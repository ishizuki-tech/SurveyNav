// file: com/negi/surveynav/ui/AiScreens.kt
package com.negi.surveynav.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.surveynav.AiViewModel
import com.negi.surveynav.SurveyViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(
    nodeId: String,
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    // ----- helpers -----
    val keyboard = LocalSoftwareKeyboardController.current
    val vScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    // ----- VM state -----
    val question by remember(vmSurvey, nodeId) { vmSurvey.questions.map { it[nodeId].orEmpty() } }
        .collectAsState(initial = vmSurvey.getQuestion(nodeId))
    val answer by remember(vmSurvey, nodeId) { vmSurvey.answers.map { it[nodeId].orEmpty() } }
        .collectAsState(initial = vmSurvey.getAnswer(nodeId))

    val loading by vmAI.loading.collectAsState()
    val score by vmAI.score.collectAsState()
    val stream by vmAI.stream.collectAsState()
    val raw by vmAI.raw.collectAsState()
    val error by vmAI.error.collectAsState()
    val followupQuestion by vmAI.followupQuestion.collectAsState()

    // ----- effects -----
    LaunchedEffect(nodeId) {
        // Focus the answer field on first appearance
        focusRequester.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it) } }

    // When a follow-up is produced (and loading is finished), record & display it once
    LaunchedEffect(followupQuestion, loading, nodeId) {
        val q = followupQuestion
        if (!loading && q != null) {
            vmSurvey.addFollowupQuestion(nodeId, q)
            vmSurvey.setQuestion(q, nodeId)
        }
    }

    // Kick off evaluation
    fun startEvaluation(curQuestion: String, curAnswer: String) {
        if (curAnswer.isBlank() || loading) return
        vmSurvey.answerLastFollowup(nodeId, curAnswer)
        scope.launch {
            vmAI.evaluateAsync(vmSurvey.getPrompt(nodeId, curQuestion, curAnswer))
        }
    }

    // Typography for dense UI
    val smallStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(title = { Text("Question Eval. $nodeId", style = smallStyle) })
        },
        bottomBar = {
            BottomAppBar(containerColor = Color.Transparent) {
                Button(
                    onClick = {
                        vmAI.resetStates()
                        onBack()
                    }
                ) { Text("Back", style = smallStyle) }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = {
                        focusRequester.freeFocus()
                        keyboard?.hide()
                        startEvaluation(question, answer)
                    },
                    enabled = answer.isNotBlank() && !loading
                ) {
                    Text(if (score == null) "Submit" else "Retry", style = smallStyle)
                }

                Spacer(Modifier.weight(1f))

                OutlinedButton(
                    onClick = {
                        vmAI.resetStates()
                        onNext()
                    }
                ) { Text("Next", style = smallStyle) }
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        CompositionLocalProvider(LocalTextStyle provides smallStyle) {
            Column(
                Modifier
                    .padding(pad)
                    .padding(20.dp)
                    .fillMaxSize()
                    .verticalScroll(vScroll) // single vertical scroller for the whole screen
            ) {
                // Read-only follow-up question (avoid accidental edits)
                OutlinedTextField(
                    value = question,
                    onValueChange = { /* readOnly */ },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current,
                    label = { Text("Question", style = LocalTextStyle.current) },
                )

                // User answer
                OutlinedTextField(
                    value = answer,
                    onValueChange = { vmSurvey.setAnswer(it, nodeId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    minLines = 5,
                    textStyle = LocalTextStyle.current,
                    label = { Text("Your answer", style = LocalTextStyle.current) },
                )

                Spacer(Modifier.height(16.dp))

                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }

                // Result area: JSON (pretty) or streaming text fallback
                if (!raw.isNullOrBlank()) {
                    val json = remember {
                        Json {
                            prettyPrint = true
                            prettyPrintIndent = "  "
                            ignoreUnknownKeys = true
                        }
                    }
                    val pretty = remember(raw) { prettyOrRaw(json, raw!!) }

                    JsonCard(pretty = pretty, score = score)
                } else {
                    Text("Output From SLM.")
                    Spacer(Modifier.height(6.dp))
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (stream.isBlank()) "Waiting for response …" else stream)
                        }
                    }
                }
            }
        }
    }
}

/* -------------------------------- Helpers -------------------------------- */

@Composable
private fun JsonCard(
    pretty: String,
    score: Int?
) {
    // Horizontal scroll only (parent column already controls vertical scroll)
    val hScroll = rememberScrollState()

    Box(Modifier.fillMaxWidth()) {
        Surface(
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(4.dp)
                .horizontalScroll(hScroll)
        ) {
            SelectionContainer {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        text = pretty,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    )
                    if (score != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("Score: $score / 100")
                    }
                }
            }
        }

        // Overlay a lightweight horizontal scrollbar when scrollable
        val showBar by remember { derivedStateOf { hScroll.maxValue > 0 } }
        if (showBar) {
            HorizontalScrollbar(
                scrollState = hScroll,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Pretty print JSON; if parsing fails, return the original raw string.
 */
private fun prettyOrRaw(json: Json, raw: String): String =
    runCatching {
        json.encodeToString(kotlinx.serialization.json.Json.parseToJsonElement(raw))
    }.getOrElse { raw }

/* --------------------------- Horizontal Scrollbar -------------------------- */

@Composable
private fun HorizontalScrollbar(
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    thickness: Dp = 3.dp,
    thumbMinWidth: Dp = 24.dp,
) {
    val radius = thickness / 2
    val density = LocalDensity.current
    var viewportPx by remember { mutableStateOf(0) }

    // ★ Composable な値は drawWithContent の外で評価してキャプチャ
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Box(
        modifier
            .height(thickness)
            .fillMaxWidth()
            .background(trackColor, RoundedCornerShape(radius))
            .onGloballyPositioned { viewportPx = it.size.width }
            .drawWithContent {
                drawContent()

                val max = scrollState.maxValue // contentWidth - viewportWidth
                if (viewportPx <= 0 || max <= 0) return@drawWithContent

                val viewport = viewportPx.toFloat()
                val content = viewport + max
                val thumbW = (viewport * viewport / content)
                    .coerceAtLeast(with(density) { thumbMinWidth.toPx() })
                    .coerceAtMost(viewport)

                val progress = scrollState.value.toFloat() / max
                val trackW = viewport - thumbW
                val thumbX = trackW * progress

                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(thumbX, 0f),
                    size = Size(thumbW, size.height),
                    cornerRadius = CornerRadius(with(density) { radius.toPx() })
                )
            }
    )
}
