package com.negi.survey.slm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Abstraction for a text-only, streaming AI scoring endpoint.
 *
 * Contract:
 * - [request] returns a cold [Flow] that emits streaming text chunks from the model.
 * - The returned Flow completes normally when the underlying generation completes.
 * - Cancellation of the collector cancels the in-flight model request.
 */
interface Repository {
    /**
     * Send a single prompt and receive streaming text chunks.
     *
     * @param prompt User-provided prompt text (may be blank).
     * @return A cold [Flow] of text chunks; collect to start the request.
     */
    suspend fun request(prompt: String): Flow<String>
}

/**
 * Repository implementation backed by MediaPipe (text-only).
 *
 * Responsibilities:
 * - Ensures the underlying [InferenceModel] is initialized before each request.
 * - Serializes requests process-wide via a global [Semaphore] to enforce single concurrency.
 * - Wraps the prompt with role-turn markers and strict-output guardrails before dispatch.
 * - Bridges the model's [InferenceModel.partialResults] into a consumer-friendly [Flow].
 *
 * Threading/Lifecycle:
 * - Uses a cold [callbackFlow] per request; collection starts the request and sets up
 *   a child coroutine to forward partial updates. Cancelling the collector cancels the request.
 * - Holds ApplicationContext via [InferenceModel.getInstance] to avoid leaks.
 */
class MediaPipeRepository(
    private val appContext: Context
) : Repository {

    // Lazily obtain the singleton model (stores ApplicationContext internally).
    private val model by lazy { InferenceModel.getInstance(appContext) }

    companion object {
        private const val TAG = "MediaPipeRepository"

        // Single-flight gate across all repository instances to avoid overlapping runs.
        private val globalGate = Semaphore(1)

        // Strict-output guardrails appended to the user's turn.
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
    }

    /**
     * Build the strict, role-wrapped prompt expected by the model.
     *
     * @param body Raw user prompt body (already trimmed/normalized by caller if needed).
     * @return The turn-annotated prompt string.
     */
    private fun wrapWithTurns(body: String): String = """
        $USER_TURN_PREFIX
        ${body.trimIndent()}
        ${STRICT_RULES.trimIndent()}
        $TURN_END
        $MODEL_TURN_PREFIX
    """.trimIndent()
    // Explanation: The model is instructed to produce one-line raw JSON under tight constraints.

    /**
     * Start a single streaming request and return a cold [Flow] of text chunks.
     *
     * Behavior:
     * - Ensures the model is initialized (non-blocking callers receive a stream error if init fails).
     * - Serializes requests via [globalGate] so only one generation runs at a time.
     * - Forwards non-empty partials downstream; completes the flow on terminal signal.
     * - On collector cancellation, forwards a cancel to the model and closes the channel.
     *
     * @param prompt User prompt text (blank is allowed; an empty-JSON instruction will be used).
     */
    override suspend fun request(prompt: String): Flow<String> = callbackFlow {
        globalGate.withPermit {
            // Ensure model is ready; if init fails, emit an error JSON and close the stream.
            runCatching { model.ensureLoaded() }.onFailure {
                Log.e(TAG, "ensureLoaded failed: ${it.message}", it)
                trySend("""{"error":"${it.message ?: "model init failed"}"}""")
                close()
                return@withPermit
            }

            val effectiveBody = if (prompt.isBlank()) EMPTY_JSON_INSTRUCTION else prompt
            val fullPrompt = wrapWithTurns(effectiveBody)
            val reqId = model.startRequest(fullPrompt)
            Log.d(TAG, "request started reqId=$reqId len=${fullPrompt.length}")


            var finished = false

            // Bridge model partials into this callbackFlow; only forward chunks for this reqId.
            val collectJob = launch {
                model.partialResults
                    .filter { it.requestId == reqId }
                    .collect { pr ->
                        if (pr.text.isNotEmpty()) {
                            this@callbackFlow.send(pr.text)
                            // Explanation: Forward only non-empty deltas to reduce noise.
                        }
                        if (pr.done) {
                            finished = true
                            Log.d(TAG, "request done reqId=$reqId")
                            this@callbackFlow.close()
                        }
                    }
            }

            // Channel close/cancel handler: cancel collection and propagate cancel to model if needed.
            awaitClose {
                Log.d(TAG, "awaitClose reqId=$reqId finished=$finished -> cancel=${!finished}")
                collectJob.cancel()
                if (!finished) {
                    runCatching { model.cancelRequest(reqId) }
                    // Explanation: Safe best-effort cancel; SLM handles edge cases internally.
                }
            }
        }
    }
}
