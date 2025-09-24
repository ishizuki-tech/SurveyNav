// file: com/negi/surveynav/screens/DoneScreen.kt
package com.negi.surveynav.screens

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.negi.surveynav.net.GitHubConfig
import com.negi.surveynav.net.GitHubUploadWorker
import com.negi.surveynav.net.GitHubUploader
import com.negi.surveynav.vm.SurveyViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Done screen:
 * - Lists answers and follow-ups
 * - Copy / Upload to GitHub
 * - Optional auto-save to device (no picker)
 * - Schedule upload when online (WorkManager)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoneScreen(
    vm: SurveyViewModel,
    onRestart: () -> Unit,
    gitHubConfig: GitHubConfig? = null,   // pass to enable upload/schedule buttons
    autoSaveToDevice: Boolean = false     // set true to auto-save once on first show
) {
    val questions by vm.questions.collectAsState(initial = emptyMap())
    val answers by vm.answers.collectAsState(initial = emptyMap())
    val followups by vm.followups.collectAsState(initial = emptyMap())

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uploading = remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Build JSON export text (answers + followups)
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

    // Run once on first composition to auto-save (no picker)
    val autoSavedOnce = remember { mutableStateOf(false) }
    LaunchedEffect(autoSaveToDevice, jsonText) {
        if (autoSaveToDevice && !autoSavedOnce.value) {
            val fileName = "survey_${System.currentTimeMillis()}.json"
            runCatching {
                val result = saveJsonAutomatically(context = context, fileName = fileName, content = jsonText)
                autoSavedOnce.value = true
                snackbar.showOnce("Saved to device: ${result.location}")
            }.onFailure { e ->
                snackbar.showOnce("Auto-save failed: ${e.message}")
            }
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
            Text("Thanks! Here is your response summary.", style = MaterialTheme.typography.bodyLarge)
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
                        Text("A: ${if (a.isBlank()) "(empty)" else a}", style = MaterialTheme.typography.bodyLarge)
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
                // Immediate upload (requires network now)
                if (gitHubConfig != null) {
                    Button(
                        onClick = {
                            if (uploading.value) return@Button
                            scope.launch {
                                uploading.value = true
                                try {
                                    val fileName = "survey_${System.currentTimeMillis()}.json"
                                    val path = "${gitHubConfig.pathPrefix.trimEnd('/')}/$fileName"
                                    val result = GitHubUploader.uploadJson(
                                        owner = gitHubConfig.owner,
                                        repo = gitHubConfig.repo,
                                        branch = gitHubConfig.branch,
                                        path = path,
                                        token = gitHubConfig.token,
                                        content = jsonText,
                                        message = "Upload $fileName"
                                    )
                                    snackbar.showOnce("Uploaded: ${result.fileUrl ?: result.commitSha}")
                                } catch (e: Exception) {
                                    snackbar.showOnce("Upload failed: ${e.message}")
                                } finally {
                                    uploading.value = false
                                }
                            }
                        },
                        enabled = !uploading.value
                    ) { Text(if (uploading.value) "Uploading..." else "Upload now") }
                }

                // Schedule upload (runs when online; survives reboot)
                if (gitHubConfig != null) {
                    Button(
                        onClick = {
                            val fileName = "survey_${System.currentTimeMillis()}.json"
                            GitHubUploadWorker.enqueue(
                                context = context,
                                cfg = gitHubConfig,
                                fileName = fileName,
                                jsonContent = jsonText
                            )
                            scope.launch { snackbar.showOnce("Upload scheduled (will run when online).") }
                        }
                    ) { Text("Upload when Online") }
                }

                Spacer(Modifier.weight(1f))
                Button(onClick = onRestart) { Text("Restart") }
            }

            Spacer(Modifier.height(12.dp))
            LaunchedEffect(Unit) { snackbar.showOnce("Thank you for your responses") }
        }
    }
}

/* ============================================================
 * Auto-save helpers (no user interaction)
 * ============================================================ */

private data class SaveResult(val uri: Uri?, val file: File?, val location: String)

/**
 * Saves JSON automatically:
 * - API 29+ -> MediaStore Downloads/SurveyNav (visible in Files app)
 * - API 28- -> app-specific external Downloads/SurveyNav (no permission)
 */
private fun saveJsonAutomatically(
    context: android.content.Context,
    fileName: String,
    content: String
): SaveResult {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveToDownloadsQPlus(context, fileName, content)
    } else {
        saveToAppExternalPreQ(context, fileName, content)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun saveToDownloadsQPlus(
    context: android.content.Context,
    fileName: String,
    content: String
): SaveResult {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "application/json")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SurveyNav")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Failed to create download entry")
    try {
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Failed to open output stream")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return SaveResult(uri = uri, file = null, location = "Downloads/SurveyNav/$fileName")
    } catch (t: Throwable) {
        resolver.delete(uri, null, null)
        throw t
    }
}

private fun saveToAppExternalPreQ(
    context: android.content.Context,
    fileName: String,
    content: String
): SaveResult {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: context.filesDir
    val dir = File(base, "SurveyNav").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeText(content, Charsets.UTF_8)
    return SaveResult(uri = null, file = file, location = file.absolutePath)
}

/* ============================================================
 * Snackbar + JSON utils
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
