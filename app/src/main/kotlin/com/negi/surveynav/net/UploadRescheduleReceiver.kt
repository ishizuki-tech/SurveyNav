// file: com/negi/surveynav/boot/UploadRescheduleReceiver.kt
package com.negi.surveynav.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.negi.surveynav.BuildConfig
import java.io.File

class UploadRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // BuildConfig から設定（既にあなたのプロジェクトにあります）
        val cfg = GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = BuildConfig.GH_REPO,
            token = BuildConfig.GH_TOKEN,
            branch = BuildConfig.GH_BRANCH,
            pathPrefix = BuildConfig.GH_PATH_PREFIX
        )
        if (cfg.token.isBlank()) return // nothing to do

        // Scan pending payloads and enqueue them (KEEP avoids duplicates)
        val dir = File(context.filesDir, "pending_uploads")
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        files.forEach { f ->
            GitHubUploadWorker.enqueueExistingPayload(context, cfg, f)
        }
    }
}
