@file:Suppress("UnusedParameter")

package com.negi.survey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.scene.rememberSceneSetupNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.net.GitHubConfig
import com.negi.survey.screens.*
import com.negi.survey.slm.*
import com.negi.survey.vm.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Entry point of the Android application.
 * Sets up edge-to-edge content and launches the main navigation composable.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                AppNav()
            }
        }
    }
}

/**
 * A Composable gate that shows loading or retry UI while performing initialization.
 * - It resets state when the key changes.
 * - The init block should suspend until initialization is complete.
 */
@Composable
fun InitGate(
    modifier: Modifier = Modifier,
    key: Any? = Unit,
    init: suspend () -> Unit,
    progressText: String = "Initializing…",
    onErrorMessage: (Throwable) -> String = { it.message ?: "Initialization failed" },
    content: @Composable () -> Unit
) {
    var isLoading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<Throwable?>(null) }
    val scope = rememberCoroutineScope()

    // Triggers initialization coroutine
    fun kick() {
        isLoading = true
        error = null
        scope.launch {
            try {
                init()
                isLoading = false
            } catch (t: Throwable) {
                error = t
                isLoading = false
            }
        }
    }

    LaunchedEffect(key) { kick() }

    when {
        isLoading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(progressText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        error != null -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(onErrorMessage(error!!), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { kick() }) { Text("Retry") }
                }
            }
        }
        else -> content()
    }
}

/**
 * Root-level navigation controller for the app.
 * Handles model downloading and initialization of the Small Language Model (SLM).
 */
@Composable
fun AppNav() {
    val appContext = LocalContext.current.applicationContext
    val appVm: AppViewModel = viewModel(factory = AppViewModel.factory())
    val state by appVm.state.collectAsState()

    // Trigger model download on first launch if in idle state
    LaunchedEffect(state) {
        if (state is DlState.Idle) {
            appVm.ensureModelDownloaded(appContext)
        }
    }

    DownloadGate(
        state = state,
        onRetry = { appVm.ensureModelDownloaded(appContext) }
    ) { modelFile ->

        // Remember SLM model instance
        val slmModel = remember(modelFile.absolutePath) {
            Model(
                name = "gemma-3n-E4B-it",
                taskPath = modelFile.absolutePath,
                config = mapOf(
                    ConfigKey.ACCELERATOR to Accelerator.GPU.label,
                    ConfigKey.MAX_TOKENS to 2048,
                    ConfigKey.TOP_K to 1,
                    ConfigKey.TOP_P to 0.0f,
                    ConfigKey.TEMPERATURE to 0.0f
                )
            )
        }

        // Initialize SLM with blocking coroutine until ready
        InitGate(
            key = slmModel,
            progressText = "Initializing Small Language Model …",
            onErrorMessage = { "Failed to initialize model: ${it.message}" },
            init = {
                withContext(Dispatchers.Default) {
                    suspendCancellableCoroutine { cont ->
                        SLM.initialize(appContext, slmModel) { err ->
                            if (err.isEmpty()) cont.resume(Unit)
                            else cont.resumeWithException(IllegalStateException(err))
                        }
                    }
                }
            }
        ) {
            // Clean up SLM resources when no longer in composition
            DisposableEffect(slmModel) {
                onDispose {
                    SLM.cleanUp(slmModel) { }
                }
            }

            val backStack = rememberNavBackStack(FlowHome)

            // Load static survey configuration from assets
            val config = remember(appContext) {
                SurveyConfigLoader.fromAssets(appContext, "survey_config.json")
            }

            // Setup direct repository for SLM communication
            val repo: Repository = remember(appContext, slmModel) {
                SlmDirectRepository(appContext, slmModel)
            }

            // Provide SurveyViewModel with navigation and config
            val vmSurvey: SurveyViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SurveyViewModel(nav = backStack, config = config) as T
            })

            // Provide AiViewModel with direct SLM repository
            val vmAI: AiViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AiViewModel(repo) as T
            })

            SurveyNavHost(vmSurvey, vmAI, backStack)
        }
    }
}

/**
 * Sets up navigation host for the survey flow.
 * Delegates each navigation entry to the corresponding screen.
 */
@Composable
fun SurveyNavHost(
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    backStack: NavBackStack<NavKey>
) {
    Box(
        Modifier.fillMaxSize().imePadding()
    ) {
        UploadProgressOverlay()

        NavDisplay(
            backStack = backStack,
            entryDecorators = listOf(
                rememberSceneSetupNavEntryDecorator(),
                rememberSavedStateNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<FlowHome> {
                    IntroScreen(
                        onStart = {
                            vmSurvey.resetToStart()
                            vmSurvey.advanceToNext()
                        }
                    )
                }
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
                entry<FlowReview> {
                    val node by vmSurvey.currentNode.collectAsState()
                    if (node.type != NodeType.REVIEW) return@entry
                    ReviewScreen(
                        vm = vmSurvey,
                        onNext = { vmSurvey.advanceToNext() },
                        onBack = { vmSurvey.backToPrevious() }
                    )
                }
                entry<FlowDone> {
                    val node by vmSurvey.currentNode.collectAsState()
                    if (node.type != NodeType.DONE) return@entry
                    val gh = if (BuildConfig.GH_TOKEN.isNotEmpty()) {
                        GitHubConfig(
                            owner = BuildConfig.GH_OWNER,
                            repo = BuildConfig.GH_REPO,
                            branch = BuildConfig.GH_BRANCH,
                            pathPrefix = BuildConfig.GH_PATH_PREFIX,
                            token = BuildConfig.GH_TOKEN
                        )
                    } else null
                    DoneScreen(
                        vm = vmSurvey,
                        onRestart = { vmSurvey.resetToStart() },
                        gitHubConfig = gh
                    )
                }
            }
        )

        // Global back handler to reset AI state and navigate back
        BackHandler(enabled = true) {
            vmAI.resetStates()
            vmSurvey.backToPrevious()
        }
    }
}