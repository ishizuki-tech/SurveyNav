package com.negi.survey.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.negi.survey.R
import java.io.File
import kotlin.math.abs

/**
 * Uploads one JSON file in /files/pending_uploads to GitHub (Contents API).
 * - Foreground notification with progress (Android 14/15 requires FGS type)
 * - Runs only when online, survives reboot
 * - Deletes local pending file on success
 * - Outputs: fileName / remotePath / commitSha / fileUrl
 */
class GitHubUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val cfg = GitHubConfig(
            owner = inputData.getString(KEY_OWNER).orEmpty(),
            repo = inputData.getString(KEY_REPO).orEmpty(),
            token = inputData.getString(KEY_TOKEN).orEmpty(),
            branch = inputData.getString(KEY_BRANCH).orEmpty(),
            pathPrefix = inputData.getString(KEY_PATH_PREFIX).orEmpty()
        )
        val filePath = inputData.getString(KEY_FILE_PATH).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: File(filePath).name
        if (
            cfg.token.isBlank() ||
            cfg.owner.isBlank() ||
            cfg.repo.isBlank() ||
            filePath.isBlank() ||
            fileName.isBlank()
        ) {
            return Result.failure()
        }

        val pendingFile = File(filePath)
        if (!pendingFile.exists()) return Result.failure()

        val remotePath = buildRemotePath(cfg.pathPrefix, fileName)

        ensureChannel()

        // Unique notification id per file so parallel uploads don't fight
        val notifId = NOTIF_BASE + (abs(fileName.hashCode()) % 8000)
        setForegroundAsync(foregroundInfo(notifId, 0, "Uploading $fileName"))

        val json = pendingFile.readText()

        var lastPct = 0
        return try {
            val result = GitHubUploader.uploadJson(
                owner = cfg.owner,
                repo = cfg.repo,
                branch = cfg.branch,
                path = remotePath,
                token = cfg.token,
                content = json
            ) { pctLong, _ ->
                lastPct = pctLong.toInt().coerceIn(0, 100)
                setProgressAsync(
                    workDataOf(
                        PROGRESS_PCT to lastPct,
                        PROGRESS_FILE to fileName
                    )
                )
                setForegroundAsync(foregroundInfo(notifId, lastPct, "Uploading $fileName"))
            }

            // Mark 100% and finish nicely
            setProgressAsync(workDataOf(PROGRESS_PCT to 100, PROGRESS_FILE to fileName))
            setForegroundAsync(foregroundInfo(notifId, 100, "Uploaded $fileName", finished = true))

            // Delete local pending file
            runCatching { pendingFile.delete() }

            val out = workDataOf(
                OUT_FILE_NAME to fileName,
                OUT_REMOTE_PATH to remotePath,
                OUT_COMMIT_SHA to (result.commitSha ?: ""),
                OUT_FILE_URL to (result.fileUrl ?: "")
            )
            Result.success(out)

        } catch (t: Throwable) {
            // Keep file for retry; just update notification
            setForegroundAsync(foregroundInfo(notifId, lastPct, "Upload failed: $fileName", error = true))
            val failureData = workDataOf("error" to (t.message ?: "unknown"))
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(failureData)
        }
    }

    private fun buildRemotePath(prefix: String, fileName: String): String {
        val segments = listOf(prefix.trim('/'), fileName.trim('/'))
            .filter { it.isNotEmpty() }
        return segments.joinToString(separator = "/")
    }

    private fun foregroundInfo(
        notificationId: Int,
        pct: Int,
        title: String,
        finished: Boolean = false,
        error: Boolean = false
    ): ForegroundInfo {
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished && !error)
            .apply {
                if (finished || error) setProgress(0, 0, false)
                else setProgress(100, pct.coerceIn(0, 100), pct < 0)
            }
            .build()

        // ★ Android 14/15 対策：FGS の種別を dataSync に明示
        return ForegroundInfo(
            notificationId,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Background Uploads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "GitHub upload progress" }
            )
        }
    }

    companion object {
        const val TAG = "github_upload"

        private const val CHANNEL_ID = "uploads"
        private const val NOTIF_BASE = 3200
        private const val MAX_ATTEMPTS = 5

        // progress keys
        const val PROGRESS_PCT = "pct"      // Int 0..100
        const val PROGRESS_FILE = "file"    // String filename

        // input keys
        const val KEY_OWNER = "owner"
        const val KEY_REPO = "repo"
        const val KEY_TOKEN = "token"
        const val KEY_BRANCH = "branch"
        const val KEY_PATH_PREFIX = "pathPrefix"
        const val KEY_FILE_PATH = "filePath"
        const val KEY_FILE_NAME = "fileName"

        // output keys
        const val OUT_FILE_NAME = "out.fileName"
        const val OUT_REMOTE_PATH = "out.remotePath"
        const val OUT_COMMIT_SHA = "out.commitSha"
        const val OUT_FILE_URL = "out.fileUrl"

        fun enqueueExistingPayload(context: Context, cfg: GitHubConfig, file: File) {
            val fileName = file.name
            val req = OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_OWNER to cfg.owner,
                        KEY_REPO to cfg.repo,
                        KEY_TOKEN to cfg.token,
                        KEY_BRANCH to cfg.branch,
                        KEY_PATH_PREFIX to cfg.pathPrefix,
                        KEY_FILE_PATH to file.absolutePath,
                        KEY_FILE_NAME to fileName
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(TAG)
                .addTag("$TAG:file:$fileName")
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("upload_$fileName", ExistingWorkPolicy.KEEP, req)
        }

        fun enqueue(
            context: Context,
            cfg: GitHubConfig,
            fileName: String,
            jsonContent: String
        ) {
            require(fileName.isNotBlank()) { "fileName is blank" }

            val safeName = sanitizeName(fileName).let { if (it.endsWith(".json", true)) it else "$it.json" }
            val dir = File(context.filesDir, "pending_uploads").apply { mkdirs() }
            val target = uniqueIfExists(File(dir, safeName))

            target.writeText(jsonContent, Charsets.UTF_8)
            enqueueExistingPayload(context, cfg, target)
        }

        private fun sanitizeName(name: String): String =
            name.replace(Regex("""[^\w\-.]"""), "_")

        private fun uniqueIfExists(file: File): File {
            if (!file.exists()) return file
            val base = file.nameWithoutExtension
            val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
            var idx = 1
            while (true) {
                val cand = File(file.parentFile, "${base}_$idx$ext")
                if (!cand.exists()) return cand
                idx++
            }
        }
    }
}
