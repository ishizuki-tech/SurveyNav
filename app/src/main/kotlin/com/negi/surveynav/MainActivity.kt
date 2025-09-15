// file: app/src/main/java/com/negi/surveynav/MainActivity.kt
@file:Suppress("UnusedParameter")

package com.negi.surveynav

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.rememberSceneSetupNavEntryDecorator
import com.negi.surveynav.screens.DoneScreen
import com.negi.surveynav.screens.IntroScreen
import com.negi.surveynav.screens.MultiChoiceScreen
import com.negi.surveynav.screens.ReviewScreen
import com.negi.surveynav.screens.SingleChoiceScreen
import com.negi.surveynav.slm.InferenceModel
import com.negi.surveynav.slm.MediaPipeRepository
import com.negi.surveynav.slm.Repository
import com.negi.surveynav.ui.AiScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ============================================================
 * Activity：エントリポイント
 * ============================================================ */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(Modifier.fillMaxSize()) { _ ->
                    AppNav()
                }
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
    key: Any? = Unit,
    modifier: Modifier = Modifier,
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
                // ★ ここで「待つ」。別スコープで launch しない & Main を使わない
                withContext(Dispatchers.Default) {
                    InferenceModel.getInstance(appContext).ensureLoaded(modelFile.absolutePath)
                }
            }
        ) {
            // ★ 初期化成功後のみ通常UIを構築（VM生成もここ）
            val backStack = rememberNavBackStack(FlowHome)

            val config = remember(appContext) {
                com.negi.surveynav.config.SurveyConfigLoader.fromAssets(
                    context = appContext,
                    fileName = "survey_config.json"
                )
            }

            val repo: Repository = remember(appContext) { MediaPipeRepository(appContext) }

            val vmSurvey: SurveyViewModel = viewModel(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        SurveyViewModel(nav = backStack, config = config) as T
                }
            )

            val vmAI: AiViewModel = viewModel(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
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
    vmAI:     AiViewModel,
    backStack: NavBackStack
) {
    Box(Modifier.fillMaxSize().imePadding()) {
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
                        onNext = { vmSurvey.advanceToNext()  },
                        onBack = { vmSurvey.backToPrevious() }
                    )
                }

                entry<FlowSingle> {
                    val node by vmSurvey.currentNode.collectAsState()
                    if (node.type != NodeType.SINGLE_CHOICE) return@entry

                    SingleChoiceScreen(
                        nodeId = node.id,
                        vm = vmSurvey,
                        onNext = { vmSurvey.advanceToNext() },
                        onBack = { vmSurvey.backToPrevious() }
                    )
                }

                entry<FlowMulti> {
                    val node by vmSurvey.currentNode.collectAsState()
                    if (node.type != NodeType.MULTI_CHOICE) return@entry

                    MultiChoiceScreen(
                        nodeId = node.id,
                        vm = vmSurvey,
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
                        nodeId = node.id,
                        vm = vmSurvey,
                        onNext = { vmSurvey.advanceToNext() },
                        onBack = { vmSurvey.backToPrevious() }
                    )
                }

                entry<FlowDone> {
                    val node by vmSurvey.currentNode.collectAsState()
                    if (node.type != NodeType.DONE) return@entry

                    DoneScreen(
                        nodeId = node.id,
                        vm = vmSurvey,
                        onRestart = {
                            backStack.clear()
                            vmSurvey.resetToStart()
                            vmSurvey.resetQuestions()
                        }
                    )
                }
            }
        )
    }
}
