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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.negi.surveynav.SurveyViewModel
import com.negi.surveynav.UiEvent

@Composable
fun SingleChoiceScreen(
    nodeId: String,
    vm: SurveyViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val options = listOf("収量", "早生", "耐病性", "価格", "味・品質")
    var selected by remember { mutableStateOf(vm.getSingleChoice()) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { e -> if (e is UiEvent.Snack) snack.showSnackbar(e.message) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text("単一選択", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            options.forEach { opt ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected == opt,
                        onClick = {
                            selected = opt
                            vm.setSingleChoice(opt)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(opt)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBack) { Text("戻る") }
                Button(onClick = onNext, enabled = !selected.isNullOrBlank()) { Text("次へ") }
            }
        }
    }
}
