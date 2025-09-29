package com.negi.survey.vm

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
    /** True while an evaluation is running. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _score = MutableStateFlow<Int?>(null)
    /** Extracted score in [0,100], or null if not available. */
    val score: StateFlow<Int?> = _score.asStateFlow()

    private val _stream = MutableStateFlow("")
    /** Streaming text from the model (incrementally appended). */
    val stream: StateFlow<String> = _stream.asStateFlow()

    private val _raw = MutableStateFlow<String?>(null)
    /** Full raw model output after completion. */
    val raw: StateFlow<String?> = _raw.asStateFlow()

    private val _followupQuestion = MutableStateFlow<String?>(null)
    /** Extracted follow-up question, if any. */
    val followupQuestion: StateFlow<String?> = _followupQuestion.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Non-null when an error/timeout/cancel happens (UI-friendly string). */
    val error: StateFlow<String?> = _error.asStateFlow()

    private var evalJob: Job? = null
    private val running = AtomicBoolean(false)

    /** Whether an evaluation is currently running. */
    val isRunning: Boolean
        get() = running.get()

    /**
     * Suspends until evaluation completes and returns the score (or null on failure).
     * If another evaluation is already running, returns the current score immediately.
     */
    suspend fun evaluate(prompt: String, timeoutMs: Long = timeout_ms): Int? {
        if (prompt.isBlank()) {
            resetStates(keepError = false)
            return null
        }
        if (!running.compareAndSet(false, true)) {
            // Already running → return current value to avoid concurrent work.
            return _score.value
        }

        // Initialize UI states for a fresh run.
        _loading.value = true
        _score.value = null
        _stream.value = ""
        _followupQuestion.value = null
        _raw.value = null
        _error.value = null

        val buf = StringBuilder()

        try {
            // Launch the core job so that external cancel() can interrupt it.
            evalJob = viewModelScope.launch(ioDispatcher) {
                try {
                    withTimeout(timeoutMs) {
                        // Collect streaming parts and append to StateFlow.
                        repo.request(prompt).collect { part ->
                            buf.append(part)
                            // It's safe to update StateFlow from a background dispatcher.
                            _stream.update { it + part }
                        }
                    }

                    // After successful collection, extract final values.
                    val rawText = buf.toString()
                    val s = clampScore(FollowupExtractor.extractScore(rawText))
                    val q = FollowupExtractor.extractFollowupQuestion(rawText)

                    _raw.value = rawText
                    _score.value = s
                    _followupQuestion.value = q
                } catch (e: TimeoutCancellationException) {
                    _error.value = "timeout"
                } catch (e: CancellationException) {
                    // Propagate cancellation while marking an error for UI.
                    _error.value = "cancelled"
                    throw e
                } catch (e: Throwable) {
                    _error.value = e.message ?: "error"
                }
            }

            // Wait for completion and then return the (possibly null) score.
            evalJob?.join()
            return _score.value
        } finally {
            _loading.value = false
            running.set(false)
            try {
                // Ensure the job is cleaned up even if already completed.
                evalJob?.cancel()
            } catch (_: Throwable) { /* no-op */ }
            evalJob = null
        }
    }

    /**
     * Fire-and-forget version of evaluate().
     * Returns a Job immediately; observe StateFlows for progress/results.
     */
    fun evaluateAsync(prompt: String, timeoutMs: Long = timeout_ms): Job =
        viewModelScope.launch {
            evaluate(prompt, timeoutMs)
        }

    /** Cancels the running evaluation, if any, and clears the running/loading flags. */
    fun cancel() {
        try {
            evalJob?.cancel()
        } catch (_: Throwable) { /* no-op */ }
        evalJob = null
        running.set(false)
        _loading.value = false
    }

    /** Resets only the UI states; does not perform any network/model call. */
    fun resetStates(keepError: Boolean = false) {
        cancel()
        _loading.value = false
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

    /** Clamp scores to the [0, 100] range to stabilize UI logic. */
    private fun clampScore(s: Int?): Int? = s?.coerceIn(0, 100)
}
