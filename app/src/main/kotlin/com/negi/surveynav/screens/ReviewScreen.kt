// file: com/negi/surveynav/screens/ReviewScreen.kt
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
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.surveynav.SurveyViewModel

@Composable
fun ReviewScreen(
    nodeId: String,
    vm: SurveyViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    // ---- Smaller base typography ----
    val smallBase = MaterialTheme.typography.bodySmall.copy(
        fontSize = 10.sp,
        lineHeight = 13.sp
    )
    val titleSmallTight = MaterialTheme.typography.titleSmall.copy(
        fontSize = 12.sp,
        lineHeight = 14.sp
    )
    val labelTight = MaterialTheme.typography.labelMedium.copy(
        fontSize = 10.sp,
        lineHeight = 12.sp
    )
    val bodyTight = MaterialTheme.typography.bodySmall.copy(
        fontSize = 10.sp,
        lineHeight = 13.sp
    )

    val allQuestions by vm.questions.collectAsState()
    val allAnswers by vm.answers.collectAsState()
    val allFollowups by vm.followups.collectAsState()

    CompositionLocalProvider(LocalTextStyle provides smallBase) {
        Scaffold { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Text("==Review==", style = titleSmallTight)
                Spacer(Modifier.height(8.dp))

                // ===== Q&A =====
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("すべてのQ&A", style = titleSmallTight)
                        Spacer(Modifier.height(6.dp))

                        // デフォルトをより小さめに
                        ProvideTextStyle(bodyTight) {
                            val entries = allAnswers.entries.sortedBy { it.key }
                            if (entries.isEmpty()) {
                                Text("（まだ回答がありません）")
                            } else {
                                entries.forEachIndexed { idx, (key, answer) ->
                                    if (idx > 0) Divider(Modifier.padding(vertical = 6.dp))
                                    Column {
                                        Text(
                                            text = key,
                                            style = labelTight,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "Q: " + (allQuestions[key].orEmpty()
                                                .ifBlank { "—（質問なし）" })
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "A: " + (answer.ifBlank { "—（未回答）" }),
                                            maxLines = 6,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ===== Followups =====
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("フォローアップ履歴（全ノード）", style = titleSmallTight)
                        Spacer(Modifier.height(6.dp))

                        ProvideTextStyle(bodyTight) {
                            if (allFollowups.isEmpty()) {
                                Text("（フォローアップはまだありません）")
                            } else {
                                val sortedFollowups = allFollowups.toSortedMap()
                                sortedFollowups.forEach { (nid, list) ->
                                    HorizontalDivider(
                                        Modifier.padding(vertical = 6.dp))
                                    Text(
                                        "Node: $nid",
                                        style = labelTight,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (list.isEmpty()) {
                                        Text("  （なし）")
                                    } else {
                                        list.forEachIndexed { i, entry ->
                                            Column(Modifier.padding(top = 4.dp)) {
                                                Text("${i + 1}. Q: ${entry.question}")
                                                Text("    A: ${entry.answer ?: "—（未回答）"}")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
                    Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Next") }
                }
            }
        }
    }
}
