package com.negi.survey.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.negi.survey.BuildConfig

import java.io.File

/**
 * Reschedules pending GitHub uploads after device reboot or app update.
 *
 * Triggers on:
 * - BOOT_COMPLETED (after user unlock unless DIRECT BOOT aware)
 * - LOCKED_BOOT_COMPLETED (API 24+; when direct boot aware)
 * - MY_PACKAGE_REPLACED (app updated/reinstalled)
 *
 * It scans /files/pending_uploads and enqueues each file with WorkManager.
 * Worker handles dedupe via unique work + KEEP policy.
 */
class UploadRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // Accept only the explicit actions we support
        val handled = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> true
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            // Support direct-boot delivery when declared as directBootAware in the manifest (N+)
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> true
            else -> false
        }
        if (!handled) return

        // Build GitHub configuration from BuildConfig (project-provided)
        val cfg = GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = BuildConfig.GH_REPO,
            token = BuildConfig.GH_TOKEN,
            branch = BuildConfig.GH_BRANCH,
            pathPrefix = BuildConfig.GH_PATH_PREFIX
        )

        // Minimal sanity check; if token is blank we skip scheduling gracefully
        if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) return

        // Scan app-internal directory for pending payloads.
        // Note: listFiles() can be null if dir doesn't exist.
        val dir = File(context.filesDir, PENDING_DIR)
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) return

        // Enqueue each file separately; worker uses unique work per filename to avoid duplicates.
        for (file in files) {
            runCatching {
                GitHubUploadWorker.enqueueExistingPayload(context, cfg, file)
            }.onFailure {
                // Swallow exceptions per-file to avoid breaking the whole reschedule pass.
                // Consider logging here if you have a logger (e.g., Timber).
            }
        }
    }

    private companion object {
        const val PENDING_DIR = "pending_uploads"
    }
}
