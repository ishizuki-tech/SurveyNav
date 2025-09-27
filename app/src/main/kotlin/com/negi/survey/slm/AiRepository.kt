package com.negi.survey.slm

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Repository abstraction for AI scoring streaming.
 */
interface Repository {
    suspend fun request(prompt: String): Flow<String>
}

class MediaPipeRepository(private val appContext: Context) : Repository {

    private val model by lazy { InferenceModel.getInstance(appContext) }

    // ★ アプリ全体で 1 リクエストに直列化（テスト/本番どちらにも効く）
    companion object {
        private val globalGate = Semaphore(1)
    }

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

    override suspend fun request(prompt: String): Flow<String> = callbackFlow {
        // 1本ずつ
        globalGate.withPermit {
            val fullPrompt = buildString { appendLine(wrapWithTurns(prompt)) }
            val reqId = model.startRequest(fullPrompt)

            var finished = false

            val collectJob = launch {
                model.partialResults
                    .filter { it.requestId == reqId }
                    .collect { pr ->
                        trySend(pr.text)
                        if (pr.done) {
                            finished = true
                            close()
                        }
                    }
            }

            awaitClose {
                // コレクション停止時のクリーンアップ
                collectJob.cancel()
                // ★ 完了済みなら cancel しない（ここで刺すとネイティブが落ちやすい）
                if (!finished) runCatching { model.cancelRequest(reqId) }
            }
        }
    }
}
