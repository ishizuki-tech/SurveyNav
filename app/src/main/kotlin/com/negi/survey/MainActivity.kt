@file:Suppress("UnusedParameter")

package com.negi.survey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSavedStateNavEntryDecorator
import androidx.navigation3.scene.rememberSceneSetupNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.net.GitHubConfig
import com.negi.survey.screens.AiScreen
import com.negi.survey.screens.DoneScreen
import com.negi.survey.screens.IntroScreen
import com.negi.survey.screens.ReviewScreen
import com.negi.survey.screens.UploadProgressOverlay
import com.negi.survey.slm.Accelerator
import com.negi.survey.slm.ConfigKey
import com.negi.survey.slm.InferenceModel
import com.negi.survey.slm.MediaPipeRepository
import com.negi.survey.slm.Model
import com.negi.survey.slm.Repository
import com.negi.survey.slm.SLM
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.AppViewModel
import com.negi.survey.vm.DlState
import com.negi.survey.vm.DownloadGate
import com.negi.survey.vm.FlowAI
import com.negi.survey.vm.FlowDone
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.FlowReview
import com.negi.survey.vm.FlowText
import com.negi.survey.vm.NodeType
import com.negi.survey.vm.SurveyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/* ============================================================
 * Activity：エントリポイント
 * ============================================================ */
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

/* ============================================================
 * InitGate: 初期化完了までスピナー＋リトライ
 *  - key が変わると再実行（isLoading/error もリセット）
 *  - init は suspend を完了させる（launch しない）
 * ============================================================ */
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

/* ============================================================
 * NavDisplay ルート
 * ============================================================ */
@Composable
fun AppNav() {

    val appContext = LocalContext.current.applicationContext
    val appVm: AppViewModel = viewModel(factory = AppViewModel.factory())
    val state by appVm.state.collectAsState()

    // 初回起動時にダウンロードを開始（Idle のときだけ）
    LaunchedEffect(state) {
        if (state is DlState.Idle) {
            appVm.ensureModelDownloaded(appContext)
        }
    }

    DownloadGate(
        state = state,
        onRetry = { appVm.ensureModelDownloaded(appContext) }
    ) { modelFile ->

        // モデル初期化が終わるまでスピナー
        InitGate(
            key = modelFile.absolutePath,
            progressText = "Initializing Small Language Model …",
            onErrorMessage = { "Failed to initialize model: ${it.message}" },
            init = {
                val model = Model(
                    name = "Gemma-3-1B-it",
                    taskPath = modelFile.absolutePath,
                    config = mapOf(
                        ConfigKey.MAX_TOKENS to 2048,
                        ConfigKey.TOP_K to 40,
                        ConfigKey.TOP_P to 0.9f,
                        ConfigKey.TEMPERATURE to 0.7f,
                        ConfigKey.ACCELERATOR to Accelerator.GPU.label
                    )
                )
                // InferenceModel にセット
                InferenceModel.getInstance(appContext).setModel(model)
                // 実際に MediaPipe のエンジン/セッションを作って待つ（非同期APIをsuspendでラップ）
                withContext(Dispatchers.Default) {
                    suspendCancellableCoroutine { cont ->
                        SLM.initialize(appContext, model) { err ->
                            if (err.isEmpty()) {
                                cont.resume(Unit)
                            } else {
                                cont.resumeWithException(IllegalStateException(err))
                            }
                        }
                    }
                }
            }
        ) {
            // ★ 初期化成功後のみ通常UIを構築（VM生成もここ）
            val backStack = rememberNavBackStack(FlowHome)
            val config = remember(appContext) {
                SurveyConfigLoader.fromAssets(
                    context = appContext,
                    fileName = "survey_config.json"
                )
            }
            val repo: Repository = remember(appContext) { MediaPipeRepository(appContext) }
            val vmSurvey: SurveyViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        SurveyViewModel(nav = backStack, config = config) as T
                }
            )
            val vmAI: AiViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        AiViewModel(repo) as T
                }
            )
            SurveyNavHost(vmSurvey, vmAI, backStack)
        }
    }
}

@Composable
fun SurveyNavHost(
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    backStack: NavBackStack<NavKey>
) {
    Box(
        Modifier
            .fillMaxSize()
            .imePadding()
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
                            owner = BuildConfig.GH_OWNER,             // "ishizuki-tech"
                            repo = BuildConfig.GH_REPO,               // "SurveyNav"
                            branch = BuildConfig.GH_BRANCH,           // "main"
                            pathPrefix = BuildConfig.GH_PATH_PREFIX,  // "exports"
                            token = BuildConfig.GH_TOKEN              // PAT
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

        BackHandler(enabled = true) {
            vmAI.resetStates()
            vmSurvey.backToPrevious()
        }
    }
}
