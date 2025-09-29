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
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Evaluates the given prompt and returns score (or null) after completion.
     */
    suspend fun evaluate(prompt: String, timeoutMs: Long = timeout_ms): Int? {
        if (prompt.isBlank()) {
            resetStates(keepError = false)
            return null
        }
        if (!running.compareAndSet(false, true)) {
            return _score.value // Already running
        }

        _loading.value = true
        _score.value = null
        _stream.value = ""
        _followupQuestion.value = null
        _raw.value = null
        _error.value = null

        val buf = StringBuilder()

        try {
            evalJob = viewModelScope.launch(ioDispatcher) {
                try {
                    withTimeout(timeoutMs) {
                        repo.request(prompt).collect { part ->
                            buf.append(part)
                            _stream.update { it + part }
                        }
                    }

                    val rawText = buf.toString().ifBlank { _stream.value }
                    val s = clampScore(FollowupExtractor.extractScore(rawText))
                    val q = FollowupExtractor.extractFollowupQuestion(rawText)

                    _raw.value = rawText
                    _score.value = s
                    _followupQuestion.value = q

                    Log.d("AiViewModel", "Evaluation completed: score=$s, followup=${q != null}")
                } catch (e: TimeoutCancellationException) {
                    _error.value = "timeout"
                    Log.w("AiViewModel", "Evaluation timeout", e)
                } catch (e: CancellationException) {
                    _error.value = "cancelled"
                    Log.w("AiViewModel", "Evaluation cancelled", e)
                    throw e
                } catch (e: Throwable) {
                    _error.value = e.message ?: "error"
                    Log.e("AiViewModel", "Evaluation error", e)
                }
            }

            evalJob?.join()
            return _score.value
        } finally {
            _loading.value = false
            running.set(false)
            evalJob?.cancel()
            evalJob = null
        }
    }

    /**
     * Fire-and-forget evaluate
     */
    fun evaluateAsync(prompt: String, timeoutMs: Long = timeout_ms): Job =
        viewModelScope.launch {
            evaluate(prompt, timeoutMs)
        }

    fun cancel() {
        try {
            evalJob?.cancel()
        } catch (_: Throwable) { /* no-op */ }
        evalJob = null
        running.set(false)
        _loading.value = false
    }

    fun resetStates(keepError: Boolean = false) {
        cancel()
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        if (!keepError) _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        cancel()
    }

    private fun clampScore(s: Int?): Int? = s?.coerceIn(0, 100)
}
