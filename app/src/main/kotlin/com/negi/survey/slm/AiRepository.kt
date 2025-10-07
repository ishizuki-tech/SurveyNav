// file: app/src/main/java/com/negi/survey/slm/SlmDirectRepository.kt
package com.negi.survey.slm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** Streaming リポジトリの契約 */
interface Repository {
    suspend fun request(prompt: String): Flow<String>
}

/** SLM バックエンド直結の実装 */
class SlmDirectRepository(
    private val appContext: Context,
    private val model: Model
) : Repository {

    companion object {
        private const val TAG = "SlmDirectRepository"

        // 会話マーカー
        private const val USER_TURN_PREFIX = "<start_of_turn>user"
        private const val MODEL_TURN_PREFIX = "<start_of_turn>model"
        private const val TURN_END = "<end_of_turn>"
        private const val EMPTY_JSON_INSTRUCTION = "Respond with an empty JSON object: {}"

        // プロンプトの共通ヘッダ（必要なら短く）
        private const val PREAMBLE: String =
            "You are a well-known English survey expert. Read the Question and the Answer."
        private const val KEY_CONTRACT: String =
            "OUTPUT FORMAT:\n- In English.\n- Keys: \"analysis\", \"expected answer\", \"follow-up questions\" (Exactly 3 in an array), \"score\" (int 1–100)."
        private const val LENGTH_BUDGET: String =
            "LENGTH LIMITS:\n- analysis<=60 chars; each follow-up<=80; expected answer<=40."
        private const val SCORING_RULE: String =
            "Scoring rule: Judge ONLY content relevance/completeness/accuracy. Do NOT penalize style or formatting."
        private const val STRICT_OUTPUT: String =
            "STRICT OUTPUT (NO MARKDOWN):\n- RAW JSON only, ONE LINE.\n- Use COMPACT JSON (no spaces around ':' and ',').\n- No extra text.\n- Entire output<=512 chars."

        // 同時リクエストを 1 つにシリアライズ
        private val globalGate = Semaphore(1)

        // 初期化待ち
        private const val INIT_WAIT_TOTAL_MS = 5_000L
        private const val INIT_WAIT_STEP_MS = 15L

        // 終了待ち（cancel 後や idle 化待ち）
        private const val CLEAN_WAIT_MS = 5_000L
        private const val CLEAN_STEP_MS = 500L

        // --- ウォッチドッグ（デフォルト定数 + ランタイム上書き可） ---
        private const val FINISH_WATCHDOG_DEFAULT_MS = 3_000L
        private const val FINISH_WATCHDOG_STEP_MS = 100L
        private const val FINISH_IDLE_GRACE_DEFAULT_MS = 250L

        // System Property（例: -Dslm.finish.watchdog.ms=6000）で上書き可
        private val FINISH_WATCHDOG_MS: Long by lazy {
            java.lang.System.getProperty("slm.finish.watchdog.ms")?.toLongOrNull()
                ?: FINISH_WATCHDOG_DEFAULT_MS
        }
        private val FINISH_IDLE_GRACE_MS: Long by lazy {
            java.lang.System.getProperty("slm.finish.idle.grace.ms")?.toLongOrNull()
                ?: FINISH_IDLE_GRACE_DEFAULT_MS
        }
    }

    /** 入力プロンプトを最終形へ整形（共通ブロックを注入） */
    fun buildPrompt(userPrompt: String): String {
        val effective = if (userPrompt.isBlank()) EMPTY_JSON_INSTRUCTION else userPrompt.trimIndent()
        val finalPrompt = """
$USER_TURN_PREFIX
$PREAMBLE

$effective

$KEY_CONTRACT
$LENGTH_BUDGET
$SCORING_RULE
$STRICT_OUTPUT
$TURN_END
$MODEL_TURN_PREFIX
""".trimIndent()
        Log.d(TAG, "buildPrompt: in.len=${userPrompt.length}, out.len=${finalPrompt.length}")
        return finalPrompt
    }

    /** SLM の初期化 */
    private suspend fun ensureInitialized() {
        if (model.instance != null) return
        val err = suspendCancellableCoroutine<String?> { cont ->
            try {
                SLM.initialize(appContext, model) { e -> if (cont.isActive) cont.resume(e) }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
        if (!err.isNullOrEmpty()) throw IllegalStateException("SLM.initialize error: $err")
        val ok = waitUntil(INIT_WAIT_TOTAL_MS, INIT_WAIT_STEP_MS) { model.instance != null }
        check(ok) { "SLM.initialize: model.instance was not set within ${INIT_WAIT_TOTAL_MS}ms" }
    }

    /** ポーリングヘルパ（suspend） */
    private suspend fun waitUntil(totalMs: Long, stepMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + totalMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            delay(stepMs)
        }
        return false
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun request(prompt: String): Flow<String> =
        callbackFlow {
            val out = this

            globalGate.withPermit {
                ensureInitialized()
                Log.d(TAG, "SLM request start: model='${model.name}', prompt.len=${prompt.length}")

                // ウォッチドッグ等のアンカー
                val anchorScope = CoroutineScope(coroutineContext + SupervisorJob())

                // 状態フラグ
                val closed = AtomicBoolean(false)
                val seenFinished = AtomicBoolean(false)
                val seenOnClean = AtomicBoolean(false)

                fun isBusyNow(): Boolean =
                    runCatching { SLM.isBusy(model) }.getOrElse { true }

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
                            // 最終チャンクが finished と同着で来ても取りこぼさない
                            if (partial.isNotEmpty() && !out.isClosedForSend) {
                                out.trySend(partial).onFailure { cause ->
                                    Log.w(TAG, "trySend(partial.len=${partial.length}) failed: ${cause?.message}")
                                }
                            }

                            if (finished) {
                                seenFinished.set(true)
                                Log.d(TAG, "SLM inference finished (model='${model.name}')")

                                // onClean だけでなく「idle になったら短い猶予でクローズ」も許容
                                anchorScope.launch {
                                    val deadline = android.os.SystemClock.elapsedRealtime() + FINISH_WATCHDOG_MS
                                    var idleSince = -1L
                                    while (android.os.SystemClock.elapsedRealtime() < deadline && !seenOnClean.get()) {
                                        if (closed.get()) return@launch

                                        val busy = isBusyNow()
                                        val now = android.os.SystemClock.elapsedRealtime()

                                        if (!busy) {
                                            if (idleSince < 0) idleSince = now
                                            val idleDur = now - idleSince
                                            if (idleDur >= FINISH_IDLE_GRACE_MS) {
                                                Log.d(TAG, "finish idle-grace (${idleDur}ms) → safeClose()")
                                                safeClose("finished-idle-no-onClean")
                                                return@launch
                                            }
                                        } else {
                                            idleSince = -1L // busy に戻ったら計測やり直し
                                        }

                                        delay(FINISH_WATCHDOG_STEP_MS)
                                    }

                                    if (!seenOnClean.get() && !closed.get()) {
                                        Log.w(TAG, "finish watchdog: onClean not observed within ${FINISH_WATCHDOG_MS}ms → safeClose()")
                                        safeClose("finish-watchdog-timeout")
                                    }
                                }
                            }
                        },
                        onClean = {
                            seenOnClean.set(true)
                            Log.d(TAG, "SLM onClean (model='${model.name}')")
                            safeClose("onClean")
                        }
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "SLM.runInference threw: ${t.message}", t)
                    cancel(CancellationException("SLM.runInference threw", t))
                }

                // 収集側のキャンセル／クローズ時の後始末
                awaitClose {
                    anchorScope.cancel(CancellationException("callbackFlow closed"))

                    val finished = seenFinished.get()
                    val cleaned = seenOnClean.get()

                    fun waitCleanOrIdle(tag: String) {
                        val deadline = android.os.SystemClock.elapsedRealtime() + CLEAN_WAIT_MS
                        var loops = 0
                        android.os.SystemClock.sleep(CLEAN_STEP_MS) // 1 回だけ初回スリープ
                        while (android.os.SystemClock.elapsedRealtime() < deadline) {
                            if (seenOnClean.get()) break
                            if (!isBusyNow()) break
                            android.os.SystemClock.sleep(CLEAN_STEP_MS)
                            loops++
                        }
                        Log.d(TAG, "awaitClose: waitCleanOrIdle[$tag] done (loops=$loops, cleaned=${seenOnClean.get()}, busy=${isBusyNow()})")
                    }

                    when {
                        // onClean を見た → 念のため idle 化を短く待つ
                        cleaned -> {
                            Log.d(TAG, "awaitClose: onClean observed → wait for idle then release")
                            waitCleanOrIdle("cleaned")
                        }

                        // まだ busy → cancel して idle/onClean を待つ
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

                        // finished 済み & idle & onClean なし → セッション再初期化
                        finished -> {
                            runCatching {
                                Log.d(TAG, "awaitClose: finished(no onClean) & idle → resetSession()")
                                SLM.resetSession(model)
                            }.onFailure { Log.w(TAG, "resetSession() failed: ${it.message}") }
                        }

                        // 途中キャンセル → 念のため busy なら cancel して待つ
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
            .buffer(Channel.BUFFERED)   // コールバックスレッドを詰まらせない
            .flowOn(Dispatchers.IO)     // 生成側（awaitClose 含む）は IO で
}
