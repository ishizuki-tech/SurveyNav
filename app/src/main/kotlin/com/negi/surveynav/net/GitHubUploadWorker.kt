package com.negi.surveynav.net

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class GitHubUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val owner      = inputData.getString(KEY_OWNER) ?: return@withContext Result.failure()
        val repo       = inputData.getString(KEY_REPO) ?: return@withContext Result.failure()
        val branch     = inputData.getString(KEY_BRANCH) ?: "main"
        val token      = inputData.getString(KEY_TOKEN) ?: return@withContext Result.failure()
        val pathPrefix = inputData.getString(KEY_PREFIX) ?: "exports"
        val fileName   = inputData.getString(KEY_FILE_NAME) ?: "survey_${System.currentTimeMillis()}.json"
        val filePath   = inputData.getString(KEY_FILE_PATH) ?: return@withContext Result.failure()

        val file = File(filePath)
        if (!file.exists()) return@withContext Result.failure(workDataOf("error" to "payload file missing"))

        return@withContext try {
            val content = file.readText()
            val path = "${pathPrefix.trimEnd('/')}/$fileName"
            val result = GitHubUploader.uploadJson(
                owner = owner, repo = repo, branch = branch, path = path, token = token,
                content = content, message = "Upload $fileName"
            )
            file.delete() // cleanup on success
            Result.success(workDataOf("fileUrl" to (result.fileUrl ?: ""), "commitSha" to (result.commitSha ?: "")))
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: "unknown")))
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_BRANCH = "branch"
        private const val KEY_TOKEN = "token"
        private const val KEY_PREFIX = "prefix"
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_FILE_PATH = "filePath"

        /** Use this when you have JSON text in memory (saves then enqueues). */
        fun enqueue(context: Context, cfg: GitHubConfig, fileName: String, jsonContent: String): Operation {
            val filePath = persistPayload(context, fileName, jsonContent)
            return enqueueExistingPayload(context, cfg, File(filePath), fileName)
        }

        /** Use this when a payload file already exists on disk. */
        fun enqueueExistingPayload(context: Context, cfg: GitHubConfig, file: File, fileName: String = file.name): Operation {
            val input = workDataOf(
                KEY_OWNER to cfg.owner,
                KEY_REPO to cfg.repo,
                KEY_BRANCH to cfg.branch,
                KEY_TOKEN to cfg.token,
                KEY_PREFIX to cfg.pathPrefix,
                KEY_FILE_NAME to fileName,
                KEY_FILE_PATH to file.absolutePath
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                .setConstraints(constraints)
                .setInputData(input)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("github-upload")
                .addTag(file.name) // identify per file
                .build()

            // Unique per file to avoid duplicates; KEEP = don't enqueue if already persisted by WM
            val uniqueName = "github-upload-${file.name}"
            return WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
        }

        internal fun persistPayload(context: Context, fileName: String, content: String): String {
            val dir = File(context.filesDir, "pending_uploads").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(content, Charsets.UTF_8)
            return file.absolutePath
        }
    }
}
