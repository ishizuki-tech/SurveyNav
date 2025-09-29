package com.negi.survey.slm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Contract interface for any class that can provide streaming inference responses.
 */
interface Repository {
    /**
     * Returns a Flow of streaming text chunks in response to a user prompt.
     *
     * @param prompt The user-provided input string (can be blank)
     * @return Flow<String> emitting chunks of the response as they arrive
     */
    suspend fun request(prompt: String): Flow<String>
}

/**
 * Direct implementation of Repository using the Small Language Model (SLM).
 *
 * This class handles:
 * - Lazy initialization of the SLM model
 * - Serializing requests using a Semaphore to avoid race conditions
 * - Prompt formatting with required markers and instructions
 * - Wrapping callback-style inference into a Coroutine Flow
 */
class SlmDirectRepository(
    private val appContext: Context,
    private val model: Model
) : Repository {

    companion object {
        private const val TAG = "SlmDirectRepository"

        // Output rule for strict JSON: No markdown, code blocks, or extras
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

        // Global semaphore to ensure only one concurrent request across all instances
        private val globalGate = Semaphore(1)
    }

    /**
     * Formats a user prompt into the structured input expected by the model,
     * including turn labels and strict output rules.
     */
    private fun wrapWithTurns(body: String): String = """
        $USER_TURN_PREFIX
        ${body.trimIndent()}
        ${STRICT_RULES.trimIndent()}
        $TURN_END
        $MODEL_TURN_PREFIX
    """.trimIndent()

    /**
     * Ensures that the model is initialized before usage.
     * Runs on a background thread and returns an error string if failed.
     */
    private suspend fun ensureInitialized(): String = withContext(Dispatchers.Default) {
        var err = ""
        SLM.initialize(appContext, model) { msg ->
            err = msg
        }
        err
    }

    /**
     * Sends the given prompt to the SLM model and returns a Flow emitting partial results.
     */
    override suspend fun request(prompt: String): Flow<String> = callbackFlow {
        globalGate.withPermit {
            val initErr = ensureInitialized()
            if (initErr.isNotEmpty()) {
                trySend("""{"error":"$initErr"}""")
                close()
                return@withPermit
            }

            val effectivePrompt = if (prompt.isBlank()) EMPTY_JSON_INSTRUCTION else prompt
            val fullPrompt = wrapWithTurns(effectivePrompt)

            Log.d(TAG, "SLM request start len=${fullPrompt.length}")

            val channel = this // Explicit capture of callbackFlow's channel

            // Start inference asynchronously and stream results into the channel
            val inferenceJob = launch {
                SLM.runInference(
                    model = model,
                    input = fullPrompt,
                    listener = { partial, done ->
                        if (partial.isNotEmpty()) {
                            channel.trySend(partial).onFailure {
                                Log.w(TAG, "trySend failed: ${it?.message}")
                            }
                        }
                        if (done) {
                            Log.d(TAG, "SLM inference done.")
                            channel.close() // Close the flow when done
                        }
                    },
                    onClean = {
                        Log.d(TAG, "SLM cleanup completed for model='${model.name}'")
                    }
                )
            }

            // Flow cancellation / completion
            awaitClose {
                inferenceJob.cancel()
                SLM.cancel(model)
                Log.d(TAG, "callbackFlow closed by awaitClose")
            }
        }
    }
}