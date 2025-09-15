package com.negi.surveynav.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.negi.surveynav.SurveyViewModel
import com.negi.surveynav.UiEvent

@Composable
fun MultiChoiceScreen(
    nodeId: String,
    vm: SurveyViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val options = listOf("灌漑あり", "天水のみ", "短期雨季", "長期雨季", "傾斜地", "平坦地")
    val selected = remember { mutableStateListOf<String>().apply { addAll(vm.getMultiChoices()) } }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { e -> if (e is UiEvent.Snack) snack.showSnackbar(e.message) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text("複数選択", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            options.forEach { opt ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selected.contains(opt),
                        onCheckedChange = {
                            vm.toggleMultiChoice(opt)
                            if (selected.contains(opt)) selected.remove(opt) else selected.add(opt)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(opt)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBack) { Text("戻る") }
                Button(onClick = onNext, enabled = selected.isNotEmpty()) { Text("次へ") }
            }
        }
    }
}
