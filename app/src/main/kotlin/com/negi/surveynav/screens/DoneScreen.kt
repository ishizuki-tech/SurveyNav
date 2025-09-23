// file: com/negi/surveynav/screens/DoneScreen.kt
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.negi.surveynav.vm.SurveyViewModel
import kotlinx.coroutines.launch

/**
 * Done screen (English version).
 * - Lists all questions and answers in insertion order
 * - Also shows follow-up Q&A per node
 * - Provides a "Copy JSON" action to export results
 * - "Restart" delegates to the parent via onRestart
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoneScreen(
    vm: SurveyViewModel,
    onRestart: () -> Unit
) {
    // ViewModel state (use safe initials to avoid compile/runtime issues)
    val questions by vm.questions.collectAsState(initial = emptyMap())
    val answers   by vm.answers.collectAsState(initial = emptyMap())
    val followups by vm.followups.collectAsState(initial = emptyMap())

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // Build JSON export text (ordered by questions map)
    val jsonText = remember(questions, answers, followups) {
        buildString {
            append("{\n")
            append("  \"answers\": {\n")
            questions.entries.forEachIndexed { idx, (id, q) ->
                val a = answers[id]?.replace("\n", "\\n") ?: ""
                append("    \"").append(escapeJson(id)).append("\": {\n")
                append("      \"question\": \"").append(escapeJson(q)).append("\",\n")
                append("      \"answer\": \"").append(escapeJson(a)).append("\"\n")
                append("    }")
                if (idx != questions.size - 1) append(",")
                append("\n")
            }
            append("  },\n")

            append("  \"followups\": {\n")
            followups.entries.forEachIndexed { i, (ownerId, list) ->
                append("    \"").append(escapeJson(ownerId)).append("\": [\n")
                list.forEachIndexed { j, fu ->
                    val q = fu.question.replace("\n", "\\n")
                    val a = (fu.answer ?: "").replace("\n", "\\n")
                    append("      { \"question\": \"").append(escapeJson(q))
                        .append("\", \"answer\": \"").append(escapeJson(a)).append("\" }")
                    if (j != list.lastIndex) append(",")
                    append("\n")
                }
                append("    ]")
                if (i != followups.size - 1) append(",")
                append("\n")
            }
            append("  }\n")
            append("}\n")
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Done") }) },
        snackbarHost = { SnackbarHost(hostState = snackbar) }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Thanks! Here is your response summary.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(16.dp))

            // Answers
            Text("■ Answers", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (questions.isEmpty()) {
                Text("No answers yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                questions.forEach { (id, q) ->
                    val a = answers[id].orEmpty()
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("Q: $q", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "A: ${if (a.isBlank()) "(empty)" else a}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Divider()
                }
            }

            Spacer(Modifier.height(20.dp))

            // Follow-ups
            Text("■ Follow-ups", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (followups.isEmpty()) {
                Text("No follow-ups.", style = MaterialTheme.typography.bodyMedium)
            } else {
                followups.forEach { (ownerId, list) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("Owner node: $ownerId", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        list.forEachIndexed { idx, fu ->
                            Text("${idx + 1}. ${fu.question}", style = MaterialTheme.typography.bodyMedium)
                            val ans = fu.answer
                            if (!ans.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text("   ↳ $ans", style = MaterialTheme.typography.bodyLarge)
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    Divider()
                }
            }

            Spacer(Modifier.height(24.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(jsonText))
                        scope.launch { snackbar.showOnce("Copied JSON to clipboard") }
                    }
                ) { Text("Copy JSON") }

                Button(onClick = onRestart) { Text("Restart") }
            }

            Spacer(Modifier.height(12.dp))

            LaunchedEffect(Unit) {
                snackbar.showOnce("Thank you for your responses")
            }
        }
    }
}

/* ============================================================
 * Small helpers
 * ============================================================ */

private suspend fun SnackbarHostState.showOnce(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}

private fun escapeJson(s: String): String =
    buildString(s.length + 8) {
        s.forEach { ch ->
            when (ch) {
                '\"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
