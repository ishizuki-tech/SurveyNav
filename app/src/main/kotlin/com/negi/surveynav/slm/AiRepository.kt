package com.negi.surveynav.slm

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Repository abstraction for AI scoring streaming.
 */
interface Repository {
    suspend fun request(prompt: String): Flow<String>
}

/**
 * MediaPipe-backed implementation of Repository.
 *
 * This class wraps an InferenceModel instance and exposes a streaming Flow of
 * partial text results for a single request. The flow completes when the model
 * signals the `done` flag on a partial result.
 */
class MediaPipeRepository(private val appContext: Context) : Repository {

    // Lazily obtain the shared inference model instance
    private val model by lazy { InferenceModel.getInstance(appContext) }

    private fun wrapWithTurns(body: String): String {
        return """
            <start_of_turn>user
            ${body.trimIndent()}
            STRICT OUTPUT (NO MARKDOWN):
            - Entire output <512 chars.
            - Return RAW JSON only. DO NOT use Markdown, code fences, or backticks.
            - Output must be ONE LINE; first char '{', last char '}'.
            - No explanations or extra text.
            <end_of_turn>
            <start_of_turn>model
        """.trimIndent()
    }



    /**
     * Start a scoring request and stream partial results as a Flow<String>.
     *
     * Note: the method remains `suspend` to preserve the original API shape.
     * The returned Flow is cold; collection will trigger streaming.
     */
    override suspend fun request(prompt: String): Flow<String> = callbackFlow {
        // Avoid shadowing the parameter name
        val promptText = prompt
        val fullPrompt = buildString { appendLine(wrapWithTurns(promptText)) }

        // Start request on the model and subscribe to partialResults matching reqId
        val reqId = model.startRequest(fullPrompt)

        // Launch a coroutine to collect partial results and forward to the callbackFlow
        val collectJob = launch {
            model.partialResults
                .filter { it.requestId == reqId }
                .collect { pr ->
                    // Try to emit the partial text; if the channel is closed, ignore the result
                    val sendResult = trySend(pr.text)
                    if (!sendResult.isSuccess) {
                        // Optionally log or handle backpressure / failure.
                        // e.g. Log.w("MediaPipeRepository", "Failed to send partial result for $reqId")
                    }

                    // If this partial result indicates completion, close the flow
                    if (pr.done) {
                        // close() will terminate the callbackFlow and propagate completion to collectors
                        close()
                    }
                }
        }

        // Clean-up when the downstream collector cancels or stops collecting
        awaitClose {
            // Cancel the model request and the internal collecting job
            try {
                model.cancelRequest(reqId)
            } catch (_: Throwable) {
                // swallow cancellation exceptions from model.cancelRequest to avoid leaking
            }
            collectJob.cancel()
        }
    }
}
