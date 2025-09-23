// file: com/negi/surveynav/SurveyViewModel.kt
package com.negi.surveynav.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.surveynav.config.NodeDTO
import com.negi.surveynav.config.SurveyConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

private const val TAG = "SurveyVM"

/* ============================================================
 * Graph primitives
 * ============================================================ */
enum class NodeType { START, TEXT, SINGLE_CHOICE, MULTI_CHOICE, AI, REVIEW, DONE }

data class Node(
    val id: String,
    val type: NodeType,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
)

/* Nav keys for nav3 */
@Serializable object FlowHome   : NavKey
@Serializable object FlowText   : NavKey
@Serializable object FlowSingle : NavKey
@Serializable object FlowMulti  : NavKey
@Serializable object FlowAI     : NavKey
@Serializable object FlowReview : NavKey
@Serializable object FlowDone   : NavKey

sealed interface UiEvent {
    data class Snack(val message: String) : UiEvent
    data class Dialog(val title: String, val message: String) : UiEvent
}

/* ============================================================
 * ViewModel
 * ============================================================ */
@Suppress("MemberVisibilityCanBePrivate")
class SurveyViewModel(
    val nav: NavBackStack,
    private val config: SurveyConfig,
) : ViewModel() {

    /* ---------- Graph from JSON ---------- */
    private val graph: Map<String, Node>
    private val startId: String = config.graph.startId

    private fun NodeDTO.toNode(): Node = Node(
        id = id,
        type = runCatching { NodeType.valueOf(type.uppercase()) }.getOrElse { NodeType.TEXT },
        title = title,
        question = question,
        options = options,
        nextId = nextId
    )

    private fun nodeOf(id: String): Node =
        graph[id] ?: error("Node not found: id=$id (defined nodes=${graph.keys})")

    /* ---------- BackStack / current node ---------- */
    private val nodeStack = ArrayDeque<String>() // visited path (ids)
    private val _currentNode = MutableStateFlow(Node(id = "Loading", type = NodeType.START))
    val currentNode: StateFlow<Node> = _currentNode.asStateFlow()

    val canGoBack: StateFlow<Boolean> =
        MutableStateFlow(false).also { out ->
            // keep a tiny mirror for quick UI checks
            _currentNode.subscribe { out.value = nodeStack.size > 1 }
        }

    /* ---------- UI events ---------- */
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /* ---------- Questions / Answers (insertion-ordered) ---------- */
    private val _questions = MutableStateFlow(LinkedHashMap<String, String>() as Map<String, String>)
    val questions: StateFlow<Map<String, String>> = _questions.asStateFlow()
    fun setQuestion(text: String, key: String) {
        _questions.update { it.mutableLinked().apply { put(key, text) } }
    }
    fun getQuestion(key: String): String = questions.value[key].orEmpty()
    fun resetQuestions() { _questions.value = LinkedHashMap() }

    private val _answers = MutableStateFlow(LinkedHashMap<String, String>() as Map<String, String>)
    val answers: StateFlow<Map<String, String>> = _answers.asStateFlow()
    fun setAnswer(text: String, key: String) {
        _answers.update { it.mutableLinked().apply { put(key, text) } }
    }
    fun getAnswer(key: String): String = answers.value[key].orEmpty()
    fun clearAnswer(key: String) {
        _answers.update { it.mutableLinked().apply { remove(key) } }
    }

    /* ---------- Single / Multi (ephemeral per node) ---------- */
    private val _single = MutableStateFlow<String?>(null)
    val single: StateFlow<String?> = _single.asStateFlow()
    fun setSingleChoice(opt: String?) { _single.value = opt }

    private val _multi = MutableStateFlow<Set<String>>(emptySet())
    val multi: StateFlow<Set<String>> = _multi.asStateFlow()
    fun toggleMultiChoice(opt: String) {
        _multi.update { cur -> cur.toMutableSet().apply { if (!add(opt)) remove(opt) } }
    }
    fun clearSelections() {
        _single.value = null
        _multi.value = emptySet()
    }

    /* ---------- Follow-ups (per node, ordered) ---------- */
    data class FollowupEntry(
        val question: String,
        val answer: String? = null,
        val askedAt: Long = System.currentTimeMillis(),
        val answeredAt: Long? = null
    )

    private val _followups = MutableStateFlow<Map<String, List<FollowupEntry>>>(LinkedHashMap())
    val followups: StateFlow<Map<String, List<FollowupEntry>>> = _followups.asStateFlow()

    fun addFollowupQuestion(nodeId: String, question: String, dedupAdjacent: Boolean = true) {
        _followups.update { old ->
            val m = old.mutableLinkedLists()
            val list = m.getOrPut(nodeId) { mutableListOf() }
            if (!(dedupAdjacent && list.lastOrNull()?.question == question)) {
                list.add(FollowupEntry(question))
            }
            m.toImmutableLists()
        }
    }

    fun answerLastFollowup(nodeId: String, answer: String) {
        _followups.update { old ->
            val m = old.mutableLinkedLists()
            val list = m[nodeId] ?: return@update old
            val idx = list.indexOfLast { it.answer == null }
            if (idx < 0) return@update old // 以前は末尾上書きだったが安全側に変更
            list[idx] = list[idx].copy(answer = answer, answeredAt = System.currentTimeMillis())
            m.toImmutableLists()
        }
    }

    fun answerFollowupAt(nodeId: String, index: Int, answer: String) {
        _followups.update { old ->
            val m = old.mutableLinkedLists()
            val list = m[nodeId] ?: return@update old
            if (index !in list.indices) return@update old
            list[index] = list[index].copy(answer = answer, answeredAt = System.currentTimeMillis())
            m.toImmutableLists()
        }
    }

    fun clearFollowups(nodeId: String) {
        _followups.update { old ->
            val m = old.mutableLinkedLists()
            m.remove(nodeId)
            m.toImmutableLists()
        }
    }

    /* ---------- Prompt (from prompts list) ---------- */
    fun getPrompt(nodeId: String, question: String, answer: String): String {
        val tpl = config.prompts.firstOrNull { it.nodeId == nodeId }?.prompt
            ?: throw IllegalArgumentException("No prompt defined for nodeId=$nodeId")
        return renderTemplate(
            tpl,
            mapOf(
                "QUESTION" to question.trim(),
                "ANSWER" to answer.trim(),
                "NODE_ID" to nodeId
            )
        )
    }

    /** Simple template renderer replacing placeholders like {{KEY}} (whitespace tolerant). */
    private fun renderTemplate(template: String, vars: Map<String, String>): String {
        var out = template
        for ((k, v) in vars) {
            out = out.replace(Regex("\\{\\{\\s*$k\\s*\\}\\}"), v)
        }
        return out
    }

    /* ============================================================
     * Navigation helpers
     * ============================================================ */
    private fun navKeyFor(node: Node): NavKey = when (node.type) {
        NodeType.START -> FlowHome
        NodeType.TEXT -> FlowText
        NodeType.SINGLE_CHOICE -> FlowSingle
        NodeType.MULTI_CHOICE -> FlowMulti
        NodeType.AI -> FlowAI
        NodeType.REVIEW -> FlowReview
        NodeType.DONE -> FlowDone
    }

    @Synchronized
    private fun push(node: Node) {
        _currentNode.value = node
        nodeStack.addLast(node.id)
        nav.add(navKeyFor(node))
        Log.d(TAG, "push -> ${node.id}")
    }

    /** 次ノードで質問文が未登録ならプリロードする */
    private fun ensureQuestion(id: String) {
        if (getQuestion(id).isEmpty()) {
            val q = nodeOf(id).question
            if (q.isNotEmpty()) setQuestion(q, id)
        }
    }

    /** 画面遷移時のUI一時状態掃除（必要に応じてUIから呼ぶ） */
    fun onNodeChangedResetSelections() = clearSelections()

    /** 任意のノードへジャンプ（履歴に積む） */
    @Synchronized
    fun goto(nodeId: String) {
        val node = nodeOf(nodeId)
        ensureQuestion(node.id)
        push(node)
    }

    /** 現在のノードを置き換えてジャンプ（履歴を汚さない） */
    @Synchronized
    fun replaceTo(nodeId: String) {
        val node = nodeOf(nodeId)
        ensureQuestion(node.id)
        if (nodeStack.isNotEmpty()) {
            nodeStack.removeLast()
            nav.removeLastOrNull()
        }
        push(node)
        Log.d(TAG, "replaceTo -> ${node.id}")
    }

    /** Startへ完全リセット（NavBackStackも初期化） */
    @Synchronized
    fun resetToStart() {
        nav.clear()
        nodeStack.clear()
        val start = nodeOf(startId)
        _currentNode.value = start
        nodeStack.addLast(start.id)
        nav.add(navKeyFor(start))
        Log.d(TAG, "resetToStart() -> ${start.id}")
    }

    /** 1つ戻る。ルートではNo-Op。 */
    @Synchronized
    fun backToPrevious() {
        if (nodeStack.size <= 1) {
            Log.d(TAG, "backToPrevious: at root (no-op)")
            return
        }
        // 先にNavを戻し、内部スタックも同期
        nav.removeLastOrNull()
        nodeStack.removeLast()
        val prevId = nodeStack.last()
        _currentNode.value = nodeOf(prevId)
        Log.d(TAG, "backToPrevious -> $prevId")
    }

    /** 線形next遷移。nextId未設定なら何もしない。 */
    @Synchronized
    fun advanceToNext() {
        val cur = _currentNode.value
        val nextId = cur.nextId ?: run {
            Log.d(TAG, "advanceToNext: no nextId from ${cur.id}")
            return
        }
        if (!graph.containsKey(nextId)) {
            throw IllegalStateException("nextId '$nextId' from node '${cur.id}' does not exist in graph.")
        }
        ensureQuestion(nextId)
        push(nodeOf(nextId))
    }

    /* ============================================================
     * Internal helpers
     * ============================================================ */
    private fun Map<String, String>.mutableLinked(): LinkedHashMap<String, String> =
        if (this is LinkedHashMap<String, String>) LinkedHashMap(this) else LinkedHashMap(this)

    private fun <T> Map<String, List<T>>.mutableLinkedLists(): LinkedHashMap<String, MutableList<T>> {
        val m = LinkedHashMap<String, MutableList<T>>()
        for ((k, v) in this) m[k] = v.toMutableList()
        return m
    }

    private fun <T> LinkedHashMap<String, MutableList<T>>.toImmutableLists(): Map<String, List<T>> =
        this.mapValues { it.value.toList() }

    // tiny helper to mirror flows without external deps
    private fun <T> StateFlow<T>.subscribe(onEach: (T) -> Unit) {
        onEach(value) // initialize once; real collectionは外部(UI層)でやる想定
    }

    init {
        graph = config.graph.nodes.associateBy { it.id }.mapValues { (_, dto) -> dto.toNode() }
        val start = nodeOf(startId)
        _currentNode.value = start
        nodeStack.addLast(start.id)
        nav.add(navKeyFor(start))
        Log.d(TAG, "init -> ${start.id}")
    }
}
