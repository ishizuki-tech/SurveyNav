// file: app/src/main/java/com/negi/survey/slm/SlmDirectRepository.kt
package com.negi.survey.slm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Contract interface for any class that can provide streaming inference responses.
 */
interface Repository {
    /**
     * Returns a Flow of streaming text chunks in response to a user prompt.
     *
     * @param prompt The user-provided input string (can be blank).
     * @return Flow<String> emitting chunks of the response as they arrive.
     */
    suspend fun request(prompt: String): Flow<String>
}

/**
 * Direct implementation of Repository backed by the SLM (Small Language Model).
 */
class SlmDirectRepository(
    private val appContext: Context,
    private val model: Model
) : Repository {

    companion object {
        private const val TAG = "SlmDirectRepository"
        private const val STRICT_RULES = """
STRICT OUTPUT (NO MARKDOWN):
- Entire output < 512 chars.
- Return RAW JSON only. DO NOT use Markdown, code fences, or backticks.
- Output must be ONE LINE; first char '{', last char '}'.
- No explanations or extra text.
"""
        private const val USER_TURN_PREFIX = "<start_of_turn>user"
        private const val MODEL_TURN_PREFIX = "<start_of_turn>model"
        private const val TURN_END = "<end_of_turn>"
        private const val EMPTY_JSON_INSTRUCTION = "Respond with an empty JSON object: {}"

        // Process-wide gate: strictly serialize in-flight requests across all repository instances.
        private val globalGate = Semaphore(1)

        // Initialization polling parameters.
        private const val INIT_WAIT_TOTAL_MS = 5_000L
        private const val INIT_WAIT_STEP_MS = 15L

        // Cleanup wait (to avoid races with the underlying session after cancel()).
        private const val CLEAN_WAIT_MS = 5_000L
        private const val CLEAN_STEP_MS = 500L

        // Watchdog for 'finished' but missing 'onClean'.
        private const val FINISH_WATCHDOG_MS = 1_500L
        private const val FINISH_WATCHDOG_STEP_MS = 100L
    }

    /** Compose the final model prompt with turn markers and strict-output rules. */
    fun buildPrompt(userPrompt: String): String {
        val effectivePrompt = if (userPrompt.isBlank()) EMPTY_JSON_INSTRUCTION else userPrompt
        return """
$USER_TURN_PREFIX
${effectivePrompt.trimIndent()}
${STRICT_RULES.trimIndent()}
$TURN_END
$MODEL_TURN_PREFIX
""".trimIndent()
    }

    /**
     * Ensure the model is initialized. If already initialized, this is a fast no-op.
     * Throws IllegalStateException if initialization reports an error or the instance
     * is not available after a short wait.
     */
    private suspend fun ensureInitialized() {
        if (model.instance != null) return

        val err = suspendCancellableCoroutine<String?> { cont ->
            try {
                SLM.initialize(appContext, model) { e ->
                    if (cont.isActive) cont.resume(e)
                }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }

        if (!err.isNullOrEmpty()) {
            throw IllegalStateException("SLM.initialize error: $err")
        }

        val ok = waitUntil(INIT_WAIT_TOTAL_MS, INIT_WAIT_STEP_MS) { model.instance != null }
        check(ok) { "SLM.initialize: model.instance was not set within ${INIT_WAIT_TOTAL_MS}ms" }
    }

    /** Small polling helper using suspending delay rather than blocking sleeps. */
    private suspend fun waitUntil(totalMs: Long, stepMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + totalMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            delay(stepMs)
        }
        return false
    }

    /**
     * Send the prompt to the SLM and return a Flow that emits partials as they arrive.
     * The flow closes when the engine reports `finished` or `onClean`.
     */
    override suspend fun request(prompt: String): Flow<String> =
        callbackFlow {
            // Only one concurrent request system-wide.
            globalGate.withPermit {
                // Ensure initialization under the same permit (strict serialization).
                ensureInitialized()

                Log.d(TAG, "SLM request start: model='${model.name}', prompt.len=${prompt.length}")

                // Anchor job (for structured cancellation / watchdogs).
                val anchor = launch {}

                // Per-flow flags (Atomic for cross-thread visibility).
                val closed = AtomicBoolean(false)
                val seenFinished = AtomicBoolean(false)
                val seenOnClean = AtomicBoolean(false)

                fun safeClose(reason: String? = null) {
                    if (closed.compareAndSet(false, true)) {
                        if (!reason.isNullOrBlank()) Log.d(TAG, "safeClose: $reason")
                        close()
                    }
                }

                try {
                    SLM.runInference(
                        model = model,
                        input = prompt,
                        listener = { partial, finished ->
                            if (partial.isNotEmpty()) {
                                if (!finished) {
                                    trySend(partial).onFailure {
                                        Log.w(
                                            TAG,
                                            "trySend(partial.len=${partial.length}) failed: ${it?.message}"
                                        )
                                    }
                                }
                            }
                            if (finished) {
                                Log.d(TAG, "SLM inference finished (model='${model.name}')")
                            }
                        },
                        onClean = {
                            seenOnClean.set(true)
                            Log.d(TAG, "SLM onClean (model='${model.name}')")
                            safeClose()
                        }
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "SLM.runInference threw: ${t.message}", t)
                    // Prefer cancel() here for broader compatibility over close(t).
                    cancel(CancellationException("SLM.runInference threw", t))
                }


                // Cleanup when the flow is closed/cancelled by the collector or by safeClose().
                awaitClose {
                    // Cancel our local anchor (best-effort).
                    anchor.cancel(CancellationException("callbackFlow closed"))

                    val finished = seenFinished.get()
                    val cleaned  = seenOnClean.get()

                    fun isBusyNow(): Boolean = runCatching { SLM.isBusy(model) }.getOrElse { false }

                    // Wait (short) for onClean or IDLE before releasing the permit to avoid session races.
                    fun waitCleanOrIdle(tag: String) {
                        val deadline = android.os.SystemClock.elapsedRealtime() + CLEAN_WAIT_MS
                        var loops = 0
                        android.os.SystemClock.sleep(CLEAN_STEP_MS)
                        while (android.os.SystemClock.elapsedRealtime() < deadline) {
                            if (seenOnClean.get()) break
                            if (!isBusyNow()) break
                            android.os.SystemClock.sleep(CLEAN_STEP_MS)
                            loops++
                        }
                        Log.d(
                            TAG,
                            "awaitClose: waitCleanOrIdle[$tag] done (loops=$loops, cleaned=${seenOnClean.get()}, busy=${isBusyNow()})"
                        )
                    }

                    when {
                        // The engine reported cleanup; wait briefly to be safely idle.
                        cleaned -> {
                            Log.d(TAG, "awaitClose: onClean observed → wait for idle then release")
                            waitCleanOrIdle("cleaned")
                        }
                        // Still busy: cancel and wait for onClean/IDLE, then optionally reset.
                        isBusyNow() -> {
                            runCatching {
                                Log.d(TAG, "awaitClose: engine BUSY → calling cancel()")
                                SLM.cancel(model)
                            }.onFailure { Log.w(TAG, "cancel() failed: ${it.message}") }

                            waitCleanOrIdle("after-cancel")

                            if (finished && !isBusyNow() && !seenOnClean.get()) {
                                runCatching {
                                    Log.d(TAG, "awaitClose: finished & idle (no onClean) → resetSession()")
                                    SLM.resetSession(model)
                                }.onFailure { Log.w(TAG, "resetSession() failed: ${it.message}") }
                            }
                        }
                        // Finished (no onClean) & idle: safe to reset.
                        finished -> {
                            runCatching {
                                Log.d(TAG, "awaitClose: finished(no onClean) & idle → resetSession()")
                                SLM.resetSession(model)
                            }.onFailure { Log.w(TAG, "resetSession() failed: ${it.message}") }
                        }
                        // Neither finished nor cleaned (collector cancelled early). Be conservative.
                        else -> {
                            if (isBusyNow()) {
                                runCatching {
                                    Log.d(TAG, "awaitClose: early cancel path → cancel()")
                                    SLM.cancel(model)
                                }.onFailure { Log.w(TAG, "cancel() failed: ${it.message}") }
                                waitCleanOrIdle("early-cancel")
                            }
                        }
                    }
                }
            }
        }
            .buffer(Channel.BUFFERED)      // Avoid backpressure on engine callback threads
            .flowOn(Dispatchers.IO)        // Make producer (including awaitClose) run on IO
}
