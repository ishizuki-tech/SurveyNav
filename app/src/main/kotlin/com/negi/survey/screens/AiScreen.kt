// file: app/src/main/java/com/negi/survey/screens/AiScreen.kt
package com.negi.survey.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.negi.survey.slm.FollowupExtractor.extractScore
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.SurveyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Locale

/* =============================================================================
 * AI Evaluation Screen — Modern × Monotone × Chic
 * - STT: SpeechRecognizer (partial → composer / final → submit)
 * - TTS: TextToSpeech (reads AI text/follow-ups with mute toggle)
 * - NEW: A small "speak" icon inside the question bubble (tap = speak, long-press = stop)
 * =============================================================================
 */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSerializationApi::class)
@Composable
fun AiScreen(
    nodeId: String,
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    // --- Survey state ---
    val question by remember(vmSurvey, nodeId) {
        vmSurvey.questions.map { it[nodeId].orEmpty() }
    }.collectAsState(initial = vmSurvey.getQuestion(nodeId))

    // --- AI state ---
    val loading by vmAI.loading.collectAsState()
    val stream by vmAI.stream.collectAsState()
    val raw by vmAI.raw.collectAsState()
    val error by vmAI.error.collectAsState()
    val followup by vmAI.followupQuestion.collectAsState()

    // Chat list per node
    val chat by remember(nodeId) { vmAI.chatFlow(nodeId) }.collectAsState()

    // --- Local UI state ---
    var composer by remember(nodeId) { mutableStateOf(vmSurvey.getAnswer(nodeId)) }
    val focusRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()

    // ─────────────────────────── TTS (Text-to-Speech) ───────────────────────────
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsMuted by remember { mutableStateOf(false) }
    val preferredLocale = remember { Locale.getDefault() }

    fun speak(text: String) {
        // Use QUEUE_FLUSH to replace any ongoing utterance
        if (!ttsReady || ttsMuted || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai-$nodeId-${System.nanoTime()}")
    }

    fun stopSpeak() {
        tts?.stop()
    }

    LaunchedEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = tts?.setLanguage(preferredLocale) ?: TextToSpeech.LANG_MISSING_DATA
                ttsReady = langResult != TextToSpeech.LANG_MISSING_DATA && langResult != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    scope.launch { snack.showSnackbar("TTS language not supported: ${preferredLocale.displayName}") }
                }
            } else {
                ttsReady = false
                scope.launch { snack.showSnackbar("Failed to initialize TextToSpeech") }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { tts?.stop(); tts?.shutdown() }
    }

    // Auto-speak follow-up when available
    LaunchedEffect(followup, loading, ttsReady, ttsMuted) {
        val fu = followup
        if (fu != null && !loading && ttsReady && !ttsMuted) speak(fu)
    }

    // ───────── Helpers to avoid stale captures inside long-lived callbacks ─────────
    val loadingLatest by rememberUpdatedState(loading)
    val submitLatest by rememberUpdatedState(newValue = {
        val answer = composer.trim()
        if (answer.isBlank() || loadingLatest) return@rememberUpdatedState
        vmSurvey.setAnswer(answer, nodeId)
        vmSurvey.answerLastFollowup(nodeId, answer)

        vmAI.chatAppend(
            nodeId,
            AiViewModel.ChatMsgVm(
                id = "u-$nodeId-${System.nanoTime()}",
                sender = AiViewModel.ChatSender.USER,
                text = answer
            )
        )
        scope.launch {
            val q = vmSurvey.getQuestion(nodeId)
            val prompt = vmSurvey.getPrompt(nodeId, q, answer)
            vmAI.evaluateAsync(prompt)
        }
        composer = ""
    })
    val setComposerLatest by rememberUpdatedState(newValue = { s: String -> composer = s })

    // ─────────────────────────── STT (SpeechRecognizer) ───────────────────────────
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context)
        else null
    }
    var micRecording by remember { mutableStateOf(false) }

    fun mapSpeechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
        else -> "Unknown error ($code)"
    }

    fun startStt() {
        if (speechRecognizer == null) {
            scope.launch { snack.showSnackbar("Speech recognition is not available on this device") }
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            // putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                micRecording = true
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                micRecording = false
                scope.launch { snack.showSnackbar(mapSpeechError(error)) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = list?.firstOrNull()
                if (!partial.isNullOrBlank() && !loadingLatest) setComposerLatest(partial)
            }

            override fun onResults(results: Bundle?) {
                micRecording = false
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (best.isNotBlank()) {
                    setComposerLatest(best)
                    submitLatest()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { speechRecognizer.cancel() }
        micRecording = true
        speechRecognizer.startListening(intent)
    }

    fun stopStt() {
        micRecording = false
        runCatching { speechRecognizer?.stopListening() }
        runCatching { speechRecognizer?.cancel() }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startStt()
        else scope.launch { snack.showSnackbar("Microphone permission denied") }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { speechRecognizer?.cancel() }
            speechRecognizer?.destroy()
        }
    }
    // ───────────── end STT ─────────────

    // Seed the first question and focus composer (keep IME open)
    LaunchedEffect(nodeId, question) {
        vmAI.chatEnsureSeedQuestion(nodeId, question)
        focusRequester.requestFocus()
        keyboard?.show()
    }
    // Restore composer text when returning to this screen
    LaunchedEffect(nodeId) { composer = vmSurvey.getAnswer(nodeId) }
    // Pipe error messages into Snackbar
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it) } }

    // Maintain/update typing bubble while streaming
    LaunchedEffect(loading, stream) {
        if (loading) {
            val txt = stream.ifBlank { "…" }
            vmAI.chatUpsertTyping(
                nodeId,
                AiViewModel.ChatMsgVm(
                    id = "typing-$nodeId",
                    sender = AiViewModel.ChatSender.AI,
                    text = txt,
                    isTyping = true
                )
            )
        }
    }

    // On final result (JSON), pretty print and replace typing bubble
    LaunchedEffect(raw, loading) {
        if (!raw.isNullOrBlank() && !loading) {
            val jsonPretty =
                Json { prettyPrint = true; prettyPrintIndent = " "; ignoreUnknownKeys = true }
            val pretty = prettyOrRaw(jsonPretty, raw!!)
            vmAI.chatReplaceTypingWith(
                nodeId,
                AiViewModel.ChatMsgVm(
                    id = "result-$nodeId-${System.nanoTime()}",
                    sender = AiViewModel.ChatSender.AI,
                    json = pretty
                )
            )
        }
    }

    // Append follow-up when idle and persist to Survey
    LaunchedEffect(followup, loading) {
        val fu = followup
        if (fu != null && !loading) {
            vmAI.chatAppend(
                nodeId,
                AiViewModel.ChatMsgVm(
                    id = "fu-$nodeId-${System.nanoTime()}",
                    sender = AiViewModel.ChatSender.AI,
                    text = fu
                )
            )
            vmSurvey.addFollowupQuestion(nodeId, fu)
            vmSurvey.setQuestion(fu, nodeId)
        }
    }

    // If finished without final raw (cancel/error), remove typing bubble
    LaunchedEffect(loading, raw) {
        if (!loading && raw.isNullOrBlank()) vmAI.chatRemoveTyping(nodeId)
    }

    // Auto-scroll on chat size change. Also auto-speak the latest AI text message.
    LaunchedEffect(chat.size) {
        delay(16)
        scroll.animateScrollTo(scroll.maxValue)
        chat.lastOrNull()?.let { m ->
            val isAiText =
                (m.sender != AiViewModel.ChatSender.USER) && !m.text.isNullOrBlank() && m.json == null
            if (isAiText && ttsReady && !ttsMuted) speak(m.text!!)
        }
    }

    // Keep pinned to bottom while streaming grows
    LaunchedEffect(stream) {
        if (loading) {
            delay(24)
            scroll.scrollTo(scroll.maxValue)
        }
    }

    // Animated monotone background brush
    val bgBrush = animatedMonotoneBackplate()

    Scaffold(
        topBar = { CompactTopBar(title = "Question • $nodeId") },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.neutralEdge(alpha = 0.14f, corner = 16.dp, stroke = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.ime)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(top = 6.dp)
                ) {
                    ChatComposer(
                        value = composer,
                        onValueChange = {
                            composer = it
                            vmSurvey.setAnswer(it, nodeId)
                        },
                        onSend = { submitLatest() },
                        enabled = !loading,
                        focusRequester = focusRequester,
                        onMicClick = {
                            if (micRecording) stopStt() else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) startStt()
                                else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        micRecording = micRecording
                    )

                    // Bottom action row: fully split left/right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgBrush)
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = { vmAI.resetStates(); onBack() },
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = Color(0xFF263238),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        ) { Text("Back") }

                        OutlinedButton(
                            onClick = { vmAI.resetStates(); onNext() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF111111)
                            ),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        ) { Text("Next") }
                    }
                }
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .background(bgBrush)
                .pointerInput(Unit) {
                    // Background tap clears focus and hides IME.
                    detectTapGestures {
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Chat list with inline "speak" icon only on the question bubble
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                chat.forEach { m ->
                    val isAi = m.sender != AiViewModel.ChatSender.USER
                    // Robust check: treat leading/trailing whitespace as equal
                    val isQuestionBubble =
                        isAi && m.json == null &&
                                m.text?.trim().orEmpty() == question.trim()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                    ) {
                        if (m.json != null) {
                            JsonBubbleMono(pretty = m.json, snack = snack)
                        } else {
                            BubbleMono(
                                text = m.text.orEmpty(),
                                isAi = isAi,
                                isTyping = m.isTyping,
                                // Speak icon only on the question bubble
                                showSpeak = isQuestionBubble,
                                canSpeak = ttsReady && !ttsMuted,
                                onSpeak = {
                                    if (ttsReady && !ttsMuted) speak(question) else scope.launch {
                                        snack.showSnackbar(if (!ttsReady) "Voice unavailable" else "Voice is muted")
                                    }
                                },
                                onStopSpeak = { stopSpeak() }
                            )
                        }
                    }
                }
            }
        }
    }

    // Clear transient AI-only state when leaving this node (keep chat history).
    DisposableEffect(Unit) { onDispose { vmAI.resetStates() } }
}

/* ───────────────────────────────── App Bar ───────────────────────────────── */

@Composable
private fun CompactTopBar(
    title: String,
    height: Dp = 32.dp
) {
    val cs = MaterialTheme.colorScheme
    val topBrush = Brush.horizontalGradient(
        listOf(
            cs.surface.copy(alpha = 0.96f),
            Color(0xFF1A1A1A).copy(alpha = 0.75f)
        )
    )
    Surface(color = Color.Transparent, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(topBrush)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(height)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                color = cs.onSurface
            )
        }
    }
}

/* ───────────────────────────── Chat bubbles ─────────────────────────────── */

@Composable
private fun BubbleMono(
    text: String,
    isAi: Boolean,
    isTyping: Boolean,
    maxWidth: Dp = 520.dp,
    // NEW: speak icon controls
    showSpeak: Boolean = false,
    canSpeak: Boolean = false,
    onSpeak: (() -> Unit)? = null,
    onStopSpeak: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme

    val corner = 12.dp
    val padH = 10.dp
    val padV = 7.dp
    val tailW = 7f
    val tailH = 6f

    val stops = if (isAi)
        listOf(Color(0xFF111111), Color(0xFF1E1E1E), Color(0xFF2A2A2A))
    else
        listOf(Color(0xFFEDEDED), Color(0xFFD9D9D9), Color(0xFFC8C8C8))

    val t = rememberInfiniteTransition(label = "bubble-mono")
    val p by t.animateFloat(
        0f, 1f,
        animationSpec = infiniteRepeatable(tween(4600, easing = LinearEasing), RepeatMode.Restart),
        label = "p"
    )
    val grad = Brush.linearGradient(
        colors = stops.map { c -> androidx.compose.ui.graphics.lerp(c, cs.surface, 0.12f) },
        start = Offset(0f, 0f),
        end = Offset(400f + 220f * p, 360f - 180f * p)
    )

    val textColor = if (isAi) Color(0xFFECECEC) else Color(0xFF111111)
    val shape = RoundedCornerShape(
        topStart = corner, topEnd = corner,
        bottomStart = if (isAi) 4.dp else corner,
        bottomEnd = if (isAi) corner else 4.dp
    )

    Surface(
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
        shape = shape,
        modifier = Modifier
            .widthIn(max = maxWidth)
            .drawBehind {
                val cr = CornerRadius(corner.toPx(), corner.toPx())
                // Fill
                drawRoundRect(brush = grad, cornerRadius = cr)
                // Tail
                val x = if (isAi) 12f else size.width - 12f
                val dir = if (isAi) -1 else 1
                drawPath(
                    path = Path().apply {
                        moveTo(x, size.height)
                        lineTo(x + dir * tailW, size.height - tailH)
                        lineTo(x + dir * tailW * 0.4f, size.height - tailH * 0.6f)
                        close()
                    },
                    brush = grad
                )
                // Subtle inner rim (soft depth)
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(0.06f), Color.Transparent),
                        center = center, radius = size.minDimension * 0.54f
                    ),
                    cornerRadius = cr
                )
            }
            .neutralEdge(alpha = 0.18f, corner = corner, stroke = 0.8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = padH, vertical = padV),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isTyping && text.isBlank()) {
                TypingDots(color = textColor)
                Spacer(Modifier.width(6.dp))
            } else {
                Text(
                    text = text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp),
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            // NEW: tiny speak icon on the right (only when showSpeak = true)
            if (showSpeak) {
                SpeakIcon(
                    enabled = canSpeak,
                    onTap = { onSpeak?.invoke() },
                    onLongPress = { onStopSpeak?.invoke() },
                    tint = textColor.copy(alpha = if (canSpeak) 1f else 0.5f)
                )
            }
        }
    }
}

@Composable
private fun TypingDots(color: Color) {
    // Simple 3-dot typing indicator with staggered alpha animation
    val t = rememberInfiniteTransition(label = "typing")
    val a1 by t.animateFloat(
        0.2f,
        1f,
        infiniteRepeatable(tween(900, 0, LinearEasing)),
        label = "a1"
    )
    val a2 by t.animateFloat(
        0.2f,
        1f,
        infiniteRepeatable(tween(900, 150, LinearEasing)),
        label = "a2"
    )
    val a3 by t.animateFloat(
        0.2f,
        1f,
        infiniteRepeatable(tween(900, 300, LinearEasing)),
        label = "a3"
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color.copy(alpha = a1), CircleShape)
        )
        Box(
            Modifier
                .size(8.dp)
                .background(color.copy(alpha = a2), CircleShape)
        )
        Box(
            Modifier
                .size(8.dp)
                .background(color.copy(alpha = a3), CircleShape)
        )
    }
}

@Composable
private fun SpeakIcon(
    enabled: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    tint: Color
) {
    // Compact icon area supporting both tap (speak) and long-press (stop)
    Box(
        modifier = Modifier
            .size(28.dp) // slightly larger touch target for accessibility
            .pointerInput(enabled) {
                detectTapGestures(
                    onTap = { if (enabled) onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (enabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
            contentDescription = if (enabled) "Speak the question" else "Voice is off",
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/* ───────────────────────────── JSON bubble ──────────────────────────────── */

@Composable
private fun JsonBubbleMono(
    pretty: String,
    collapsedMaxHeight: Dp = 72.dp,
    snack: SnackbarHostState? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val clip = RoundedCornerShape(10.dp)

    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.60f),
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
        shape = clip,
        modifier = Modifier
            .widthIn(max = 580.dp)
            .animateContentSize()
            .neutralEdge(alpha = 0.16f, corner = 10.dp, stroke = 1.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }) {
                expanded = !expanded
            }
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF1F1F1F).copy(0.22f),
                                Color(0xFF3A3A3A).copy(0.22f),
                                Color(0xFF6A6A6A).copy(0.22f)
                            )
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val scoreText = extractScore(pretty)?.let { "$it / 100" } ?: "—"
                Text(
                    text = if (expanded) "Result JSON  •  Score $scoreText  (tap to collapse)"
                    else "Score $scoreText  •  tap to expand",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    // Hook platform clipboard here if needed
                    // LocalClipboardManager.current.setText(AnnotatedString(pretty))
                    // snack?.showSnackbar("JSON copied")
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy JSON")
                }
            }

            if (expanded) {
                SelectionContainer {
                    Text(
                        text = pretty,
                        color = cs.onSurface,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace, lineHeight = 18.sp
                        ),
                        modifier = Modifier
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState())
                    )
                }
            } else {
                Text(
                    "Analysis preview…",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = collapsedMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/* ───────────────────────────── Composer ─────────────────────────────────── */

@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onMicClick: () -> Unit,
    micRecording: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 8.dp, shape = CircleShape, clip = false)
                .background(
                    Brush.linearGradient(
                        listOf(
                            cs.surfaceVariant.copy(alpha = 0.65f),
                            cs.surface.copy(alpha = 0.65f)
                        )
                    ),
                    CircleShape
                )
                .neutralEdge(alpha = 0.14f, corner = 999.dp, stroke = 1.dp)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text("Type your answer…") },
                minLines = 1,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            // Mic button (toggle)
            IconButton(
                onClick = onMicClick,
                enabled = enabled,
                modifier = Modifier.size(40.dp)
            ) {
                if (micRecording) {
                    Icon(Icons.Outlined.Stop, contentDescription = "Stop recording")
                } else {
                    Icon(Icons.Outlined.Mic, contentDescription = "Start recording")
                }
            }

            FilledTonalButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
            }
        }
    }
}

/* ─────────────────────────── Visual utilities ───────────────────────────── */

@Composable
fun animatedMonotoneBackplate(): Brush {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "bg-mono")
    val p by t.animateFloat(
        0f, 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgp"
    )

    val c0 = androidx.compose.ui.graphics.lerp(Color(0xFF0F0F10), cs.surface, 0.10f)
    val c1 = androidx.compose.ui.graphics.lerp(Color(0xFF1A1A1B), cs.surface, 0.12f)
    val c2 = androidx.compose.ui.graphics.lerp(Color(0xFF2A2A2B), cs.surface, 0.14f)
    val c3 = androidx.compose.ui.graphics.lerp(Color(0xFF3A3A3B), cs.surface, 0.16f)

    val endX = 1200f + 240f * p
    val endY = 820f - 180f * p

    return Brush.linearGradient(
        colors = listOf(c0, c1, c2, c3),
        start = Offset(0f, 0f),
        end = Offset(endX, endY)
    )
}

/**
 * Draws a very subtle sweep gradient stroke to create a neutral rim/edge.
 */
@Composable
fun Modifier.neutralEdge(
    alpha: Float = 0.16f,
    corner: Dp = 12.dp,
    stroke: Dp = 1.dp
): Modifier = this.then(
    Modifier.drawBehind {
        val cr = CornerRadius(corner.toPx(), corner.toPx())
        val sweep = Brush.sweepGradient(
            0f to Color(0xFF101010).copy(alpha = alpha),
            0.25f to Color(0xFF3A3A3A).copy(alpha = alpha),
            0.5f to Color(0xFF7A7A7A).copy(alpha = alpha * 0.9f),
            0.75f to Color(0xFF3A3A3A).copy(alpha = alpha),
            1f to Color(0xFF101010).copy(alpha = alpha)
        )
        drawRoundRect(brush = sweep, style = Stroke(width = stroke.toPx()), cornerRadius = cr)
    }
)

/* ───────────────────────────── JSON helpers ─────────────────────────────── */

private fun prettyOrRaw(json: Json, raw: String): String {
    val stripped = stripCodeFence(raw)
    val element = parseJsonLenient(json, stripped)
    return if (element != null) json.encodeToString(JsonElement.serializer(), element) else raw
}

private fun parseJsonLenient(json: Json, text: String): JsonElement? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    parseOrNull(json, trimmed)?.let { return it }
    var i = 0
    while (i < trimmed.length) {
        when (trimmed[i]) {
            '{', '[' -> {
                val end = findMatchingJsonBoundary(trimmed, i)
                if (end != -1) {
                    val candidate = trimmed.substring(i, end + 1)
                    parseOrNull(json, candidate)?.let { return it }
                    i = end
                }
            }
        }
        i++
    }
    return null
}

private fun parseOrNull(json: Json, value: String): JsonElement? =
    runCatching { json.parseToJsonElement(value) }.getOrNull()

private fun stripCodeFence(text: String): String {
    val t = text.trim()
    if (!t.startsWith("```")) return t
    val closing = t.indexOf("```", startIndex = 3)
    if (closing == -1) return t
    val newline = t.indexOf('\n', startIndex = 3)
    val contentStart = if (newline in 4 until closing) newline + 1 else 3
    return t.substring(contentStart, closing).trim()
}

private fun findMatchingJsonBoundary(text: String, start: Int): Int {
    if (start !in text.indices) return -1
    val open = text[start]
    if (open != '{' && open != '[') return -1
    val stack = ArrayDeque<Char>()
    stack.addLast(open)
    var i = start + 1
    var inString = false
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            if (c == '\\' && i + 1 < text.length) {
                i += 2; continue
            }
            if (c == '"') inString = false
        } else {
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}' -> if (stack.isEmpty() || stack.removeLast() != '{') return -1
                ']' -> if (stack.isEmpty() || stack.removeLast() != '[') return -1
            }
        }
        if (stack.isEmpty()) return i
        i++
    }
    return -1
}

/* ───────────────────────────── Preview ─────────────────────────────────── */

@SuppressLint("RememberInComposition")
@Preview(showBackground = true, name = "Chat — Monotone Chic Preview")
@Composable
private fun ChatPreview() {
    MaterialTheme {
        val fakeQuestion = "How much yield do you lose because of FAW?"
        val fake = listOf(
            AiViewModel.ChatMsgVm("q", AiViewModel.ChatSender.AI, text = fakeQuestion),
            AiViewModel.ChatMsgVm(
                "u1",
                AiViewModel.ChatSender.USER,
                text = "About 10% over 3 seasons."
            ),
            AiViewModel.ChatMsgVm(
                "r1",
                AiViewModel.ChatSender.AI,
                json = """
                    {
                      "analysis":"Clear unit",
                      "expected answer":"~10% avg loss over 3 seasons",
                      "follow-up question":"Is 10% per season or overall?",
                      "score":88
                    }
                """.trimIndent()
            ),
            AiViewModel.ChatMsgVm(
                "fu",
                AiViewModel.ChatSender.AI,
                text = "Is that 10% per season or overall?"
            )
        )
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .background(animatedMonotoneBackplate())
                .padding(16.dp)
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                fake.forEach { m ->
                    val isAi = m.sender != AiViewModel.ChatSender.USER
                    val isQuestionBubble =
                        isAi && m.json == null && m.text?.trim().orEmpty() == fakeQuestion.trim()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                    ) {
                        if (m.json != null) JsonBubbleMono(pretty = m.json)
                        else BubbleMono(
                            text = m.text.orEmpty(),
                            isAi = isAi,
                            isTyping = false,
                            showSpeak = isQuestionBubble,
                            canSpeak = true,
                            onSpeak = { /* preview no-op */ },
                            onStopSpeak = { /* preview no-op */ }
                        )
                    }
                }
            }
            ChatComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                enabled = true,
                focusRequester = FocusRequester(),
                onMicClick = {},
                micRecording = false
            )
        }
    }
}
