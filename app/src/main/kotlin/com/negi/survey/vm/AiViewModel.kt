// file: app/src/main/java/com/negi/survey/vm/AiViewModel.kt
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

/**
 * AiViewModel
 * ---------------------------------------------------------------
 * Handles SLM-driven evaluation and streaming inference.
 * Responsibilities:
 *  - Stream partial output from Repository
 *  - Parse score and follow-up questions
 *  - Manage init/loading/error state for Compose UI
 *  - Provide safe cancel/reset lifecycle
 */
class AiViewModel(
    private val repo: Repository,
    private val timeoutMs: Long = 120_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "AiViewModel"
    }

    // ============================================================
    // UI State Container
    // ============================================================
    data class UiState(
        val isInitializing: Boolean = false,
        val initialized: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun setInitializing(v: Boolean) = _ui.update { it.copy(isInitializing = v) }
    fun setInitialized(v: Boolean) = _ui.update { it.copy(isInitializing = false, initialized = v) }

    // ============================================================
    // Internal States
    // ============================================================
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

    private val _followups = MutableStateFlow<List<String>>(emptyList())
    val followups: StateFlow<List<String>> = _followups.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var evalJob: Job? = null
    private val running = AtomicBoolean(false)

    val isRunning: Boolean
        get() = running.get()

    // ============================================================
    // Evaluate (streaming)
    // ============================================================
    suspend fun evaluate(prompt: String, timeout: Long = timeoutMs): Int? {
        if (prompt.isBlank()) {
            Log.i(TAG, "evaluate: blank prompt -> reset states")
            resetStates(keepError = false)
            return null
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluate: already running -> reuse current score=${_score.value}")
            return _score.value
        }

        _loading.value = true
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        _followups.value = emptyList()
        _error.value = null

        val buf = StringBuilder()
        var chunks = 0
        var totalChars = 0

        val elapsed = measureTimeMillis {
            try {
                evalJob = viewModelScope.launch(ioDispatcher) {
                    var timedOut = false

                    try {
                        withTimeout(timeout) {
                            val finalPrompt = repo.buildPrompt(prompt)
                            repo.request(finalPrompt).collect { part ->
                                if (part.isNotEmpty()) {
                                    chunks++
                                    totalChars += part.length
                                    buf.append(part)
                                    _stream.update { it + part }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        val name = e.javaClass.name
                        val looksTimeout = name.endsWith("TimeoutCancellationException")
                                || name.contains("Timeout", ignoreCase = true)
                        if (looksTimeout) {
                            timedOut = true
                            Log.w(TAG, "evaluate: timeout after ${timeout}ms ($name)")
                        } else {
                            _error.value = "cancelled"
                            Log.w(TAG, "evaluate: cancelled by user ($name)")
                            throw e
                        }
                    }

                    val rawText = buf.toString().ifBlank { _stream.value }
                    if (rawText.isNotBlank()) {
                        logSummary(prompt, rawText, chunks, totalChars)

                        val score = clampScore(FollowupExtractor.extractScore(rawText))
                        val top3 = FollowupExtractor.fromRaw(rawText, max = 3)
                        val q = top3.firstOrNull()

                        _raw.value = rawText
                        _score.value = score
                        _followups.value = top3
                        _followupQuestion.value = q

                        Log.i(TAG, "followups=${top3.size}, score=$score, timeout=$timedOut")
                    } else {
                        Log.w(TAG, "evaluate: empty rawText")
                    }

                    if (timedOut) _error.value = "timeout"
                }

                evalJob?.join()
            } finally {
                running.set(false)
                _loading.value = false
                evalJob?.cancel()
                evalJob = null
            }
        }

        Log.d(TAG, "evaluate: finished in ${elapsed}ms, score=${_score.value}, chunks=$chunks, chars=$totalChars, err=${_error.value}")
        return _score.value
    }

    // ============================================================
    // Fire-and-forget wrapper
    // ============================================================
    fun evaluateAsync(prompt: String, timeout: Long = timeoutMs): Job =
        viewModelScope.launch {
            val result = evaluate(prompt, timeout)
            Log.i(TAG, "evaluateAsync: done, score=$result, error=${_error.value}")
        }

    // ============================================================
    // Cancel / Reset
    // ============================================================
    fun cancel() {
        Log.i(TAG, "cancel: requested (running=${running.get()}, loading=${_loading.value})")
        try {
            evalJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel: exception ignored", t)
        } finally {
            running.set(false)
            _loading.value = false
        }
    }

    fun resetStates(keepError: Boolean = false) {
        Log.d(TAG, "resetStates(keepError=$keepError) start")
        cancel()
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        _followups.value = emptyList()
        if (!keepError) _error.value = null
        Log.d(TAG, "resetStates done (score=${_score.value}, err=${_error.value})")
    }

    override fun onCleared() {
        Log.i(TAG, "onCleared: cancelling job")
        cancel()
        super.onCleared()
    }

    // ============================================================
    // Helpers
    // ============================================================
    private fun clampScore(s: Int?): Int? {
        val c = s?.coerceIn(0, 100)
        if (s != c) Log.d(TAG, "clampScore: $s -> $c")
        return c
    }

    private fun sha256Hex(text: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrElse { "sha256_error" }

    private fun logSummary(prompt: String, raw: String, chunks: Int, chars: Int) {
        Log.d(TAG, "---- AI Eval Summary ----")
        Log.d(TAG, "Prompt.len=${prompt.length}, SHA=${sha256Hex(prompt)}")
        Log.d(TAG, "Raw.len=${raw.length}, chunks=$chunks, chars=$chars, SHA=${sha256Hex(raw)}")
        Log.v(TAG, "Prompt:\n$prompt")
        Log.v(TAG, "Raw:\n$raw")
    }
}
