@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/* ============================================================
   InferenceModel — Repository-facing wrapper over SLM (text-only)
   - Provides pre-initialization via ensureLoaded(), streaming via startRequest(),
     and control APIs cancelRequest() / close().
   - Assumes single concurrent generation; SLM enforces serialization internally.
   - This file adds English-only KDoc and explanatory inline comments.
   ============================================================ */

/* -----------------------
   Constants / Defaults
   ----------------------- */

private const val TAG = "InferenceModel"
private const val PARTIAL_BUFFER_CAPACITY = 64
private const val NOT_INITIALIZED_MSG = "Model is not initialized. Call ensureLoaded() first."

/**
 * A lightweight wrapper that manages inference start/cancel and exposes partial
 * results as a [SharedFlow].
 *
 * Intended Repository-facing API:
 * - [startRequest]: begin streaming and return a requestId
 * - [partialResults]: stream of (requestId, text, done)
 * - [cancelRequest]: cancel an in-flight request by requestId
 *
 * Initialization flow:
 * - Call [setModel] then [ensureLoaded] at app startup. [ensureLoaded] suspends
 *   until the model is ready.
 * - [startRequest] does not wait for initialization; if the model is not ready,
 *   it immediately emits a terminal error to the stream.
 *
 * Threading/Lifecycle:
 * - [partialResults] may be emitted from background threads; switch to the main
 *   thread on the consumer side for UI updates.
 * - [getInstance] retains ApplicationContext to avoid leaking UI components.
 */
class InferenceModel private constructor(
    private val appContext: Context
) {

    /**
     * A single partial response from the model.
     *
     * @property requestId Identifier of the streaming request that produced this result.
     * @property text Text chunk from the model; may be empty.
     * @property done True when the stream has completed.
     */
    data class PartialResult(
        val requestId: String,
        val text: String,
        val done: Boolean
    )

    companion object {
        @Volatile private var INSTANCE: InferenceModel? = null

        /**
         * Get or create the singleton instance.
         *
         * Application context is stored internally to avoid lifecycle leaks.
         *
         * @param context Any context (converted to applicationContext internally).
         * @return The singleton [InferenceModel].
         */
        fun getInstance(context: Context): InferenceModel =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: InferenceModel(context.applicationContext).also { INSTANCE = it }
            }
    }

    /* -----------------------
       Streams
       ----------------------- */

    // SharedFlow for multiple subscribers; DROP_OLDEST prevents backpressure stalls.
    private val _partialResults = MutableSharedFlow<PartialResult>(
        replay = 0,
        extraBufferCapacity = PARTIAL_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Public read-only stream of partial results. */
    val partialResults: SharedFlow<PartialResult> = _partialResults.asSharedFlow()

    /* -----------------------
       State
       ----------------------- */

    @Volatile private var configuredModel: Model? = null
    private val currentRequestId = AtomicReference<String?>(null)
    private val finishing = AtomicBoolean(false)

    /* -----------------------
       Configuration
       ----------------------- */

    /**
     * Set the model to be used. Typically called once at app startup.
     *
     * @param model Model definition containing path and sampling configuration.
     */
    fun setModel(model: Model) {
        configuredModel = model
    }

    /* -----------------------
       Initialization
       ----------------------- */

    /**
     * Initialize the model and suspend until it is ready.
     *
     * Call this during app startup; subsequent [startRequest] calls will not
     * wait for initialization.
     *
     * @param expectedModelPath Optional sanity check; logs a warning if the actual path differs.
     * @throws IllegalStateException If MediaPipe initialization fails (wrapped task error).
     */
    suspend fun ensureLoaded(expectedModelPath: String? = null) {
        val model = configuredModel
            ?: error("InferenceModel: model is not configured. Call setModel(model) first.")

        if (expectedModelPath != null) {
            val actual = model.getPath()
            if (actual != expectedModelPath) {
                Log.w(TAG, "ensureLoaded: modelPath mismatch. expected=$expectedModelPath actual=$actual")
            }
        }

        if (model.instance != null) return // Already initialized.

        // Bridge SLM.initialize (callback) into suspend.
        return suspendCancellableCoroutine { cont ->
            SLM.initialize(appContext, model) { err ->
                if (err.isEmpty()) cont.resume(Unit)
                else cont.resumeWithException(IllegalStateException(err))
            }
        }
    }

    /* -----------------------
       Inference (streaming)
       ----------------------- */

    /**
     * Start a streaming inference and return a new request ID.
     *
     * Assumes [ensureLoaded] has already completed. If the model is not initialized,
     * a terminal error is emitted to the stream immediately and the new requestId is returned.
     *
     * Note: SLM is currently text-only. The [images] parameter is reserved for future use.
     *
     * @param prompt Input text. Empty is allowed (validated by upper layers).
     * @param images Reserved for future multimodal support (currently unused).
     * @return The issued requestId.
     */
    fun startRequest(
        prompt: String,
        images: List<Bitmap> = emptyList()
    ): String {
        val model = configuredModel
            ?: error("InferenceModel: model is not configured. Call setModel(model) first.")

        val reqId = UUID.randomUUID().toString()

        // Non-blocking behavior: if not initialized, emit terminal error and return.
        if (model.instance == null) {
            emitPartial(reqId, NOT_INITIALIZED_MSG, done = true)
            return reqId
        }

        // Keep current requestId for cancel matching.
        currentRequestId.set(reqId)
        finishing.set(false)

        if (!SLM.isBusy(model)) {
            SLM.resetSession(model)
        }

        // Start streaming; SLM enforces single concurrency per model internally.
        SLM.runInference(
            model = model,
            input = prompt,
            resultListener = { partial, done ->
                // Suppress empty noise but always forward the terminal event.
                if (partial.isNotEmpty()) {
                    emitPartial(reqId, partial, done)
                } else if (done) {
                    emitPartial(reqId, "", true)
                }

                if (done) {
                    // Mark finished and clear active requestId.
                    finishing.set(true)
                    currentRequestId.compareAndSet(reqId, null)
                }
            },
            cleanUpListener = {
                // Hook for deferred cleanup notifications; UI updates can be triggered here if needed.
            }
        )

        return reqId
    }

    /**
     * Cancel an in-flight generation for the given [requestId].
     *
     * Only cancels when the ID matches the active request. Completed or mismatched
     * IDs are ignored to avoid interfering with serialization guarantees.
     *
     * @param requestId The ID to cancel.
     */
    fun cancelRequest(requestId: String) {
        val model = configuredModel ?: return
        val active = currentRequestId.get()

        // Ignore if already finished or no active request.
        if (finishing.get() || active == null) return

        if (active != requestId) {
            // Different ID: do nothing (Repository-side serialization is assumed).
            Log.w(TAG, "cancelRequest: ignored (active=$active, requested=$requestId)")
            return
        }

        SLM.cancel(model)
    }

    /**
     * Explicitly release resources (e.g., at app shutdown).
     *
     * If a stream is running, cleanup is deferred and performed after completion.
     * This method triggers asynchronous destruction and does not wait.
     */
    fun close() {
        val model = configuredModel ?: return
        SLM.cleanUp(model) { /* no-op */ }
        currentRequestId.set(null)
        finishing.set(false)
    }

    /**
     * Whether the underlying SLM is currently busy (RUNNING or CANCELLING).
     *
     * Useful for tests and diagnostics.
     *
     * @return True if SLM reports a non-IDLE state; false otherwise or when not configured.
     */
    fun isBusy(): Boolean {
        val model = configuredModel ?: return false
        return SLM.isBusy(model)
    }

    /* -----------------------
       Private helpers
       ----------------------- */

    /** Centralize emission to the SharedFlow (non-blocking; drops oldest on overflow). */
    private fun emitPartial(requestId: String, text: String, done: Boolean) {
        _partialResults.tryEmit(PartialResult(requestId, text, done))
        // Explanation: tryEmit is non-suspending; overflow behavior follows the flow's policy.
    }
}
