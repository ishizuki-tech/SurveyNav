// app/src/main/java/com/negi/survey/vm/AiViewModel.kt
package com.negi.survey.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negi.survey.slm.FollowupExtractor
import com.negi.survey.slm.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

/**
 * ViewModel dedicated to AI-related operations.
 *
 * Responsibilities:
 * - Execute text evaluation via Repository
 * - Stream partial outputs to UI
 * - Extract and keep score / follow-up questions (top-3)
 * - Support cancel/reset lifecycle
 */
class AiViewModel(
    private val repo: Repository,
    private val timeout_ms: Long = 120_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "AiViewModel"
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _score = MutableStateFlow<Int?>(null)
    val score: StateFlow<Int?> = _score.asStateFlow()

    private val _stream = MutableStateFlow("")
    val stream: StateFlow<String> = _stream.asStateFlow()

    private val _raw = MutableStateFlow<String?>(null)
    val raw: StateFlow<String?> = _raw.asStateFlow()

    private val _followupQuestion = MutableStateFlow<String?>(null)
    val followupQuestion: StateFlow<String?> = _followupQuestion.asStateFlow()

    // 追加: 上位3件を保持
    private val _followups = MutableStateFlow<List<String>>(emptyList())
    val followups: StateFlow<List<String>> = _followups.asStateFlow()

    // 文字列で持つ（テスト側は .filterNotNull() で監視可能）
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var evalJob: Job? = null
    private val running = AtomicBoolean(false)

    val isRunning: Boolean
        get() = running.get()

    // ---------- Logging helpers ----------

    private fun sha256Hex(input: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { "%02x".format(it) }
    }.getOrElse { "sha256_error" }

    /**
     * Evaluate the given prompt. Returns score (0..100) or null.
     * - Streams partial text into [_stream]
     * - On completion (or timeout), sets [_raw], [_score], [_followups], [_followupQuestion]
     * - On timeout, tries to extract from partial buffer and sets error="timeout"
     */
    suspend fun evaluate(prompt: String, timeoutMs: Long = timeout_ms): Int? {

        if (prompt.isBlank()) {
            Log.i(TAG, "Evaluate: blank prompt -> reset states and return null")
            resetStates(keepError = false)
            return null
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "Evaluate: already running -> return current score=${_score.value}")
            return _score.value
        }

        _loading.value = true
        _score.value = null
        _stream.value = ""
        _followupQuestion.value = null
        _followups.value = emptyList()
        _raw.value = null
        _error.value = null

        val buf = StringBuilder()
        var chunkCount = 0
        var totalChars = 0

        val elapsed = measureTimeMillis {
            try {
                evalJob = viewModelScope.launch(ioDispatcher) {
                    var isTimeout = false

                    try {
                        // ---- collect with timeout ----
                        try {
                            withTimeout(timeoutMs) {
                                repo.request(prompt).collect { part ->
                                    if (part.isNotEmpty()) {
                                        chunkCount++
                                        buf.append(part)
                                        _stream.update { it + part }
                                        totalChars += part.length
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            // ※ ライブラリ差異で TimeoutCancellationException が無い環境でもOKにするため、
                            //    実体クラス名でタイムアウトを判定
                            val name = e.javaClass.name
                            val looksTimeout = name.endsWith("TimeoutCancellationException")
                                    || name.contains("Timeout", ignoreCase = true)
                            if (looksTimeout) {
                                isTimeout = true
                                Log.w(TAG, "evaluate: timeout after ${timeoutMs}ms (class=$name)")
                            } else {
                                _error.value = "cancelled"
                                Log.w(TAG, "evaluate: cancelled (class=$name)", e)
                                throw e // 明示キャンセルは外へ伝播
                            }
                        }

                        // ---- finalize with whatever we have (even on timeout) ----
                        val rawText = buf.toString().ifBlank { _stream.value }
                        if (rawText.isNotBlank()) {
                            // Debug logs
                            Log.w(TAG, "Evaluate[debug]: prompt.len=${prompt.length}, sha=${sha256Hex(prompt)}")
                            Log.w(TAG, "Evaluate[debug]: raw.len=${rawText.length}, chunks=$chunkCount, chars=$totalChars, sha=${sha256Hex(rawText)}")
                            Log.w(TAG, "Evaluate: prompt =\n$prompt")
                            Log.w(TAG, "Evaluate: rawText =\n$rawText")

                            val score = clampScore(FollowupExtractor.extractScore(rawText))
                            val top3 = FollowupExtractor.fromRaw(rawText, max = 3)
                            val question = top3.firstOrNull()

                            _raw.value = rawText
                            _score.value = score
                            _followups.value = top3
                            _followupQuestion.value = question

                            if (question != null) Log.w(TAG, "Followup[0]: \"${question.replace("\n", "\\n")}\"")
                            if (top3.size > 1) Log.w(TAG, "Followup[1..]: ${top3.drop(1)}")
                            if (score != null) Log.w(TAG, "Score: $score") else Log.w(TAG, "score: <null> (extraction failed)")
                        } else {
                            Log.w(TAG, "Evaluate: no output produced (stream & buffer empty)")
                        }

                        if (isTimeout) {
                            _error.value = "timeout"
                        }
                    } catch (e: CancellationException) {
                        // ここに来るのは「明示キャンセル」
                        _error.value = "cancelled"
                        Log.w(TAG, "evaluate: propagate cancellation", e)
                        throw e
                    } catch (e: Throwable) {
                        _error.value = e.message ?: "error"
                        Log.e(TAG, "evaluate: error", e)
                    }
                }

                // Wait for the launched job (Job.join は例外を再throwしない)
                evalJob?.join()
            } finally {
                _loading.value = false
                running.set(false)
                try {
                    evalJob?.cancel()
                } catch (_: Throwable) {}
                evalJob = null
            }
        }

        Log.d(TAG, "evaluate: finished in ${elapsed}ms, score=${_score.value}, err=${_error.value}, chunks=$chunkCount, chars=$totalChars")
        return _score.value
    }

    /**
     * Fire-and-forget evaluate
     */
    fun evaluateAsync(prompt: String, timeoutMs: Long = timeout_ms): Job =
        viewModelScope.launch {
            val result = evaluate(prompt, timeoutMs)
            Log.i(TAG, "evaluateAsync: finished score=$result, error=${_error.value}")
        }

    fun cancel() {
        Log.i(TAG, "cancel: invoked (isRunning=${running.get()}, loading=${_loading.value})")
        try {
            evalJob?.cancel()
            Log.d(TAG, "cancel: evalJob.cancel() requested")
        } catch (t: Throwable) {
            Log.w(TAG, "cancel: exception during cancel (ignored)", t)
        }
        evalJob = null
        running.set(false)
        _loading.value = false
    }

    fun resetStates(keepError: Boolean = false) {
        Log.i(TAG, "resetStates: keepError=$keepError (before) score=${_score.value} err=${_error.value}")
        cancel()
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        _followups.value = emptyList()
        if (!keepError) _error.value = null
        Log.i(TAG, "resetStates: done (score=${_score.value}, err=${_error.value})")
    }

    override fun onCleared() {
        Log.i(TAG, "onCleared: ViewModel is being cleared -> cancel()")
        super.onCleared()
        cancel()
    }

    private fun clampScore(s: Int?): Int? {
        val clamped = s?.coerceIn(0, 100)
        if (s != clamped) {
            Log.d(TAG, "clampScore: input=$s -> clamped=$clamped")
        }
        return clamped
    }
}
