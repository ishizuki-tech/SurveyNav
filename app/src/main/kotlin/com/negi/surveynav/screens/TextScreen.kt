package com.negi.surveynav.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.negi.surveynav.vm.SurveyViewModel
import com.negi.surveynav.vm.UiEvent
import kotlinx.coroutines.flow.map
import kotlin.text.orEmpty

@Composable
fun TextScreen(
    nodeId: String,
    vm: SurveyViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // VM の answers から対象ノードのテキストだけを監視
    val text by remember(vm, nodeId) {
        vm.answers.map { it[nodeId].orEmpty() }
    }.collectAsState(initial = vm.getAnswer(nodeId))

    val snack = remember { SnackbarHostState() }

    // 初期フォーカス & キーボード開く
    LaunchedEffect(nodeId) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    // Snackbar リッスン
    LaunchedEffect(Unit) {
        vm.events.collect { e -> if (e is UiEvent.Snack) snack.showSnackbar(e.message) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).verticalScroll(scroll).fillMaxSize()
        ) {
            Text("自由記述", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("具体的に書くほど後のAI評価が有利になります。")
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { vm.setAnswer(it, nodeId) },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                minLines = 5,
                label = { Text("Your answer") },
                //keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // ★ onNext を“実行”させる（以前の onNext はラムダ参照で未実行だった）
                //keyboardActions = KeyboardActions(onDone = { onNext() })
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBack) { Text("戻る") }
                Button(onClick = onNext, enabled = text.isNotBlank()) { Text("次へ") }
            }
        }
    }
}
