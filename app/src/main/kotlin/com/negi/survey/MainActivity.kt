@file:Suppress("UnusedParameter", "UNCHECKED_CAST")

package com.negi.survey

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.config.toSlmMpConfigMap
import com.negi.survey.net.GitHubUploader
import com.negi.survey.screens.*
import com.negi.survey.slm.*
import com.negi.survey.ui.theme.SurveyNavTheme
import com.negi.survey.vm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ------------------------------------------------------------
        // Load YAML config safely
        // ------------------------------------------------------------
        val configResult = runCatching {
            SurveyConfigLoader.fromAssets(this, "survey_config1.yaml").also { it.validateOrThrow() }
        }
        val config: SurveyConfig? = configResult.getOrNull()
        val md = config?.modelDefaultsOrFallback() ?: SurveyConfig.ModelDefaults()

        // ------------------------------------------------------------
        // AppViewModel (download manager)
        // ------------------------------------------------------------
        val appFactory = AppViewModel.factory(
            url = md.defaultModelUrl,
            fileName = md.defaultFileName,
            timeoutMs = md.timeoutMs,
            uiThrottleMs = md.uiThrottleMs,
            uiMinDeltaBytes = md.uiMinDeltaBytes
        )
        val appVm = ViewModelProvider(this, appFactory)[AppViewModel::class.java]

        // ------------------------------------------------------------
        // Compose entry point
        // ------------------------------------------------------------
        setContent {
            SurveyNavTheme {
                val context = LocalContext.current
                val snackbar = remember { SnackbarHostState() }
                var showDownload by rememberSaveable { mutableStateOf(true) }

                // YAML load error handling
                configResult.exceptionOrNull()?.let { err ->
                    ErrorScreen(err, snackbar)
                    return@SurveyNavTheme
                }

                if (showDownload) {
                    // ---------------- Download Phase ----------------
                    DownloadScreen(vm = appVm) { showDownload = false }
                } else {
                    // ---------------- Main Phase ----------------
                    val backStack = rememberNavBackStack(FlowHome)

                    val modelFile = remember { appVm.expectedLocalFile(context) }
                    val modelPath = remember(modelFile) { modelFile.absolutePath }

                    val slmCfg = remember(config?.hashCode()) { config?.toSlmMpConfigMap() }

                    val slmModel = remember(modelPath) {
                        Model(
                            name = md.defaultFileName,
                            taskPath = modelPath,
                            config = slmCfg ?: mapOf(
                                ConfigKey.ACCELERATOR to Accelerator.GPU.name,
                                ConfigKey.MAX_TOKENS to 1024
                            )
                        )
                    }

                    val repo: Repository = remember(slmModel, config) {
                        SlmDirectRepository(slmModel, config!!)
                    }

                    // --------------------------------------------------------
                    // AiViewModel (SLM controller)
                    // --------------------------------------------------------
                    val aiVm: AiViewModel = viewModel(
                        key = "AiViewModel",
                        factory = object : ViewModelProvider.Factory {
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                return AiViewModel(repo) as T
                            }
                        }
                    )

                    // --------------------------------------------------------
                    // SurveyViewModel (YAML-driven flow)
                    // --------------------------------------------------------
                    val vmSurvey: SurveyViewModel = viewModel(
                        key = "SurveyVM",
                        factory = SurveyViewModel.factory(nav = backStack, config = config!!)
                    )

                    // --------------------------------------------------------
                    // Initialize SLM (offload to IO to avoid ANR)
                    // --------------------------------------------------------
                    LaunchedEffect(modelPath) {
                        aiVm.setInitializing(true)

                        withContext(Dispatchers.IO) {
                            SLM.initialize(context.applicationContext, slmModel) { msg ->
                                // コールバック内は通常関数なので launch(Main) でUI更新
                                kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                                    if (msg.isNotEmpty()) {
                                        Toast.makeText(
                                            context,
                                            "SLM init failed: $msg",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        aiVm.setInitialized(false)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "SLM initialized",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        aiVm.setInitialized(true)
                                    }
                                }
                            }
                        }
                    }

                    val ui by aiVm.ui.collectAsState()
                    // --------------------------------------------------------
                    // Blocking overlay while SLM initializing
                    // --------------------------------------------------------
                    if (ui.isInitializing) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .imePadding(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "Initializing SLM engine…",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    else {

                        Box(Modifier.fillMaxSize()) {
                            // --------------------------------------------------------
                            // Main Navigation Host
                            // --------------------------------------------------------
                            SurveyNavHost(
                                vmSurvey = vmSurvey,
                                vmAI = aiVm,
                                backStack = backStack
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Survey Navigation Host
// ============================================================================
@Composable
fun SurveyNavHost(
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    backStack: NavBackStack<NavKey>
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        NavDisplay(
            backStack = backStack,
            entryProvider = entryProvider {
                // ---------------- Home ----------------
                entry<FlowHome> {
                    IntroScreen(
                        onStart = {
                            vmSurvey.resetToStart()
                            vmSurvey.advanceToNext()
                        }
                    )
                }

                // ---------------- Text Input ----------------
                entry<FlowText> {
                    val node by vmSurvey.currentNode.collectAsState()
                    if (node.type != NodeType.TEXT) return@entry
                    AiScreen(
                        nodeId = node.id,
                        vmSurvey = vmSurvey,
                        vmAI = vmAI,
                        onNext = { vmSurvey.advanceToNext() },
                        onBack = { vmSurvey.backToPrevious() }
                    )
                }

                // ---------------- AI Interaction ----------------
                entry<FlowAI> {
                    val node by vmSurvey.currentNode.collectAsState()
                    if (node.type != NodeType.AI) return@entry
                    AiScreen(
                        nodeId = node.id,
                        vmSurvey = vmSurvey,
                        vmAI = vmAI,
                        onNext = { vmSurvey.advanceToNext() },
                        onBack = { vmSurvey.backToPrevious() }
                    )
                }

                // ---------------- Review ----------------
                entry<FlowReview> {
                    val node by vmSurvey.currentNode.collectAsState()
                    if (node.type != NodeType.REVIEW) return@entry
                    ReviewScreen(
                        vm = vmSurvey,
                        onNext = { vmSurvey.advanceToNext() },
                        onBack = { vmSurvey.backToPrevious() }
                    )
                }

                // ---------------- Done ----------------
                entry<FlowDone> {
                    val node by vmSurvey.currentNode.collectAsState()
                    if (node.type != NodeType.DONE) return@entry

                    val gh = runCatching {
                        if (BuildConfig.GH_TOKEN.isNotEmpty()) {
                            GitHubUploader.GitHubConfig(
                                owner = BuildConfig.GH_OWNER,
                                repo = BuildConfig.GH_REPO,
                                branch = BuildConfig.GH_BRANCH,
                                pathPrefix = BuildConfig.GH_PATH_PREFIX,
                                token = BuildConfig.GH_TOKEN
                            )
                        } else null
                    }.getOrNull()

                    DoneScreen(
                        vm = vmSurvey,
                        onRestart = { vmSurvey.resetToStart() },
                        gitHubConfig = gh
                    )
                }
            }
        )

        // ----------------------------------------------------
        // Global back handler: resets AI and navigates backward
        // ----------------------------------------------------
        BackHandler(enabled = true) {
            vmAI.resetStates()
            vmSurvey.backToPrevious()
        }
    }
}
