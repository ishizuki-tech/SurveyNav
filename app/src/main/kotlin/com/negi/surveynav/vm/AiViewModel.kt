package com.negi.surveynav.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negi.surveynav.slm.FollowupExtractor
import com.negi.surveynav.slm.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI関連の処理だけを担当する ViewModel（完全置き換え版）
 * - テキスト評価の実行
 * - ストリーム更新
 * - スコア抽出/保持
 * - キャンセル/リセット
 */
class AiViewModel(
    private val repo: Repository,
    private val passThreshold: Int = 70,
    private val DEFAULT_TIMEOUT_MS: Long = 120_000L
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

    /** 評価結果が合格かどうか（nullは不合格扱い） */
    val passed: StateFlow<Boolean> = MutableStateFlow(false).also { out ->
        viewModelScope.launch {
            score.collect { s -> out.value = s?.let { it >= passThreshold } == true }
        }
    }

    private var evalJob: Job? = null
    private val running = AtomicBoolean(false)

    /**
     * サスペンド版：評価完了まで待ち、スコアを返す（失敗時は null）
     */
    suspend fun evaluate(prompt: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Int? {

        if (prompt.isBlank()) {
            resetStates(keepError = false)
            return null
        }

        if (!running.compareAndSet(false, true)) {
            // すでに実行中
            return _score.value
        }

        _loading.value = true
        _score.value = null
        _stream.value = ""
        _followupQuestion.value = null
        _raw.value = null
        _error.value = null

        val buf = StringBuilder()

        try {
            withTimeout(timeoutMs) {
                evalJob = viewModelScope.launch(Dispatchers.IO) {
                    repo.request(prompt)
                        .onEach { part ->
                            buf.append(part)
                            withContext(Dispatchers.Main.immediate) {
                                _stream.value += part
                            }
                        }
                        .catch { e ->
                            withContext(Dispatchers.Main.immediate) {
                                _error.value = e.message ?: "unknown error"
                            }
                            throw e
                        }
                        .collect() {}
                }
                evalJob?.join()
            }

            val rawText = buf.toString()
            val s = FollowupExtractor.extractScore(rawText)
            val followupQuestion = FollowupExtractor.extractFollowupQuestion(rawText)
            withContext(Dispatchers.Main.immediate) {
                _raw.value = rawText
                _score.value = s
                _followupQuestion.value = followupQuestion
            }
            return s
        } catch (_: TimeoutCancellationException) {
            _error.value = "timeout"
            return null
        } catch (_: CancellationException) {
            _error.value = "cancelled"
            return null
        } catch (e: Throwable) {
            _error.value = e.message ?: "error"
            return null
        } finally {
            _loading.value = false
            running.set(false)
            try {
                evalJob?.cancel()
            } catch (_: Throwable) {
            }
            evalJob = null
        }
    }

    /**
     * 非同期版：評価を開始して即座に戻す（状態は StateFlow を購読）
     */
    fun evaluateAsync(prompt: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Job =
        viewModelScope.launch {
            evaluate(prompt, timeoutMs)
        }

    /** 実行中の評価をキャンセル */
    fun cancel() {
        try {
            evalJob?.cancel()
        } catch (_: Throwable) {
        }
        evalJob = null
        running.set(false)
        _loading.value = false
    }

    /** 状態だけ初期化 */
    fun resetStates(keepError: Boolean = false) {
        cancel()
        _loading.value = false
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        if (!keepError) _error.value = null
    }
}