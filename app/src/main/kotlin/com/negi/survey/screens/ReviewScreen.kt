package com.negi.survey.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val baseCompact = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
    val titleTight = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 14.sp)
    val labelTight = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
    val bodyTight = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp)

    val allQuestions by vm.questions.collectAsState()
    val allAnswers by vm.answers.collectAsState()
    val allFollowups by vm.followups.collectAsState()

    val qaEntries = remember(allAnswers, allQuestions) {
        allAnswers.entries.map { (id, ans) ->
            val q = allQuestions[id].orEmpty()
            Triple(id, q, ans)
        }.sortedBy { it.first }
    }

    val sortedFollowups = remember(allFollowups) { allFollowups.toSortedMap() }

    val bgBrush = animatedMonotoneBackplate()

    CompositionLocalProvider(LocalTextStyle provides baseCompact) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .offset(y = (-12).dp)
                        .neutralEdge(alpha = 0.14f, corner = 16.dp, stroke = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgBrush)
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onBack,
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = Color(0xFF263238),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                        ) {
                            Text("Back")
                        }

                        Spacer(Modifier.width(16.dp))

                        OutlinedButton(
                            onClick = onNext,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF111111)
                            ),
                            border = BorderStroke(1.dp, Color(0xFF111111)),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                        ) {
                            Text("Next")
                        }
                    }
                }
            }
        ) { pad ->
            LazyColumn(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .background(bgBrush)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Text("Review", style = titleTight, color = MaterialTheme.colorScheme.onSurface)
                }

                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("All Original Questions and Answers", style = titleTight)
                            Spacer(Modifier.height(6.dp))

                            if (qaEntries.isEmpty()) {
                                Text("No records yet.", style = bodyTight)
                            } else {
                                qaEntries.forEachIndexed { idx, (nodeId, question, answer) ->
                                    if (idx > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))

                                    Column {
                                        Text(nodeId, style = labelTight, color = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.height(2.dp))

                                        val qText = if (question.isBlank()) "– No Question." else "Q: $question"
                                        Text(qText, style = bodyTight)
                                        Spacer(Modifier.height(2.dp))

                                        val aText = if (answer.isBlank()) "– No Answer." else answer
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
                                        list.forEachIndexed { i, entry ->
                                            Spacer(Modifier.height(4.dp))
                                            Text("${i + 1}. Q: ${entry.question}", style = bodyTight)
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
            }
        }
    }
}
