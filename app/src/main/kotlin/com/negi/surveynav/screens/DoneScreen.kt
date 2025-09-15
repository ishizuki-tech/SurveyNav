package com.negi.surveynav.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.negi.surveynav.SurveyViewModel

@Composable
fun DoneScreen(
    nodeId: String,
    vm: SurveyViewModel,
    onRestart: () -> Unit
) {
    val text = vm.getAnswer("TEXT1")
    val single = vm.getSingleChoice() ?: "-"
    val multi = vm.getMultiChoices().joinToString()

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text("完了", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("■ 記述：$text")
            Text("■ 単一：$single")
            Text("■ 複数：$multi")
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRestart) { Text("最初に戻る") }
        }
    }
}
