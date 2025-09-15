// file: com/negi/surveynav/ui/AiScreens.kt
package com.negi.surveynav.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.surveynav.AiViewModel
import com.negi.surveynav.SurveyViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(
    nodeId: String,
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    // ---- tools ----
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    // ---- VM state ----
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

    LaunchedEffect(nodeId) {
        // 初回は回答欄にフォーカス
        focusRequester.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it) } }

    // ★ フォローアップが「生成された瞬間」に1回だけ記録 & 表示用に反映
    LaunchedEffect(followupQuestion, loading, nodeId) {
        val q = followupQuestion
        if (!loading && q != null) {
            vmSurvey.addFollowupQuestion(nodeId, q)  // 質問の履歴に積む（未回答）
            vmSurvey.setQuestion(q, nodeId)          // 表示中の質問を更新
        }
    }

    // ★ 送信時は回答だけを直近の未回答フォローアップに紐づける
    fun startEvaluation(curQuestion: String, curAnswer: String) {
        if (curAnswer.isBlank() || loading) return
        vmSurvey.answerLastFollowup(nodeId, curAnswer)
        scope.launch {
            vmAI.evaluateAsync(vmSurvey.getPrompt(nodeId, curQuestion, curAnswer))
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Question Eval. $nodeId", style = LocalTextStyle.current) }
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = Color.Transparent) {
                Button(
                    onClick = {
                        vmAI.resetStates()
                        onBack()
                    },
                    enabled = true
                ) {
                    Text("Back", style = LocalTextStyle.current)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        focusRequester.freeFocus()
                        keyboard?.hide()
                        startEvaluation(question, answer)
                    },
                    enabled = answer.isNotBlank() && !loading
                ) {
                    Text(if (score == null) "Submit" else "Retry", style = LocalTextStyle.current)
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        vmAI.resetStates()
                        onNext()
                    },
                    enabled = true
                ) { Text("Next", style = LocalTextStyle.current) }
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->

        val smallStyle = MaterialTheme.typography.bodySmall.copy(
            fontSize = 11.sp, lineHeight = 14.sp
        )

        CompositionLocalProvider(LocalTextStyle provides smallStyle) {
            Column(
                Modifier
                    .padding(pad)
                    .padding(20.dp)
                    .verticalScroll(scroll)
                    .fillMaxSize()
            ) {
                // フォローアップの質問は読み取り専用（誤編集防止）
                OutlinedTextField(
                    value = question,
                    onValueChange = { /* readOnly */ },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current,
                    label = { Text("Question", style = LocalTextStyle.current) },
                )

                OutlinedTextField(
                    value = answer,
                    onValueChange = { vmSurvey.setAnswer(it, nodeId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    minLines = 5,
                    textStyle = LocalTextStyle.current,
                    label = { Text("Your answer", style = LocalTextStyle.current) },
                    // keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    // keyboardActions = KeyboardActions(onDone = { startEvaluation(question, answer) })
                )

                Spacer(Modifier.height(16.dp))

                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }

                if (!raw.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Response from SLM.", style = LocalTextStyle.current)
                    Spacer(Modifier.height(4.dp))
                    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(7.dp)) {
                            Text(raw!!)
                        }
                    }
                } else {
                    // 途中経過（stream）は loading 中に更新され続ける
                    Text("AI出力（ライブ）", style = LocalTextStyle.current)
                    Spacer(Modifier.height(6.dp))
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (stream.isBlank()) "出力待ち…" else stream)
                            if (score != null) {
                                Spacer(Modifier.height(8.dp))
                                Text("Score: $score / 100")
                            }
                        }
                    }
                }
            }
        }
    }
}
