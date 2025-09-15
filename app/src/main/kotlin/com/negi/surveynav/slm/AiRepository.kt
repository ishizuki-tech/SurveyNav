package com.negi.surveynav.slm

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.random.Random

/* ============================================================
 * Repository 抽象：AI 評価 I/F
 * ============================================================ */
interface Repository {
    suspend fun scoreStreaming(prompt: String): Flow<String>
}

class FakeRepository(
    private val emitChunks: Int = 6,
    private val perChunkDelayMs: Long = 30L,
    private val simulateError: Boolean = false,
    private val forceScore: Int? = null, // nullならランダム80±10
) : Repository {

    override suspend fun scoreStreaming(prompt: String): Flow<String> = flow {
        val chunks = emitChunks.coerceAtLeast(2)
        val score = (forceScore ?: (80 + Random.nextInt(-10, 11))).coerceIn(0, 100)
        for (i in 1..chunks) {
            delay(perChunkDelayMs)
            emit("chunk[$i] ")
            if (simulateError && i == chunks / 2) {
                error("Simulated error")
            }
        }
        emit("""{"overall_score":$score} """)
        emit("Score: $score / 100")
    }
}

/* --- 実機推論（MediaPipe GenAI + InferenceModel のラッパー） --- */
class MediaPipeRepository(
    private val appContext: Context,
    repoScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : Repository {

    private val model by lazy { InferenceModel.getInstance(appContext) }

    init {
        repoScope.launch {
            try {
                model.ensureLoaded()
            } catch (e: Throwable) {
            }
        }
    }

    private fun wrapWithTurns(body: String): String {
        return """
            <start_of_turn>user
            ${body.trimIndent()}
            <end_of_turn>
            <start_of_turn>model
        """.trimIndent()
    }

    override suspend fun scoreStreaming(prompt: String): Flow<String> = callbackFlow {

        //model.ensureLoaded()

        val prompt = buildString { appendLine(wrapWithTurns(prompt)) }

        val reqId = model.startRequest(prompt)

        val job = launch {
            model.partialResults
                .filter { it.requestId == reqId }
                .collect { pr ->
                    trySend(pr.text) // 文字列の差分を UI へ
                    if (pr.done) close() // ここで Flow を完了
                }
        }

        awaitClose {
            model.cancelRequest(reqId)
            job.cancel()
        }
    }
}

