package com.negi.survey.vm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.BuildConfig
import com.negi.survey.utils.HeavyInitializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI

/**
 * Ensures the on-device model file exists (downloads if needed).
 * - Serialized starts (Mutex) avoid duplicate downloads.
 * - Force re-download purges old/partial files and starts from scratch.
 * - Cancellation propagates to the downloader; progress updates are throttled.
 */
class AppViewModel(
    private val modelUrl: String = DEFAULT_MODEL_URL,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val uiThrottleMs: Long = DEFAULT_UI_THROTTLE_MS,
    private val uiMinDeltaBytes: Long = DEFAULT_UI_MIN_DELTA_BYTES
) : ViewModel() {

    companion object {
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm"
        private const val DEFAULT_FILE_NAME = "model.litertlm"
        private const val DEFAULT_TIMEOUT_MS = 30L * 60 * 1000 // 30 minutes
        private const val DEFAULT_UI_THROTTLE_MS = 250L
        private const val DEFAULT_UI_MIN_DELTA_BYTES = 1L * 1024L * 1024L // 1 MiB

        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel() as T
        }
        fun factory(
            url: String = DEFAULT_MODEL_URL,
            fileName: String = DEFAULT_FILE_NAME,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
            uiThrottleMs: Long = DEFAULT_UI_THROTTLE_MS,
            uiMinDeltaBytes: Long = DEFAULT_UI_MIN_DELTA_BYTES
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(url, fileName, timeoutMs, uiThrottleMs, uiMinDeltaBytes) as T
        }
    }

    // ---- State ----
    sealed class DlState {
        data object Idle : DlState()
        data class Downloading(val downloaded: Long, val total: Long?) : DlState()
        data class Done(val file: File) : DlState()
        data class Error(val message: String) : DlState()
        data object Canceled : DlState()
    }

    private val _state = MutableStateFlow<DlState>(DlState.Idle)
    val state: StateFlow<DlState> = _state

    private var currentJob: Job? = null
    private val startMutex = Mutex()

    // ---- Public API ----

    /** Start (or force) ensuring the model file exists. Serialized to avoid duplicates. */
    fun ensureModelDownloaded(appContext: Context, forceRedownload: Boolean = false) {
        val app = appContext.applicationContext
        val fileName = suggestFileName(modelUrl)
        val finalFile = File(app.filesDir, fileName)

        viewModelScope.launch(Dispatchers.IO) {
            startMutex.withLock {
                val st = _state.value
                if (!forceRedownload && (st is DlState.Downloading || st is DlState.Done)) return@withLock

                if (!forceRedownload && finalFile.exists() && finalFile.length() > 0L) {
                    _state.value = DlState.Done(finalFile)
                    return@withLock
                }

                if (forceRedownload) {
                    // Stop our job and clear single-flight before starting fresh
                    currentJob?.cancelAndJoin(); currentJob = null
                    runCatching { HeavyInitializer.cancel() }
                    HeavyInitializer.resetForDebug()
                    purgeModelFiles(app, fileName) // remove final and any .tmp/.part
                } else {
                    // Double-click guard
                    if (currentJob != null) return@withLock
                }

                currentJob = launchDownloadJob(app, fileName, forceFresh = forceRedownload)
            }
        }
    }

    /** Wi-Fi only gate. Non-blocking; the start sequence runs serialized inside. */
    fun ensureModelDownloadedWifiOnly(appContext: Context, forceRedownload: Boolean = false): Boolean {
        val ctx = appContext.applicationContext
        return if (isWifiConnected(ctx)) {
            ensureModelDownloaded(ctx, forceRedownload)
            true
        } else {
            _state.value = DlState.Error("wifi_required")
            false
        }
    }

    /** Explicit cancel from UI; also cancels the shared single-flight. */
    fun cancelDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            startMutex.withLock {
                currentJob?.cancel(); currentJob = null
                runCatching { HeavyInitializer.cancel() }
                _state.value = DlState.Canceled
            }
        }
    }

    /** Reset back to Idle (no disk or network side effects). */
    fun reset() {
        viewModelScope.launch(Dispatchers.IO) {
            startMutex.withLock {
                currentJob?.cancel(); currentJob = null
                _state.value = DlState.Idle
            }
        }
    }

    override fun onCleared() {
        currentJob?.cancel(); currentJob = null
        super.onCleared()
    }

    // ---- Ready checks (no download) ----

    fun verifyModelReady(appContext: Context): Boolean =
        HeavyInitializer.isAlreadyComplete(
            context = appContext.applicationContext,
            modelUrl = modelUrl,
            hfToken = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() },
            fileName = suggestFileName(modelUrl)
        )

    fun isModelCachedLocally(appContext: Context): Boolean {
        val f = File(appContext.filesDir, suggestFileName(modelUrl))
        return f.exists() && f.length() > 0L
    }

    fun publishDoneIfLocalPresent(appContext: Context): Boolean {
        val f = File(appContext.filesDir, suggestFileName(modelUrl))
        return if (f.exists() && f.length() > 0L) {
            _state.value = DlState.Done(f); true
        } else false
    }

    // ---- File helpers ----

    fun expectedLocalFile(appContext: Context): File =
        File(appContext.filesDir, suggestFileName(modelUrl))

    fun downloadedFileOrNull(): File? = (state.value as? DlState.Done)?.file

    val downloadedFileFlow = state.map { (it as? DlState.Done)?.file }.distinctUntilChanged()

    /** Suspend until a terminal state; returns file on success, null otherwise. */
    suspend fun awaitReadyFile(appContext: Context, forceRedownload: Boolean = false): File? {
        if (publishDoneIfLocalPresent(appContext)) return (state.value as? DlState.Done)?.file
        if (forceRedownload || state.value !is DlState.Downloading) {
            ensureModelDownloaded(appContext, forceRedownload)
        }
        val terminal = state.first { it is DlState.Done || it is DlState.Error || it is DlState.Canceled }
        return (terminal as? DlState.Done)?.file
    }

    // ---- Internals ----

    private fun launchDownloadJob(app: Context, fileName: String, forceFresh: Boolean): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val myJob = coroutineContext[Job]
            var lastUiEmitNs = 0L
            var lastUiBytes = 0L

            try {
                _state.value = DlState.Downloading(downloaded = 0L, total = null)

                val result = HeavyInitializer.ensureInitialized(
                    context = app,
                    modelUrl = modelUrl,
                    hfToken = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() },
                    fileName = fileName,
                    timeoutMs = timeoutMs,
                    forceFresh = forceFresh, // ensure rewrite-from-scratch when forced
                    onProgress = { got, total ->
                        if (myJob?.isActive != true) throw CancellationException("canceled before progress emit")
                        val now = System.nanoTime()
                        val elapsedMs = (now - lastUiEmitNs) / 1_000_000
                        val deltaBytes = got - lastUiBytes
                        val shouldEmit = elapsedMs >= uiThrottleMs ||
                                deltaBytes >= uiMinDeltaBytes ||
                                (total != null && got >= total)
                        if (shouldEmit) {
                            lastUiEmitNs = now
                            lastUiBytes = got
                            _state.value = DlState.Downloading(got, total)
                        }
                    }
                )

                result.fold(
                    onSuccess = { file -> _state.value = DlState.Done(file) },
                    onFailure = { e -> _state.value = DlState.Error(e.message ?: "download failed") }
                )
            } catch (ce: CancellationException) {
                _state.value = DlState.Canceled
                throw ce
            } catch (t: Throwable) {
                _state.value = DlState.Error(t.message ?: "download failed")
            } finally {
                startMutex.withLock { if (currentJob === this) currentJob = null }
            }
        }

    private fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun suggestFileName(url: String): String =
        runCatching {
            val uri = URI(url)
            (uri.path ?: "").substringAfterLast('/').ifBlank { fileName }
        }.getOrElse { fileName }

    /** Remove final file and any temp remnants (.tmp/.part/.partial). */
    private fun purgeModelFiles(context: Context, finalName: String) {
        val dir = context.filesDir
        dir.listFiles()?.forEach { f ->
            val n = f.name
            if (n == finalName ||
                n == "$finalName.tmp" ||
                n == "$finalName.part" ||
                (n.startsWith(finalName) &&
                        (n.endsWith(".tmp") || n.endsWith(".part") || n.endsWith(".partial")))
            ) {
                runCatching { f.delete() }
            }
        }
    }
}
