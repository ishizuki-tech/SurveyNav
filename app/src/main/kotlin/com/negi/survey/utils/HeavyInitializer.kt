package com.negi.survey.utils

import android.content.Context
import android.util.Log
import com.negi.survey.net.HttpUrlFileDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.Job

/**
 * One-shot, single-flight initializer for heavy assets (e.g., model download).
 * - Runs in the CALLER's coroutine (no internal scope), so caller cancellation propagates.
 * - Writes to a temp file first, then atomically replaces the final file.
 * - `forceFresh` disables resume and rebuilds file from scratch.
 */
object HeavyInitializer {

    private const val TAG = "HeavyInitializer"
    private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L // 64 MiB

    private val inFlight = AtomicReference<CompletableDeferred<Result<File>>?>(null)
    @Volatile private var runningJob: Job? = null

    fun isAlreadyComplete(
        context: Context,
        modelUrl: String,
        hfToken: String?,
        fileName: String
    ): Boolean {
        val dst = File(context.filesDir, fileName)
        if (!dst.exists() || dst.length() <= 0L) return false
        val remoteLen = runCatching { headContentLengthForVerify(modelUrl, hfToken) }.getOrNull()
        return remoteLen != null && remoteLen == dst.length()
    }

    // Minimal HEAD probe for strict length equality check
    private fun headContentLengthForVerify(srcUrl: String, hfToken: String?): Long? {
        var current = srcUrl
        repeat(10) {
            val u = URL(current)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "SurveyNav/1.0 (Android)")
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                if ((u.host == "huggingface.co" || u.host.endsWith(".huggingface.co")) && !hfToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(u, loc).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val len = conn.getHeaderFieldLong("Content-Length", -1L)
                return len.takeIf { it >= 0L }
            } finally { conn.disconnect() }
        }
        return null
    }

    /**
     * Start or join the single-flight initialization in the CALLER's coroutine.
     *
     * @param forceFresh If true, ignore any existing file and write new content into a temp file,
     *                   then atomically replace the final file on success.
     */
    suspend fun ensureInitialized(
        context: Context,
        modelUrl: String,
        hfToken: String?,
        fileName: String,
        timeoutMs: Long,
        forceFresh: Boolean,
        onProgress: (downloaded: Long, total: Long?) -> Unit
    ): Result<File> {
        inFlight.get()?.let { return it.await() }
        val deferred = CompletableDeferred<Result<File>>()
        if (!inFlight.compareAndSet(null, deferred)) {
            return inFlight.get()!!.await()
        }

        val callerJob = currentCoroutineContext()[Job]
        runningJob = callerJob
        val token = hfToken?.takeIf { it.isNotBlank() }

        try {
            val dir = context.filesDir
            val finalFile = File(dir, fileName)
            val tmpFile = File(dir, "$fileName.tmp")

            if (forceFresh) {
                runCatching { if (tmpFile.exists()) tmpFile.delete() }
                runCatching { if (finalFile.exists()) finalFile.delete() }
            }

            val downloader = HttpUrlFileDownloader(hfToken = token, debugLogs = true)
            val remoteLen = runCatching { headContentLength(modelUrl, token) }.getOrNull()

            if (!forceFresh && remoteLen != null && finalFile.exists() && finalFile.length() == remoteLen) {
                onProgress(finalFile.length(), finalFile.length())
                deferred.complete(Result.success(finalFile))
                return deferred.await()
            }

            if (remoteLen != null) {
                val existing = if (!forceFresh && finalFile.exists()) finalFile.length() else 0L
                val needed = max(0L, remoteLen - existing) + FREE_SPACE_MARGIN_BYTES
                val free = dir.usableSpace
                if (free < needed) {
                    deferred.complete(Result.failure(IOException("Not enough free space")))
                    return deferred.await()
                }
            }

            withTimeout(timeoutMs) {
                val progressBridge: (Long, Long?) -> Unit = { cur, total ->
                    if (callerJob?.isActive != true) throw CancellationException("canceled by caller")
                    onProgress(cur, total)
                }

                // Always download to a CLEAN temp file first
                runCatching { if (tmpFile.exists()) tmpFile.delete() }
                tmpFile.parentFile?.mkdirs()
                tmpFile.createNewFile()

                try {
                    downloader.downloadToFile(
                        url = modelUrl,
                        dst = tmpFile,
                        onProgress = progressBridge
                    )
                } catch (e: IOException) {
                    val msg = (e.message ?: "").lowercase()
                    val rangeProblem =
                        "416" in msg || "range" in msg || "content-range" in msg || "requested range" in msg
                    // Only allow one retry on non-force mode (resume hiccup). For force, we don't resume.
                    if (!forceFresh && rangeProblem && callerJob?.isActive == true) {
                        runCatching { if (tmpFile.exists()) tmpFile.delete() }
                        tmpFile.createNewFile()
                        downloader.downloadToFile(
                            url = modelUrl,
                            dst = tmpFile,
                            onProgress = progressBridge
                        )
                    } else {
                        throw e
                    }
                }
            }

            // Atomically replace final <- tmp (same dir)
            val replaced = replaceFinal(tmpFile, finalFile)
            if (!replaced) {
                runCatching { if (finalFile.exists()) finalFile.delete() }
                if (!tmpFile.renameTo(finalFile)) {
                    throw IOException("Failed to move temp file into place")
                }
            }

            deferred.complete(Result.success(finalFile))
        } catch (ce: CancellationException) {
            runCatching { File(context.filesDir, "$fileName.tmp").takeIf { it.exists() }?.delete() }
            deferred.complete(Result.failure(IOException("Canceled")))
        } catch (te: TimeoutCancellationException) {
            deferred.complete(Result.failure(IOException("Timeout ($timeoutMs ms)")))
        } catch (t: Throwable) {
            val msg = userFriendlyMessage(t)
            Log.w(TAG, "Initialization error: $msg", t)
            runCatching { File(context.filesDir, "$fileName.tmp").takeIf { it.exists() }?.delete() }
            deferred.complete(Result.failure(IOException(msg, t)))
        } finally {
            if (inFlight.get() === deferred && deferred.isCompleted) inFlight.set(null)
            runningJob = null
        }

        return deferred.await()
    }

    suspend fun cancel() {
        val job = runningJob ?: return
        runCatching { job.cancel(CancellationException("canceled by user")) }
        Log.w(TAG, "Initialization canceled.")
    }

    fun resetForDebug() {
        inFlight.getAndSet(null)?.cancel(CancellationException("resetForDebug"))
        runningJob?.cancel(CancellationException("resetForDebug"))
        runningJob = null
        Log.w(TAG, "resetForDebug(): cleared in-flight state")
    }

    // ---- internals ----
    private fun headContentLength(srcUrl: String, hfToken: String?): Long? {
        var current = srcUrl
        repeat(10) {
            val u = URL(current)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "SurveyNav/1.0 (Android)")
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                if ((u.host == "huggingface.co" || u.host.endsWith(".huggingface.co")) && !hfToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(u, loc).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val len = conn.getHeaderFieldLong("Content-Length", -1L)
                return len.takeIf { it >= 0L }
            } finally { conn.disconnect() }
        }
        return null
    }

    private fun userFriendlyMessage(t: Throwable): String {
        val raw = t.message ?: t::class.java.simpleName
        val s = raw.lowercase()
        return when {
            "unauthorized" in s || "401" in s -> "Authorization failed (HF token?)"
            "forbidden" in s || "403" in s -> "Access denied (token/permissions?)"
            "timeout" in s -> "Network timeout"
            "space" in s || "free space" in s || "no space" in s -> "Not enough free space"
            "content-range" in s || "range" in s || "416" in s -> "Resume failed (server refused range)"
            "host" in s && "unknown" in s -> "Unknown host (check connectivity/DNS)"
            else -> raw
        }
    }

    /** Delete-then-rename within the same directory to approximate atomic replace. */
    private fun replaceFinal(tmp: File, dst: File): Boolean {
        if (dst.exists() && !dst.delete()) return false
        return tmp.renameTo(dst)
    }

    @JvmStatic fun isInFlight(): Boolean = inFlight.get() != null
}
