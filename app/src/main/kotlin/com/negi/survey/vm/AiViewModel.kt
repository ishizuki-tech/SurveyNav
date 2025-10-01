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
import kotlinx.coroutines.TimeoutCancellationException
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
 * - Extract and keep score/follow-up question
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
     * Evaluates the given prompt and returns score (or null) after completion.
     */
    suspend fun evaluate(prompt: String, timeoutMs: Long = timeout_ms): Int? {

        if (prompt.isBlank()) {
            Log.i(TAG, "Evaluate: blank prompt -> reset states and return null")
            resetStates(keepError = false)
            return null
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "Evaluate: already running -> return current score=${_score.value}")
            return _score.value // Already running
        }

        _loading.value = true
        _score.value = null
        _stream.value = ""
        _followupQuestion.value = null
        _raw.value = null
        _error.value = null

        val buf = StringBuilder()
        var chunkCount = 0
        var totalChars = 0

        val elapsed = measureTimeMillis {
            try {
                evalJob = viewModelScope.launch(ioDispatcher) {
                    try {
                        withTimeout(timeoutMs) {
                            repo.request(prompt).collect { part ->
                                chunkCount++
                                buf.append(part)
                                _stream.update { it + part }
                                totalChars += part.length
                            }
                        }

                        val rawText = buf.toString().ifBlank { _stream.value }

                        // 最終フルバッファログ
                        Log.w(TAG, "Evaluate: prompt =\n$prompt")
                        Log.w(TAG, "Evaluate: rawText =\n$rawText")

                        val s = clampScore(FollowupExtractor.extractScore(rawText))
                        val q = FollowupExtractor.extractFollowupQuestion(rawText)

                        _raw.value = rawText
                        _score.value = s
                        _followupQuestion.value = q

                        if (q != null) Log.w(TAG, "Followup: \"${q.replace("\n", "\\n")}\"") else Log.d(TAG, "followup: <none>")
                        if (s != null) Log.w(TAG, "Score: $s") else Log.w(TAG, "score: <null> (extraction failed)")

                    } catch (e: TimeoutCancellationException) {
                        _error.value = "timeout"
                        Log.w(TAG, "evaluate: timeout after ${timeoutMs}ms", e)
                    } catch (e: CancellationException) {
                        _error.value = "cancelled"
                        Log.w(TAG, "evaluate: cancelled", e)
                        throw e
                    } catch (e: Throwable) {
                        _error.value = e.message ?: "error"
                        Log.e(TAG, "evaluate: error", e)
                    }
                }
                evalJob?.join()
            } finally {
                _loading.value = false
                running.set(false)
                evalJob?.cancel()
                evalJob = null
            }
        }

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
