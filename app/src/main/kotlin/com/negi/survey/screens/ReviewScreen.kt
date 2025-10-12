// file: app/src/main/java/com/negi/survey/screens/ReviewScreen.kt
package com.negi.survey.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.survey.vm.SurveyViewModel

@Composable
fun ReviewScreen(
    vm: SurveyViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    // ---- Compact typography presets (tight but readable) ----
    val baseCompact = MaterialTheme.typography.bodySmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
    val titleTight = MaterialTheme.typography.titleSmall.copy(
        fontSize = 12.sp,
        lineHeight = 14.sp
    )
    val labelTight = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 12.sp
    )
    val bodyTight = MaterialTheme.typography.bodySmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp
    )

    // ---- Collect VM state ----
    val allQuestions by vm.questions.collectAsState()
    val allAnswers by vm.answers.collectAsState()
    val allFollowups by vm.followups.collectAsState()

    // ---- Memoized, sorted views for stable item ordering ----
    val qaEntries = remember(allAnswers, allQuestions) {
        // Pair nodeId with question + answer, sorted by nodeId for predictability
        allAnswers.entries
            .map { (id, ans) ->
                val q = allQuestions[id].orEmpty()
                Triple(id, q, ans)
            }
            .sortedBy { it.first }
    }

    val sortedFollowups = remember(allFollowups) {
        // Sort nodes by id; keep per-node followups in original order
        allFollowups.toSortedMap()
    }

    CompositionLocalProvider(LocalTextStyle provides baseCompact) {
        Scaffold(containerColor = Color.Transparent) { pad ->
            // Use LazyColumn for performance and smoother scrolling on large datasets
            LazyColumn(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ====== Header ======
                item {
                    Text("Review", style = titleTight)
                }

                // ====== Q & A Card ======
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("All Original Questions and Answers", style = titleTight)
                            Spacer(Modifier.height(6.dp))

                            if (qaEntries.isEmpty()) {
                                Text("No records yet.", style = bodyTight)
                            } else {
                                // Draw each Q/A row with dividers
                                qaEntries.forEachIndexed { idx, (nodeId, question, answer) ->
                                    if (idx > 0) {
                                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                    }

                                    Column {
                                        // Node id label
                                        Text(
                                            text = nodeId,
                                            style = labelTight,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(2.dp))

                                        // Question (show placeholder when missing)
                                        val qText =
                                            if (question.isBlank()) "– No Question." else "Q: $question"
                                        Text(qText, style = bodyTight)

                                        Spacer(Modifier.height(2.dp))

                                        // Answer (highlight missing answer a little)
                                        val aText =
                                            if (answer.isBlank()) "– No Answer."
                                            else answer
                                        Text(
                                            text = "A: $aText",
                                            maxLines = 6,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (answer.isBlank())
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            else LocalTextStyle.current.color,
                                            style = bodyTight
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ====== Followups Card ======
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Follow-up History", style = titleTight)
                            Spacer(Modifier.height(6.dp))

                            if (sortedFollowups.isEmpty()) {
                                Text("No follow-up questions.", style = bodyTight)
                            } else {
                                sortedFollowups.entries.forEachIndexed { idx, (nodeId, list) ->
                                    if (idx > 0) {
                                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                    }

                                    Text(
                                        text = "Node: $nodeId",
                                        style = labelTight,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    if (list.isEmpty()) {
                                        Text("– No follow-ups recorded.", style = bodyTight)
                                    } else {
                                        // Render each follow-up line
                                        list.forEachIndexed { i, entry ->
                                            // entry.question: String, entry.answer: String?
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "${i + 1}. Q: ${entry.question}",
                                                style = bodyTight
                                            )
                                            Text(
                                                "   A: ${entry.answer ?: "– No Answer."}",
                                                style = bodyTight,
                                                color = if (entry.answer.isNullOrBlank())
                                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                else LocalTextStyle.current.color
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ====== Bottom Buttons ======
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp)
                    ) {
                        Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
                        Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("Next") }
                    }
                }
            }
        }
    }
}
