// file: com/negi/surveynav/slm/InferenceModel.kt
package com.negi.surveynav.slm

import android.content.Context
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class InferenceModel private constructor(appCtx: Context) {

    private val TAG = "InferenceModel"

    companion object {

        private const val MODEL_ASSET_NAME = "gemma-3n-E2B-it-int4.litertlm"
        private const val MAX_TOKENS = 512
        private const val DEFAULT_TOP_K = 20
        private const val DEFAULT_TOP_P = 0.98f
        private const val DEFAULT_TEMPERATURE = 0.0f
        private const val EMITTED_SO_FAR_MAX = 100_000
        private const val FUTURE_GET_TIMEOUT_SECONDS = 25L
        private const val CHUNK_MAX_LEN = 200
        private const val CHUNK_DELAY_MS = 15L
        private const val MAX_CONCURRENT_REQUESTS = 2
        private const val PENDING_EMITS_MAX = 25

        @Volatile private var instance: InferenceModel? = null

        fun getInstance(context: Context): InferenceModel {
            return instance ?: synchronized(this) {
                instance ?: InferenceModel(context.applicationContext).also {
                    instance = it
                    Log.i("InferenceModel", "InferenceModel instance created: ${it.hashCode()}")
                }
            }
        }
    }

    data class PartialResult(val requestId: String, val text: String, val done: Boolean)

    private val emitScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val nativeCloseExecutor = Executors.newSingleThreadExecutor {
        Thread(it, "InferenceModel-NativeClose").apply { isDaemon = true }
    }
    private val listenerExecutor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "InferenceModel-Listener").apply { isDaemon = true }
    }
    private val emitRetryExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1) {
        Thread(it, "InferenceModel-EmitRetry").apply { isDaemon = true }
    }

    private val appContext = appCtx.applicationContext

    private val _partialResults =
        MutableSharedFlow<PartialResult>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val partialResults: SharedFlow<PartialResult> = _partialResults.asSharedFlow()

    @Volatile private var llm: LlmInference? = null
    @Volatile private var loadedPath: String? = null

    private val closed = AtomicBoolean(false)
    private val loaded = AtomicBoolean(false)
    private val loadMutex = Mutex()

    private data class RequestState(
        val requestId: String,
        val session: LlmInferenceSession,
        val future: ListenableFuture<String>,
        val doneEmitted: AtomicBoolean = AtomicBoolean(false),
        val lastPartial: AtomicReference<String?> = AtomicReference(""),
        val lastEmitted: AtomicReference<String?> = AtomicReference(""),
        val emittedSoFarBuilder: StringBuilder = StringBuilder(),
        val emittedLock: Any = Any(),
        val finalEmitted: AtomicBoolean = AtomicBoolean(false),
        val closed: AtomicBoolean = AtomicBoolean(false),
        val cancelled: AtomicBoolean = AtomicBoolean(false)
    ) {
        fun appendEmitted(part: String, maxLen: Int) {
            synchronized(emittedLock) {
                emittedSoFarBuilder.append(part)
                if (emittedSoFarBuilder.length > maxLen) {
                    val keep = emittedSoFarBuilder.substring(emittedSoFarBuilder.length - maxLen)
                    emittedSoFarBuilder.setLength(0)
                    emittedSoFarBuilder.append(keep)
                }
            }
        }
        fun getEmittedSoFar(): String = synchronized(emittedLock) { emittedSoFarBuilder.toString() }
    }

    private val requests = ConcurrentHashMap<String, RequestState>()
    private val pendingEmits: ConcurrentLinkedQueue<PartialResult> = ConcurrentLinkedQueue()

    @Volatile private var emitFailCount = 0L
    @Volatile private var totalPartialCount = 0L
    @Volatile private var droppedEmitsCount = 0L

    private val requestSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)

    init {
        emitRetryExecutor.scheduleWithFixedDelay({
            try { drainPendingEmits() } catch (t: Throwable) { Log.w(TAG, "emitRetry failed", t) }
        }, 50, 50, TimeUnit.MILLISECONDS)
    }

    suspend fun ensureLoaded(path: String? = null) {
        // すでにロード済み & パス一致なら即 return
        if (loaded.get() && (path == null || path == loadedPath)) return

        // パスが変わった場合は一旦クローズしてフラグを戻す
        if (loaded.get() && path != null && path != loadedPath) {
            loadMutex.withLock {
                if (path == loadedPath) return
                safeCloseLlm()            // ★ Main以外でクローズ
                llm = null
                loaded.set(false)
                loadedPath = null
            }
        }
        loadMutex.withLock {

            if (loaded.get() && (path == null || path == loadedPath)) return@withLock

            val fileNameOrPath = path ?: MODEL_ASSET_NAME
            val taskPath = path ?: withContext(Dispatchers.IO) {
                ensureModelPresent(appContext, fileNameOrPath)
            }

            try {
                // ★ 重い生成は Main 以外で実行（GPU→ダメならCPU）
                val created = withContext(Dispatchers.Default) {
                    val gpuOpts = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(taskPath)
                        .setMaxTokens(MAX_TOKENS)
                        .setPreferredBackend(LlmInference.Backend.GPU)
                        .build()
                    try {
                        LlmInference.createFromOptions(appContext, gpuOpts)
                    } catch (_: Throwable) {
                        val cpuOpts = LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(taskPath)
                            .setMaxTokens(MAX_TOKENS)
                            .setPreferredBackend(LlmInference.Backend.CPU)
                            .build()
                        LlmInference.createFromOptions(appContext, cpuOpts)
                    }
                }

                llm = created
                loaded.set(true)
                loadedPath = taskPath
                Log.i(TAG, "InferenceModel loaded (path=$taskPath, llm=${llm?.hashCode()})")

            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize LlmInference", e)
                runCatching { safeCloseLlm() }
                llm = null
                loaded.set(false)
                loadedPath = null
                throw e
            }
        }
    }

    private suspend fun safeCloseLlm() = withContext(Dispatchers.Default) {
        try { llm?.close() } catch (_: Throwable) {}
    }
    private fun requireLlm(): LlmInference = llm ?: throw IllegalStateException("InferenceModel not loaded: call ensureLoaded()")

    /* ======================= Session ======================= */
    private fun sanitizeTopP(raw: Float): Float =
        when {
            raw.isNaN() -> DEFAULT_TOP_P
            raw > 1f && raw <= 100f -> (raw / 100f).coerceIn(0f, 1f)
            else -> raw.coerceIn(0f, 1f)
        }

    private fun sanitizeTopK(raw: Int): Int = raw.coerceAtLeast(0)

    private fun sanitizeTemperature(raw: Float): Float =
        if (raw.isNaN()) DEFAULT_TEMPERATURE else raw.coerceIn(0f, 5f)

    private fun newSession(
        topK: Int,
        topP: Float,
        temperature: Float,
        randomSeed: Int? = null
    ): LlmInferenceSession {

        val sTopK = sanitizeTopK(topK)
        val sTopP = sanitizeTopP(topP)
        val sTemp = sanitizeTemperature(temperature)

        if (sTopK != topK || sTopP != topP || sTemp != temperature) {
            Log.w(TAG, "newSession sanitized: topK=$sTopK topP=$sTopP temp=$sTemp (was $topK/$topP/$temperature)")
        }

        val builder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(sTopK)
            .setTopP(sTopP)
            .setTemperature(sTemp)
        if (randomSeed != null) builder.setRandomSeed(randomSeed)

        return try {
            val s = LlmInferenceSession.createFromOptions(requireLlm(), builder.build())
            Log.i(TAG, "New session: ${s.hashCode()} (topK=$sTopK topP=$sTopP temp=$sTemp)")
            s
        } catch (e: Exception) {
            Log.e(TAG, "create session failed", e)
            throw e
        }
    }

    /* ======================= Request ======================= */
    fun startRequest(
        prompt: String,
        topK: Int = DEFAULT_TOP_K,
        topP: Float = DEFAULT_TOP_P,
        temperature: Float = DEFAULT_TEMPERATURE,
        randomSeed: Int? = null
    ): String {

        val requestId = UUID.randomUUID().toString()

        Log.d(TAG, " ")
        Log.d(TAG, "========================================================================")
        Log.d(TAG, "startRequest(requestId=$requestId) thread=${Thread.currentThread().name}")
        Log.d(TAG, "Prompt (truncated 512): ${prompt.take(512).replace("\n", "\\n")}")

        val acquired = try {
            requestSemaphore.tryAcquire(200, TimeUnit.MILLISECONDS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        if (!acquired) {
            Log.w(TAG, "Too many concurrent requests; rejecting request=$requestId")
            return requestId
        }

        val localSession = try {
            newSession(topK, topP, temperature, randomSeed)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create session for request=$requestId", e)
            requestSemaphore.release()
            return requestId
        }

        try {
            localSession.addQueryChunk(prompt)
        } catch (e: Throwable) {
            Log.e(TAG, "addQueryChunk failed for request=$requestId session=${localSession.hashCode()}", e)
            runCatching { localSession.close() }
            requestSemaphore.release()
            return requestId
        }

        val doneEmitted = AtomicBoolean(false)
        val lastPartial = AtomicReference<String?>("")
        val lastEmitted = AtomicReference<String?>("")

        try {
            val future: ListenableFuture<String> =
                localSession.generateResponseAsync { partial: String, done: Boolean ->
                    Log.d(TAG, "  partial = $partial    done = $done")
                    try {
                        totalPartialCount++
                        lastPartial.set(partial)
                        val state = requests[requestId]
                        if (state != null && state.finalEmitted.get()) {
                            Log.d(TAG, "callback: final emission in progress -> skip")
                        } else {
                            val emitted = tryEmitPartial(PartialResult(requestId, partial, done))
                            if (emitted) {
                                lastEmitted.set(partial)
                                requests[requestId]?.appendEmitted(partial, EMITTED_SO_FAR_MAX)
                            } else {
                                Log.w(TAG, "failed to emit partial -> queued")
                            }
                        }

                        if (done) {
                            if (doneEmitted.compareAndSet(false, true)) {
                                Log.i(TAG, "done=true (callback) request=$requestId; cleanup deferred")
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Exception in callback for request=$requestId", e)
                    }
                }

            val state = RequestState(requestId, localSession, future, doneEmitted, lastPartial, lastEmitted)
            requests[requestId] = state
            Log.i(TAG, "Stored request state request=$requestId session=${localSession.hashCode()} future=${future.hashCode()} totalRequests=${requests.size}")

            try {
                future.addListener(Runnable { handleFutureCompletion(requestId, state) }, listenerExecutor)
            } catch (ree: RejectedExecutionException) {
                Log.w(TAG, "listenerExecutor rejected addListener; fallback submit", ree)
                runCatching { listenerExecutor.submit { handleFutureCompletion(requestId, state) } }
                    .onFailure { Log.e(TAG, "Fallback listener submit failed", it) }
            }

        } catch (e: Throwable) {
            Log.e(TAG, "startRequest failed for request=$requestId", e)
            runCatching { localSession.close() }
            requests.remove(requestId)
            requestSemaphore.release()
            return requestId
        }

        return requestId
    }

    private fun handleFutureCompletion(requestId: String, state: RequestState) {
        try {
            Log.d(TAG, "Future listener invoked for request=$requestId (isDone=${state.future.isDone} isCancelled=${state.future.isCancelled})")

            if (state.doneEmitted.compareAndSet(false, true)) {
                var finalText: String? = state.lastPartial.get()
                if (finalText.isNullOrEmpty()) {
                    finalText = runCatching { state.future.get(FUTURE_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                        .onFailure { Log.w(TAG, "future.get() failed for request=$requestId", it) }
                        .getOrNull() ?: ""
                }

                if (finalText.isEmpty()) {
                    Log.w(TAG, "finalText empty, emitting placeholder for request=$requestId")
                    tryEmitPartial(PartialResult(requestId, "__NO_OUTPUT__", true))
                } else {
                    state.finalEmitted.set(true)
                    val already = state.getEmittedSoFar()
                    Log.d(TAG, "finalText.len=${finalText.length} emittedSoFar.len=${already.length} request=$requestId")

                    val toEmit = computeMissingSuffixTokenAware(already, finalText)
                    if (toEmit.isEmpty()) {
                        Log.i(TAG, "No missing suffix -> done marker only request=$requestId")
                        tryEmitPartial(PartialResult(requestId, "", true))
                    } else {
                        Log.i(TAG, "Emitting missing suffix.len=${toEmit.length} request=$requestId")
                        chunkAndEmitFinalWithState(requestId, finalText, toEmit, state, CHUNK_DELAY_MS)
                    }
                }
            } else {
                Log.d(TAG, "done already emitted for request=$requestId")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Exception in future listener for request=$requestId", e)
        } finally {
            // session close (idempotent)
            if (requests[requestId]?.closed?.compareAndSet(false, true) == true) {
                val st = requests[requestId]!!
                nativeCloseExecutor.submit {
                    try {
                        runCatching { st.session.cancelGenerateResponseAsync() }
                        try { Thread.sleep(10) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        runCatching { st.session.close() }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error in nativeCloseExecutor for request=$requestId", t)
                    }
                }
            }
            if (requests.remove(requestId) != null) {
                Log.i(TAG, "Request cleaned request=$requestId remaining=${requests.size}")
                requestSemaphore.release()
            }
        }
    }

    private fun computeMissingSuffixTokenAware(already: String, finalText: String): String {
        val a = already.trim()
        val f = finalText.trim()
        if (a.isEmpty()) return f
        if (a == f) return ""
        val maxCheckLen = min(a.length, 1024)
        val startIdx = maxOf(0, a.length - maxCheckLen)
        for (len in (a.length - startIdx) downTo 1) {
            val suffix = a.substring(a.length - len)
            val idx = f.lastIndexOf(suffix)
            if (idx >= 0) {
                val emitStart = idx + suffix.length
                return if (emitStart >= f.length) "" else f.substring(emitStart).trimStart()
            }
        }
        val occ = f.indexOf(a)
        if (occ >= 0) {
            val emitStart = occ + a.length
            return if (emitStart >= f.length) "" else f.substring(emitStart).trimStart()
        }
        if (f.startsWith(a)) return f.removePrefix(a).trimStart()
        return f
    }

    private fun chunkAndEmitFinalWithState(
        requestId: String,
        finalText: String,
        toEmit: String,
        state: RequestState,
        emitDelayMs: Long = 20L
    ) {
        emitScope.launch {
            try {
                val sentenceRegex = Regex("(?<=[。．！？!?]|\\.|\\!|\\?)\\s*")
                val pieces: List<String> = sentenceRegex.split(toEmit).map { it.trim() }.filter { it.isNotEmpty() }
                val chunks = if (pieces.isNotEmpty()) pieces else toEmit.chunked(CHUNK_MAX_LEN)
                for ((i, chunk) in chunks.withIndex()) {
                    if (state.cancelled.get()) break
                    val isLast = (i == chunks.lastIndex)
                    val emitted = tryEmitPartial(PartialResult(requestId, chunk, isLast))
                    if (emitted) {
                        state.appendEmitted(chunk, EMITTED_SO_FAR_MAX)
                        state.lastEmitted.set(chunk)
                    } else {
                        Log.w(TAG, "chunk emit failed -> queued request=$requestId len=${chunk.length}")
                    }
                    Log.d(TAG, "chunk emitted request=$requestId len=${chunk.length} done=$isLast")
                    if (emitDelayMs > 0 && !isLast) delay(emitDelayMs)
                }
                val cur = state.getEmittedSoFar()
                if (!cur.endsWith(finalText)) {
                    state.appendEmitted(finalText.substring(maxOf(0, finalText.length - EMITTED_SO_FAR_MAX)), EMITTED_SO_FAR_MAX)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "chunkAndEmitFinal error request=$requestId", e)
                tryEmitPartial(PartialResult(requestId, finalText, true))
                state.appendEmitted(finalText, EMITTED_SO_FAR_MAX)
                state.lastEmitted.set(finalText)
            }
        }
    }

    /* ======================= Emit queue ======================= */
    private fun tryEmitPartial(pr: PartialResult): Boolean {
        return try {
            val ok = _partialResults.tryEmit(pr)
            if (!ok) {
                if (pendingEmits.size >= PENDING_EMITS_MAX) {
                    pendingEmits.poll()?.let {
                        droppedEmitsCount++
                        Log.w(TAG, "pendingEmits overflow: dropped oldest partial request=${it.requestId} len=${it.text.length}")
                    }
                }
                pendingEmits.add(pr)
                emitFailCount++
            }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "tryEmitPartial error", t)
            if (pendingEmits.size >= PENDING_EMITS_MAX) {
                pendingEmits.poll()?.let {
                    droppedEmitsCount++
                    Log.w(TAG, "pendingEmits overflow (exception path): dropped oldest request=${it.requestId}")
                }
            }
            pendingEmits.add(pr)
            emitFailCount++
            false
        }
    }

    private fun drainPendingEmits() {
        var attempts = 0
        while (attempts < 200) {
            val pr = pendingEmits.peek() ?: break
            val ok = runCatching { _partialResults.tryEmit(pr) }.getOrDefault(false)
            if (ok) pendingEmits.remove() else break
            attempts++
        }
    }

    /* ======================= Cancel / Close ======================= */
    fun cancelRequest(requestId: String) {
        val state: RequestState? = requests[requestId]
        if (state == null) {
            Log.w(TAG, "cancelRequest: no such requestId=$requestId"); return
        }
        Log.i(TAG, "cancelRequest request=$requestId session=${state.session.hashCode()} future=${state.future.hashCode()}")

        state.cancelled.set(true)
        runCatching { state.session.cancelGenerateResponseAsync() }
        runCatching { state.future.cancel(true) }

        if (state.closed.compareAndSet(false, true)) {
            nativeCloseExecutor.submit {
                try {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    runCatching { state.session.close() }
                    Log.i(TAG, "Closed session: ${state.session.hashCode()} (cancelRequest)")
                } catch (t: Throwable) {
                    Log.w(TAG, "Error closing session in cancelRequest", t)
                }
            }
        }

        if (requests.remove(requestId) != null) requestSemaphore.release()
        _partialResults.tryEmit(PartialResult(requestId, "__CANCELLED__", true))
        Log.i(TAG, "cancelRequest completed request=$requestId")
    }

    fun cancelAll() {
        requests.keys().toList().forEach { cancelRequest(it) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            Log.i(TAG, "close() called but already closed"); return
        }
        Log.i(TAG, "close() called")
        cancelAll()
        emitScope.cancel()
        runCatching { llm?.close() }.onFailure { Log.w(TAG, "llm.close threw", it) }
        runCatching { nativeCloseExecutor.shutdownNow() }
        runCatching { listenerExecutor.shutdownNow() }
        runCatching { emitRetryExecutor.shutdownNow() }
        instance = null
        llm = null
        loaded.set(false)
        loadedPath = null
        Log.i(TAG, "InferenceModel closed")
    }

    /* ======================= Utils ======================= */
    private val ALLOW_ASSETS_FALLBACK = false // 開発中のみ true 推奨

    private fun ensureModelPresent(context: Context, fileOrName: String): String {
        // 1) 絶対パス
        val asFile = File(fileOrName)
        if (asFile.isAbsolute) {
            require(asFile.exists()) { "Model not found (absolute path): ${asFile.absolutePath}" }
            return asFile.absolutePath
        }
        // 2) filesDir/<name>
        val dst = File(context.filesDir, fileOrName)
        if (dst.exists()) {
            Log.i(TAG, "Model present at ${dst.absolutePath}")
            return dst.absolutePath
        }
        // 3) assets フォールバック
        if (ALLOW_ASSETS_FALLBACK) {
            val assetPath = "models/$fileOrName"
            return try {
                Log.i(TAG, "Copying assets/$assetPath -> ${dst.absolutePath}")
                context.assets.open(assetPath).use { input ->
                    dst.parentFile?.mkdirs()
                    FileOutputStream(dst).use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Model copied to ${dst.absolutePath}")
                dst.absolutePath
            } catch (e: Throwable) {
                Log.e(TAG, "Copy from assets failed", e)
                throw IllegalStateException(
                    "Model not installed: ${dst.absolutePath}. Place the file in filesDir or pass an absolute path.",
                    e
                )
            }
        }
        // 4) 明示的にエラー
        throw IllegalStateException(
            "Model not installed: ${dst.absolutePath}. " +
                    "Download the model into filesDir or call ensureLoaded(<absolutePath>)."
        )
    }

    fun logMetrics() {
        Log.i(
            TAG,
            "metrics: totalPartialCount=$totalPartialCount emitFailCount=$emitFailCount " +
                    "droppedEmitsCount=$droppedEmitsCount pendingEmits=${pendingEmits.size} activeRequests=${requests.size}"
        )
    }
}

