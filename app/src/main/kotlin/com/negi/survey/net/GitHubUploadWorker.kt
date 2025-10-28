package com.negi.survey.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
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
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Uploads a single JSON file from /files/pending_uploads to GitHub (Contents API).
 *
 * Key behaviors:
 * - Runs in foreground with a progress notification (Android 14/15 requires a foreground service type).
 * - Enforced to run only when network is connected; survives process death.
 * - Deletes the local pending file on success.
 * - Exposes progress via setProgressAsync() and result via output Data.
 *
 * Output keys: fileName / remotePath / commitSha / fileUrl
 */
class GitHubUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @RequiresApi(Build.VERSION_CODES.Q)
    override suspend fun doWork(): Result {
        // --- Read & validate inputs (fail fast with clear semantics) ---
        val cfg = GitHubUploader.GitHubConfig(
            owner = inputData.getString(KEY_OWNER).orEmpty(),
            repo = inputData.getString(KEY_REPO).orEmpty(),
            token = inputData.getString(KEY_TOKEN).orEmpty(),
            branch = inputData.getString(KEY_BRANCH).orEmpty(),
            pathPrefix = inputData.getString(KEY_PATH_PREFIX).orEmpty()
        )
        val filePath = inputData.getString(KEY_FILE_PATH).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: File(filePath).name

        if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            // Misconfiguration → no point retrying
            return Result.failure(workDataOf(ERROR_MESSAGE to "Invalid GitHub config (owner/repo/token)"))
        }
        if (filePath.isBlank()) {
            return Result.failure(workDataOf(ERROR_MESSAGE to "Input file path is blank"))
        }
        if (fileName.isBlank()) {
            return Result.failure(workDataOf(ERROR_MESSAGE to "File name is blank"))
        }

        val pendingFile = File(filePath)
        if (!pendingFile.exists()) {
            // File already processed or missing → treat as failure but do not retry
            return Result.failure(workDataOf(ERROR_MESSAGE to "Pending file not found: $filePath"))
        }

        // Build the remote path under the configured prefix
        val remotePath = buildRemotePath(cfg.pathPrefix, fileName)

        // Ensure the notification channel exists before entering foreground
        ensureChannel()

        // Use a stable, per-file notification id so parallel uploads do not collide
        val notifId = NOTIF_BASE + (abs(fileName.hashCode()) % 8000)

        // Enter foreground early to satisfy Android 14+ restrictions
        setForegroundAsync(foregroundInfo(notifId, pct = 0, title = "Uploading $fileName"))

        // Read the file as UTF-8; for large payloads consider streaming if needed
        val jsonContent = runCatching { pendingFile.readText(Charsets.UTF_8) }
            .getOrElse { e ->
                return Result.failure(workDataOf(ERROR_MESSAGE to "Read failed: ${e.message}"))
            }

        var lastPct = 0

        return try {
            // Perform the upload while reporting progress to both WorkManager and Notification
            val result = GitHubUploader.uploadJson(
                owner = cfg.owner,
                repo = cfg.repo,
                branch = cfg.branch,
                path = remotePath,
                token = cfg.token,
                content = jsonContent
            ) { pctLong, _ ->
                lastPct = pctLong.toInt().coerceIn(0, 100)
                setProgressAsync(workDataOf(PROGRESS_PCT to lastPct, PROGRESS_FILE to fileName))
                setForegroundAsync(foregroundInfo(notifId, pct = lastPct, title = "Uploading $fileName"))
            }

            // Finalize UI as complete
            setProgressAsync(workDataOf(PROGRESS_PCT to 100, PROGRESS_FILE to fileName))
            setForegroundAsync(foregroundInfo(notifId, pct = 100, title = "Uploaded $fileName", finished = true))

            // Best-effort cleanup of the local file
            runCatching { pendingFile.delete() }

            // Provide structured outputs to callers
            val out = workDataOf(
                OUT_FILE_NAME to fileName,
                OUT_REMOTE_PATH to remotePath,
                OUT_COMMIT_SHA to (result.commitSha ?: ""),
                OUT_FILE_URL to (result.fileUrl ?: "")
            )
            Result.success(out)

        } catch (t: Throwable) {
            // Keep the file for retries; reflect the last observed progress in the notification
            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = max(0, lastPct),
                    title = "Upload failed: $fileName",
                    error = true
                )
            )
            val failureData = workDataOf(ERROR_MESSAGE to (t.message ?: "unknown"))
            // Delegate retry policy to WorkManager's backoff criteria; request retry if budget remains
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(failureData)
        }
    }

    /** Builds "prefix/fileName" while avoiding duplicate slashes. */
    private fun buildRemotePath(prefix: String, fileName: String): String =
        listOf(prefix.trim('/'), fileName.trim('/'))
            .filter { it.isNotEmpty() }
            .joinToString("/")

    /**
     * Creates a ForegroundInfo with a progress notification.
     * - Ongoing while in progress; auto-stops on success/failure.
     * - Android 14/15: declares DATA_SYNC foreground service type.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun foregroundInfo(
        notificationId: Int,
        pct: Int,
        title: String,
        finished: Boolean = false,
        error: Boolean = false
    ): ForegroundInfo {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished && !error)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        // Determinate progress when 0..100; indeterminate only when pct < 0 (not used here)
        if (finished || error) {
            builder.setProgress(0, 0, false)
        } else {
            builder.setProgress(100, pct.coerceIn(0, 100), false)
        }

        val notif = builder.build()

        // Declare the foreground service type for Android 14+ compliance
        return ForegroundInfo(
            notificationId,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /** Ensures the notification channel exists on Android O+. Id must match the builder. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Uploads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "GitHub upload progress"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val TAG = "github_upload"

        private const val CHANNEL_ID = "uploads"
        private const val NOTIF_BASE = 3200
        private const val MAX_ATTEMPTS = 5

        // progress keys
        const val PROGRESS_PCT = "pct"      // Int 0..100
        const val PROGRESS_FILE = "file"    // String: filename

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

        // failure key
        const val ERROR_MESSAGE = "error"

        /**
         * Enqueue a work to upload an already existing file on disk.
         * - Unique per filename to avoid duplicate uploads.
         * - Requires network; uses expedited if quota permits, otherwise falls back.
         * - Applies exponential backoff for robust retries.
         */
        fun enqueueExistingPayload(context: Context, cfg: GitHubUploader.GitHubConfig, file: File) {
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
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, // initial delay (seconds)
                    TimeUnit.SECONDS
                )
                .addTag(TAG)
                .addTag("$TAG:file:$fileName")
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("upload_$fileName", ExistingWorkPolicy.KEEP, req)
        }

        /**
         * Enqueue a work by writing the JSON content into /files/pending_uploads first.
         * Filenames are sanitized and uniquified to avoid clobbering existing payloads.
         */
        fun enqueue(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
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

        /** Replace non [A-Za-z0-9_.-] characters with underscores to make a filesystem-safe filename. */
        private fun sanitizeName(name: String): String =
            name.replace(Regex("""[^\w\-.]"""), "_")

        /** If the target exists, append _1, _2, ... until a free name is found. */
        private fun uniqueIfExists(file: File): File {
            if (!file.exists()) return file
            val base = file.nameWithoutExtension
            val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
            var idx = 1
            while (true) {
                val candidate = File(file.parentFile, "${base}_$idx$ext")
                if (!candidate.exists()) return candidate
                idx++
            }
        }
    }
}
