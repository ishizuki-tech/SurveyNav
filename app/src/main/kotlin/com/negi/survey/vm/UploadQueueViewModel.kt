package com.negi.survey.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.negi.survey.net.GitHubUploadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * UI representation of a file upload task.
 */
data class UploadItemUi(
    val id: String,
    val fileName: String,
    val percent: Int?,        // null = unknown
    val state: WorkInfo.State,
    val fileUrl: String?,     // populated when upload succeeds
    val message: String? = null
)

/**
 * ViewModel that observes file uploads via WorkManager.
 * Transforms WorkInfo states into UI-friendly data stream.
 */
class UploadQueueViewModel(app: Application) : AndroidViewModel(app) {

    private val wm = WorkManager.getInstance(app)

    /**
     * A flow that emits the list of current uploads, sorted by priority.
     * Uses LiveData -> Flow bridge for best reliability with WorkManager.
     */
    val itemsFlow: Flow<List<UploadItemUi>> =
        wm.getWorkInfosByTagLiveData(GitHubUploadWorker.TAG)
            .asFlow()
            .map { list ->
                list.map { wi ->
                    val pct: Int? = wi.progress
                        .getInt(GitHubUploadWorker.PROGRESS_PCT, -1)
                        .takeIf { it >= 0 }

                    val name: String =
                        wi.progress.getString(GitHubUploadWorker.PROGRESS_FILE)
                            ?: wi.outputData.getString(GitHubUploadWorker.OUT_FILE_NAME)
                            ?: wi.tags.firstOrNull {
                                it.startsWith("${GitHubUploadWorker.TAG}:file:")
                            }?.substringAfter(":file:")
                            ?: "upload.json"

                    val url: String? =
                        wi.outputData.getString(GitHubUploadWorker.OUT_FILE_URL)
                            ?.takeIf { it.isNotBlank() }

                    UploadItemUi(
                        id = wi.id.toString(),
                        fileName = name,
                        percent = pct,
                        state = wi.state,
                        fileUrl = url,
                        message = when (wi.state) {
                            WorkInfo.State.ENQUEUED  -> "Waiting for network…"
                            WorkInfo.State.RUNNING   -> "Uploading…"
                            WorkInfo.State.SUCCEEDED -> "Uploaded"
                            WorkInfo.State.FAILED    -> "Failed"
                            WorkInfo.State.BLOCKED   -> "Blocked"
                            WorkInfo.State.CANCELLED -> "Cancelled"
                        }
                    )
                }.sortedWith(
                    compareBy<UploadItemUi> {
                        when (it.state) {
                            WorkInfo.State.RUNNING -> 0
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> 1
                            WorkInfo.State.SUCCEEDED -> 2
                            else -> 3
                        }
                    }.thenBy { it.fileName }
                )
            }
            /**
             * Avoid unnecessary UI redraws unless items actually change.
             */
            .distinctUntilChanged { old, new ->
                if (old.size != new.size) return@distinctUntilChanged false
                old.zip(new).all { (a, b) ->
                    a.id == b.id &&
                            a.state == b.state &&
                            (a.percent ?: -1) == (b.percent ?: -1) &&
                            a.fileUrl == b.fileUrl
                }
            }

    companion object {
        /**
         * Factory method for ViewModel instantiation in Compose.
         */
        fun factory(app: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                UploadQueueViewModel(app) as T
        }
    }
}