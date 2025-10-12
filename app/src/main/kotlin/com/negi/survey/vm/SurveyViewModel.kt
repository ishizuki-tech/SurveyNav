package com.negi.survey.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

private const val TAG = "SurveyVM"

/**
 * Interface that abstracts minimal navigation stack operations.
 * Enables testability and decouples from navigation library.
 */
interface BackStackPort<K : NavKey> {
    fun add(key: K): Boolean
    fun removeLastOrNull(): K?
    fun clear()
}

/**
 * Adapter to bridge Navigation3's NavBackStack with BackStackPort.
 */
class Nav3BackStackAdapter(
    private val delegate: NavBackStack<NavKey>
) : BackStackPort<NavKey> {
    override fun add(key: NavKey) = delegate.add(key)
    override fun removeLastOrNull(): NavKey? = delegate.removeLastOrNull()
    override fun clear() = delegate.clear()
}

/**
 * Survey node types for flow branching.
 */
enum class NodeType { START, TEXT, SINGLE_CHOICE, MULTI_CHOICE, AI, REVIEW, DONE }

/**
 * Runtime node model built from config.
 */
data class Node(
    val id: String,
    val type: NodeType,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
)

/**
 * NavKey definitions for each flow node (navigation destinations).
 */
@Serializable object FlowHome   : NavKey
@Serializable object FlowText   : NavKey
@Serializable object FlowSingle : NavKey
@Serializable object FlowMulti  : NavKey
@Serializable object FlowAI     : NavKey
@Serializable object FlowReview : NavKey
@Serializable object FlowDone   : NavKey

/**
 * Events for showing UI feedback (e.g., snackbars or dialogs).
 */
sealed interface UiEvent {
    data class Snack(val message: String) : UiEvent
    data class Dialog(val title: String, val message: String) : UiEvent
}

/**
 * Main ViewModel for managing survey state, answers, navigation, and follow-ups.
 */
open class SurveyViewModel(
    val nav: NavBackStack<NavKey>,
    private val config: SurveyConfig,
) : ViewModel() {

    // Graph configuration loaded from JSON
    private val graph: Map<String, Node>
    private val startId: String = config.graph.startId

    // Gets a Node from ID or throws if missing
    private fun nodeOf(id: String): Node =
        graph[id] ?: error("Node not found: id=$id (defined nodes=${graph.keys})")

    // Internal stack tracking visited nodes
    private val nodeStack = ArrayDeque<String>()

    // StateFlow holding the currently displayed node
    private val _currentNode = MutableStateFlow(Node(id = "Loading", type = NodeType.START))
    val currentNode: StateFlow<Node> = _currentNode.asStateFlow()

    // Whether back navigation is possible
    val canGoBack: StateFlow<Boolean> =
        MutableStateFlow(false).also { out ->
            _currentNode.subscribe { out.value = nodeStack.size > 1 }
        }

    // UI-level events such as snackbars or alerts
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // Question map (id to text) to persist during flow
    private val _questions = MutableStateFlow(LinkedHashMap<String, String>() as Map<String, String>)
    val questions: StateFlow<Map<String, String>> = _questions.asStateFlow()

    fun setQuestion(text: String, key: String) {
        _questions.update { it.mutableLinked().apply { put(key, text) } }
    }

    fun getQuestion(key: String): String = questions.value[key].orEmpty()
    fun resetQuestions() { _questions.value = LinkedHashMap() }

    // Answer map (id to response)
    private val _answers = MutableStateFlow(LinkedHashMap<String, String>() as Map<String, String>)
    val answers: StateFlow<Map<String, String>> = _answers.asStateFlow()

    fun setAnswer(text: String, key: String) {
        _answers.update { it.mutableLinked().apply { put(key, text) } }
    }

    fun getAnswer(key: String): String = answers.value[key].orEmpty()
    fun clearAnswer(key: String) {
        _answers.update { it.mutableLinked().apply { remove(key) } }
    }

    // Single-choice and multi-choice selections (ephemeral)
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

    /**
     * Data class representing one follow-up QA entry.
     */
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
            if (idx < 0) return@update old
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

    /**
     * Returns a rendered prompt for a given node/question/answer set.
     */
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

    /**
     * Replaces placeholders in templates using the format {{KEY}}.
     */
    private fun renderTemplate(template: String, vars: Map<String, String>): String {
        var out = template
        for ((k, v) in vars) {
            out = out.replace(Regex("\\{\\{\\s*$k\\s*\\}\\}"), v)
        }
        return out
    }

    // Navigation helpers
    private fun navKeyFor(node: Node): NavKey = when (node.type) {
        NodeType.START -> FlowHome
        NodeType.TEXT -> FlowText
        NodeType.SINGLE_CHOICE -> FlowSingle
        NodeType.MULTI_CHOICE -> FlowMulti
        NodeType.AI -> FlowAI
        NodeType.REVIEW -> FlowReview
        NodeType.DONE -> FlowDone
    }

    /**
     * Pushes a node into the stack and navigates to its destination.
     */
    @Synchronized
    private fun push(node: Node) {
        _currentNode.value = node
        nodeStack.addLast(node.id)
        nav.add(navKeyFor(node))
        Log.d(TAG, "push -> ${node.id}")
    }

    /** Preloads question if not yet cached */
    private fun ensureQuestion(id: String) {
        if (getQuestion(id).isEmpty()) {
            val q = nodeOf(id).question
            if (q.isNotEmpty()) setQuestion(q, id)
        }
    }

    /** Call from UI to reset transient state per node */
    fun onNodeChangedResetSelections() = clearSelections()

    /** Navigates to the specified node and adds it to history */
    @Synchronized
    fun goto(nodeId: String) {
        val node = nodeOf(nodeId)
        ensureQuestion(node.id)
        push(node)
    }

    /** Replaces current node in history (i.e., jump without stacking) */
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

    /** Resets navigation to the first node */
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

    /** Navigates back to previous node (no-op at root) */
    @Synchronized
    fun backToPrevious() {
        if (nodeStack.size <= 1) {
            Log.d(TAG, "backToPrevious: at root (no-op)")
            return
        }
        nav.removeLastOrNull()
        nodeStack.removeLast()
        val prevId = nodeStack.last()
        _currentNode.value = nodeOf(prevId)
        Log.d(TAG, "backToPrevious -> $prevId")
    }

    /** Moves forward to next node if defined */
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

    // Internal collection helpers
    private fun Map<String, String>.mutableLinked(): LinkedHashMap<String, String> =
        if (this is LinkedHashMap<String, String>) LinkedHashMap(this) else LinkedHashMap(this)

    private fun <T> Map<String, List<T>>.mutableLinkedLists(): LinkedHashMap<String, MutableList<T>> {
        val m = LinkedHashMap<String, MutableList<T>>()
        for ((k, v) in this) m[k] = v.toMutableList()
        return m
    }

    private fun <T> LinkedHashMap<String, MutableList<T>>.toImmutableLists(): Map<String, List<T>> =
        this.mapValues { it.value.toList() }

    private fun <T> StateFlow<T>.subscribe(onEach: (T) -> Unit) {
        onEach(value)
    }

    // Initialize graph and set first node

    // Converts DTO to internal node model

    init {
        graph = config.graph.nodes.associateBy { it.id }.mapValues { (_, dto) -> dto.toNode() }
        val start = nodeOf(startId)
        _currentNode.value = start
        nodeStack.addLast(start.id)
        nav.add(navKeyFor(start))
        Log.d(TAG, "init -> ${start.id}")
    }
}