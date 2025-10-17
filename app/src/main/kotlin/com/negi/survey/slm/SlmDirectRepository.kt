// file: app/src/main/java/com/negi/survey/slm/SlmDirectRepository.kt
package com.negi.survey.slm

import android.util.Log
import com.negi.survey.config.SurveyConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Repository that streams inference results from an on-device SLM.
 *
 * Responsibilities:
 * - Build final prompt with YAML overrides (SurveyConfig.slm)
 * - Stream tokens via Flow<String> using callbackFlow
 * - Robust lifecycle: finished flag, onClean observer, idle-grace & watchdog
 * - Defensive cleanup (cancel/reset) on abnormal paths
 */

class SlmDirectRepository(
    private val model: Model,
    private val config: SurveyConfig
) : Repository {

    companion object {
        private const val TAG = "SlmDirectRepository"

        // ---------- YAML fallback defaults ----------
        private const val DEF_USER_TURN_PREFIX = "<start_of_turn>user"
        private const val DEF_MODEL_TURN_PREFIX = "<start_of_turn>model"
        private const val DEF_TURN_END = "<end_of_turn>"
        private const val DEF_EMPTY_JSON_INSTRUCTION = "Respond with an empty JSON object: {}"

        private const val DEF_PREAMBLE =
            "You are a well-known English survey expert. Read the Question and the Answer."
        private const val DEF_KEY_CONTRACT =
            "OUTPUT FORMAT:\n- In English.\n- Keys: \"analysis\", \"expected answer\", \"follow-up questions\" (Exactly 3 in an array), \"score\" (int 1–100)."
        private const val DEF_LENGTH_BUDGET =
            "LENGTH LIMITS:\n- analysis<=60 chars; each follow-up<=80; expected answer<=40."
        private const val DEF_SCORING_RULE =
            "Scoring rule: Judge ONLY content relevance/completeness/accuracy. Do NOT penalize style or formatting."
        private const val DEF_STRICT_OUTPUT =
            "STRICT OUTPUT (NO MARKDOWN):\n- RAW JSON only, ONE LINE.\n- Use COMPACT JSON (no spaces around ':' and ',').\n- No extra text.\n- Entire output<=512 chars."

        // ---------- Concurrency / lifecycle ----------
        private val globalGate = Semaphore(1) // serialize access to the single SLM instance

        private const val CLEAN_WAIT_MS = 5_000L
        private const val CLEAN_STEP_MS = 500L
        private const val FINISH_WATCHDOG_DEFAULT_MS = 3_000L
        private const val FINISH_WATCHDOG_STEP_MS = 100L
        private const val FINISH_IDLE_GRACE_DEFAULT_MS = 250L

        private const val FINISH_WATCHDOG_MS = FINISH_WATCHDOG_DEFAULT_MS
        private const val FINISH_IDLE_GRACE_MS = FINISH_IDLE_GRACE_DEFAULT_MS
    }

    // ------------------------------ Prompt builder ------------------------------

    /**
     * Build the final prompt string with YAML `slm` overrides.
     * - If `userPrompt` is blank, use `empty_json_instruction`.
     * - Normalize line breaks and trim trailing newlines.
     * - Skip blank blocks when joining to avoid extra empty lines.
     */
    override fun buildPrompt(userPrompt: String): String {
        val slm = config.slm
        val userTurn   = slm.user_turn_prefix       ?: DEF_USER_TURN_PREFIX
        val modelTurn  = slm.model_turn_prefix      ?: DEF_MODEL_TURN_PREFIX
        val turnEnd    = slm.turn_end               ?: DEF_TURN_END
        val emptyJson  = slm.empty_json_instruction ?: DEF_EMPTY_JSON_INSTRUCTION

        // System framing blocks (optional in YAML)
        val preamble     = slm.preamble      ?: DEF_PREAMBLE
        val keyContract  = slm.key_contract  ?: DEF_KEY_CONTRACT
        val lengthBudget = slm.length_budget ?: DEF_LENGTH_BUDGET
        val scoringRule  = slm.scoring_rule  ?: DEF_SCORING_RULE
        val strictOutput = slm.strict_output ?: DEF_STRICT_OUTPUT

        val effective = if (userPrompt.isBlank()) emptyJson else userPrompt.trimIndent().normalize()

        val finalPrompt = compactJoin(
            userTurn,
            preamble,
            effective,
            keyContract,
            lengthBudget,
            scoringRule,
            strictOutput,
            turnEnd,
            modelTurn
        )
        Log.d(TAG, "buildPrompt: in.len=${userPrompt.length}, out.len=${finalPrompt.length}")
        return finalPrompt
    }

    // ------------------------------ Inference streaming ------------------------------

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun request(prompt: String): Flow<String> =
        callbackFlow {
            val out = this

            // Ensure only one active inference at a time for the process.
            globalGate.withPermit {
                Log.d(TAG, "SLM request start: model='${model.name}', prompt.len=${prompt.length}")

                // Anchor scope for watchdogs that must be tied to the flow's lifecycle.
                val anchorScope = CoroutineScope(coroutineContext + SupervisorJob())

                // Lightweight state flags, all thread-safe.
                val closed       = AtomicBoolean(false)
                val seenFinished = AtomicBoolean(false)
                val seenOnClean  = AtomicBoolean(false)

                fun isBusyNow(): Boolean =
                    runCatching { SLM.isBusy(model) }
                        .onFailure { Log.w(TAG, "SLM.isBusy threw: ${it.message}") }
                        .getOrElse { true } // defensive: treat as busy on errors

                fun safeClose(reason: String? = null) {
                    if (closed.compareAndSet(false, true)) {
                        if (!reason.isNullOrBlank()) Log.d(TAG, "safeClose: $reason")
                        out.close()
                    }
                }

                try {
                    SLM.runInference(
                        model = model,
                        input = prompt,
                        listener = { partial, finished ->
                            // Forward partial tokens if the channel is still open.
                            if (partial.isNotEmpty() && !out.isClosedForSend) {
                                val r = out.trySend(partial)
                                if (r.isFailure) {
                                    Log.w(
                                        TAG,
                                        "trySend(partial.len=${partial.length}) failed: ${r.exceptionOrNull()?.message}"
                                    )
                                }
                            }

                            if (finished) {
                                // Mark finished and start a single watchdog job to close safely.
                                seenFinished.set(true)
                                Log.d(TAG, "SLM inference finished (model='${model.name}')")

                                anchorScope.launch {
                                    // Wait until:
                                    // (a) onClean is observed, OR
                                    // (b) engine stays IDLE for FINISH_IDLE_GRACE_MS, OR
                                    // (c) watchdog timeout.
                                    val ok = withTimeoutOrNull(FINISH_WATCHDOG_MS) {
                                        var idleSince = -1L
                                        while (isActive && !closed.get() && !seenOnClean.get()) {
                                            val busy = isBusyNow()
                                            val now = android.os.SystemClock.elapsedRealtime()

                                            if (!busy) {
                                                if (idleSince < 0) idleSince = now
                                                val idleDur = now - idleSince
                                                if (idleDur >= FINISH_IDLE_GRACE_MS) {
                                                    Log.d(TAG, "finish idle-grace (${idleDur}ms) → safeClose()")
                                                    break
                                                }
                                            } else {
                                                // Reset the idle window if activity returns.
                                                idleSince = -1L
                                            }

                                            // Small delay to avoid a hot poll loop.
                                            kotlinx.coroutines.delay(FINISH_WATCHDOG_STEP_MS)
                                        }
                                        true // reached by onClean or idle-grace
                                    } != null

                                    // If onClean didn't arrive and we're not closed yet, close safely.
                                    if (!closed.get() && !seenOnClean.get()) {
                                        if (ok) {
                                            // Idle-grace path
                                            safeClose("finished-idle-grace")
                                        } else {
                                            // Watchdog timeout path
                                            Log.w(
                                                TAG,
                                                "finish watchdog: onClean not observed within ${FINISH_WATCHDOG_MS}ms → safeClose()"
                                            )
                                            safeClose("finish-watchdog-timeout")
                                        }
                                    }
                                }
                            }
                        },
                        onClean = {
                            // Engine/session signaled cleanup.
                            seenOnClean.set(true)
                            Log.d(TAG, "SLM onClean (model='${model.name}')")
                            safeClose("onClean")
                        }
                    )
                } catch (t: Throwable) {
                    // Log first for better diagnosability.
                    Log.e(TAG, "SLM.runInference threw: ${t.message}", t)

                    // Ensure the producer channel closes to unblock awaitClose quickly.
                    safeClose("exception")

                    // Best-effort engine/session cleanup.
                    SLM.cancel(model)
                    SLM.resetSession(model)

                    // Cancel the producer coroutine to propagate the failure upstream.
                    cancel(CancellationException("SLM.runInference threw", t))
                }

                awaitClose {
                    // Tear down any pending tasks bound to this flow.
                    anchorScope.cancel(CancellationException("callbackFlow closed"))

                    val finished = seenFinished.get()
                    val cleaned  = seenOnClean.get()

                    fun waitCleanOrIdle(tag: String) {
                        val deadline = android.os.SystemClock.elapsedRealtime() + CLEAN_WAIT_MS
                        var loops = 0
                        // One initial sleep helps stabilize very-short tail races on some devices.
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
                        // Normal: onClean observed → just give the engine a moment to settle idle.
                        cleaned -> {
                            Log.d(TAG, "awaitClose: onClean observed → wait for idle then release")
                            waitCleanOrIdle("cleaned")
                        }

                        // Abnormal: still busy. Try cancel(), then if finished & idle but no onClean, reset session.
                        isBusyNow() -> {
                            runCatching {
                                Log.d(TAG, "awaitClose: engine BUSY → cancel()")
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

                        // Finished flag but never got onClean; if idle, do a cautious reset.
                        finished -> {
                            runCatching {
                                Log.d(TAG, "awaitClose: finished(no onClean) & idle → resetSession()")
                                SLM.resetSession(model)
                            }.onFailure { Log.w(TAG, "resetSession() failed: ${it.message}") }
                        }

                        // Early-close path: nothing observed; if busy, try a best-effort cancel.
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
            // BUFFERED is usually enough for token streams; can switch to UNLIMITED if needed.
            .buffer(Channel.BUFFERED)
            // Offload callbacks to IO to avoid blocking the caller context.
            .flowOn(Dispatchers.IO)

    // ------------------------------ Utilities ------------------------------

    /** Normalize line breaks to '\n' and trim trailing newlines. */
    private fun String.normalize(): String =
        this.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')

    /** Join non-blank parts with single '\n', avoiding duplicate empty lines and trailing newline. */
    private fun compactJoin(vararg parts: String): String {
        val list = buildList {
            parts.forEach { p ->
                val t = p.normalize()
                if (t.isNotBlank()) add(t)
            }
        }
        return list.joinToString("\n")
    }
}
