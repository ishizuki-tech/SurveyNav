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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

/**
 * ViewModel dedicated to AI-related operations and chat persistence.
 *
 * Responsibilities:
 * - Build prompt and evaluate text via Repository
 * - Stream partial outputs to UI
 * - Extract and keep score / follow-up questions (top-3)
 * - Persist chat history per nodeId
 * - Robust timeout/cancel handling
 */
class AiViewModel(
    private val repo: Repository,
    private val timeout_ms: Long = 120_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "AiViewModel"
        private const val DEBUG_LOGS = true
    }

    // ───────────────────────── UI state ─────────────────────────

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

    // Optional push-style events for UI
    private val _events = MutableSharedFlow<AiEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<AiEvent> = _events.asSharedFlow()

    // ─────────────────────── Chat persistence ───────────────────────

    enum class ChatSender { USER, AI }

    data class ChatMsgVm(
        val id: String,
        val sender: ChatSender,
        val text: String? = null,   // plain text bubble
        val json: String? = null,   // JSON bubble (final result)
        val isTyping: Boolean = false
    )

    private val _chats = MutableStateFlow<Map<String, List<ChatMsgVm>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatMsgVm>>> = _chats.asStateFlow()

    /** Observe chat list for a specific nodeId as a StateFlow. */
    fun chatFlow(nodeId: String): StateFlow<List<ChatMsgVm>> =
        _chats
            .map { it[nodeId] ?: emptyList() }
            .stateIn(
                scope = viewModelScope,
                started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
                initialValue = emptyList()
            )

    /** Ensure the first AI question bubble is inserted only once for a node. */
    fun chatEnsureSeedQuestion(nodeId: String, question: String) {
        val cur = _chats.value[nodeId]
        if (cur.isNullOrEmpty()) {
            chatAppend(nodeId, ChatMsgVm(id = "q-$nodeId", sender = ChatSender.AI, text = question))
            if (DEBUG_LOGS) Log.d(TAG, "chatEnsureSeedQuestion: seeded for $nodeId")
        }
    }

    fun chatAppend(nodeId: String, msg: ChatMsgVm) {
        updateNode(nodeId) { it + msg }
        if (DEBUG_LOGS) Log.v(TAG, "chatAppend[$nodeId]: ${msg.id}")
    }

    /** Replace existing typing bubble or append if not present. */
    fun chatUpsertTyping(nodeId: String, typing: ChatMsgVm) {
        updateNode(nodeId) { list ->
            val i = list.indexOfFirst { it.isTyping }
            if (i >= 0) list.toMutableList().apply { set(i, typing) } else list + typing
        }
    }

    fun chatRemoveTyping(nodeId: String) {
        updateNode(nodeId) { list -> list.filterNot { it.isTyping } }
    }

    fun chatReplaceTypingWith(nodeId: String, finalMsg: ChatMsgVm) {
        updateNode(nodeId) { list ->
            val i = list.indexOfFirst { it.isTyping }
            if (i >= 0) list.toMutableList().apply { set(i, finalMsg) } else list + finalMsg
        }
    }

    fun chatClear(nodeId: String) {
        _chats.update { it - nodeId }
        if (DEBUG_LOGS) Log.w(TAG, "chatClear: cleared chat for $nodeId")
    }

    private inline fun updateNode(nodeId: String, xform: (List<ChatMsgVm>) -> List<ChatMsgVm>) {
        _chats.update { map ->
            val cur = map[nodeId] ?: emptyList()
            map + (nodeId to xform(cur))
        }
    }

    // ─────────────────────── Execution control ───────────────────────

    private var evalJob: Job? = null
    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    /**
     * Evaluate the given prompt. Returns score (0..100) or null.
     * - Streams partial text into [_stream]
     * - On completion (or timeout), sets [_raw], [_score], [_followups], [_followupQuestion]
     * - On timeout, sets error="timeout" but still finalizes with whatever buffer we have
     */
    suspend fun evaluate(prompt: String, timeoutMs: Long = timeout_ms): Int? {
        if (prompt.isBlank()) {
            Log.i(TAG, "evaluate: blank prompt -> reset states and return null")
            resetStates(keepError = false)
            return null
        }

        val fullPrompt = runCatching { repo.buildPrompt(prompt) }
            .onFailure { t -> Log.e(TAG, "evaluate: buildPrompt failed", t) }
            .getOrElse { prompt }

        // Prevent concurrent runs
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluate: already running -> returning current score=${_score.value}")
            return _score.value
        }

        // Initialize UI state
        _loading.value = true
        _score.value = null
        _stream.value = ""
        _followupQuestion.value = null
        _followups.value = emptyList()
        _raw.value = null
        // keep previous error only if it was timeout/cancel (UX choice). Clear otherwise.
        if (_error.value != "timeout" && _error.value != "cancelled") _error.value = null

        val buf = StringBuilder()
        var chunkCount = 0
        var totalChars = 0
        var emittedTerminalEvent = false

        val elapsed = measureTimeMillis {
            try {
                evalJob = viewModelScope.launch(ioDispatcher) {
                    var isTimeout = false
                    try {
                        // 1) streaming with timeout
                        try {
                            withTimeout(timeoutMs) {
                                repo.request(fullPrompt).collect { part ->
                                    if (part.isNotEmpty()) {
                                        chunkCount++
                                        buf.append(part)
                                        totalChars += part.length
                                        _stream.update { it + part }
                                        _events.tryEmit(AiEvent.Stream(part))
                                    }
                                }
                            }
                        } catch (e: TimeoutCancellationException) {
                            isTimeout = true
                            Log.w(TAG, "evaluate: timeout after ${timeoutMs}ms", e)
                        } catch (e: CancellationException) {
                            if (looksLikeTimeout(e)) {
                                isTimeout = true
                                Log.w(TAG, "evaluate: timeout-like cancellation (${e.javaClass.name})")
                            } else {
                                _error.value = "cancelled"
                                _events.tryEmit(AiEvent.Cancelled)
                                emittedTerminalEvent = true
                                Log.w(TAG, "evaluate: explicit cancellation (${e.javaClass.name})")
                                throw e // propagate
                            }
                        }

                        // 2) finalize with whatever we have
                        val rawText = buf.toString().ifBlank { _stream.value }
                        if (rawText.isNotBlank()) {
                            if (DEBUG_LOGS) {
                                Log.d(TAG, "Evaluate[stats]: prompt.len=${prompt.length}, full.len=${fullPrompt.length}, chunks=$chunkCount, chars=$totalChars")
                                Log.d(TAG, "Evaluate[sha]: prompt=${sha256Hex(prompt)}, full=${sha256Hex(fullPrompt)}, raw=${sha256Hex(rawText)}")
                            }
                            val score = clampScore(FollowupExtractor.extractScore(rawText))
                            val top3 = FollowupExtractor.fromRaw(rawText, max = 3)
                            val question = top3.firstOrNull()

                            _raw.value = rawText
                            _score.value = score
                            _followups.value = top3
                            _followupQuestion.value = question

                            _events.tryEmit(AiEvent.Final(rawText, score, top3))
                            emittedTerminalEvent = true

                            if (DEBUG_LOGS) {
                                Log.i(TAG, "Score=$score, FU[0]=${question ?: "<none>"} FU[1..]=${top3.drop(1)}")
                            }
                        } else {
                            Log.w(TAG, "evaluate: no output produced (stream & buffer empty)")
                            _events.tryEmit(AiEvent.Final("", null, emptyList()))
                            emittedTerminalEvent = true
                        }

                        if (isTimeout) {
                            _error.value = "timeout"
                            _events.tryEmit(AiEvent.Timeout)
                        }
                    } catch (e: CancellationException) {
                        if (!emittedTerminalEvent) _events.tryEmit(AiEvent.Cancelled)
                        _error.value = "cancelled"
                        Log.w(TAG, "evaluate: propagate cancellation", e)
                        throw e
                    } catch (t: Throwable) {
                        _error.value = t.message ?: "error"
                        _events.tryEmit(AiEvent.Error(_error.value!!))
                        Log.e(TAG, "evaluate: error", t)
                    }
                }

                // Wait for job completion; Job.join() won't rethrow CancellationException here
                evalJob?.join()
            } finally {
                // finalize flags & cleanup
                _loading.value = false
                running.set(false)
                try { evalJob?.cancel() } catch (_: Throwable) {}
                evalJob = null
            }
        }

        Log.d(TAG, "evaluate: finished in ${elapsed}ms, score=${_score.value}, err=${_error.value}, chunks=$chunkCount, chars=$totalChars")
        return _score.value
    }

    /** Fire-and-forget variant of [evaluate]. */
    fun evaluateAsync(prompt: String, timeoutMs: Long = timeout_ms): Job {
        // track job so cancel() can cancel it
        val job = viewModelScope.launch {
            val result = evaluate(prompt, timeoutMs)
            Log.i(TAG, "evaluateAsync: finished score=$result, error=${_error.value}")
        }
        evalJob = job
        return job
    }

    /** Cancel the ongoing evaluation if any. */
    fun cancel() {
        Log.i(TAG, "cancel: invoked (isRunning=${running.get()}, loading=${_loading.value})")
        try {
            evalJob?.cancel()
            Log.d(TAG, "cancel: evalJob.cancel() requested")
        } catch (t: Throwable) {
            Log.w(TAG, "cancel: exception during cancel (ignored)", t)
        } finally {
            evalJob = null
            running.set(false)
            _loading.value = false
            _events.tryEmit(AiEvent.Cancelled)
        }
    }

    /** Reset transient states; keeps chat history intact. */
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

    // ───────────────────────── helpers ─────────────────────────

    private fun clampScore(s: Int?): Int? = s?.coerceIn(0, 100)

    private fun looksLikeTimeout(e: CancellationException): Boolean {
        val n = e.javaClass.name
        val m = e.message ?: ""
        return n.endsWith("TimeoutCancellationException") ||
                n.contains("Timeout", ignoreCase = true) ||
                m.contains("timeout", ignoreCase = true)
    }

    private fun sha256Hex(input: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { "%02x".format(it) }
    }.getOrElse { "sha256_error" }
}

/** Events for reactive handling in UI. */
sealed interface AiEvent {
    /** Emitted for each streamed chunk. */
    data class Stream(val chunk: String) : AiEvent
    /** Emitted once at the end (even on timeout) with final buffer and parsed info. */
    data class Final(val raw: String, val score: Int?, val followups: List<String>) : AiEvent
    /** Emitted if evaluation was cancelled explicitly. */
    data object Cancelled : AiEvent
    /** Emitted if evaluation hit the timeout. */
    data object Timeout : AiEvent
    /** Emitted for unexpected errors. */
    data class Error(val message: String) : AiEvent
}
