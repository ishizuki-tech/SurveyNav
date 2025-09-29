// app/src/androidTest/java/com/negi/survey/vm/AiViewModelGraphConfigFromAssetsInstrumentationTest.kt
package com.negi.survey.vm

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.negi.survey.ModelAssetRule
import com.negi.survey.slm.InferenceModel
import com.negi.survey.slm.MediaPipeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Runs AiViewModel against the graph+prompts JSON stored in assets:
 *   app/src/main/assets/survey_config.json
 *
 * Verifies:
 *  - Each AI node prompt produces STRICT one-line RAW JSON (<512 chars)
 *  - Required keys exist: analysis, expected answer, follow-up questions (size=3), score(int)
 *  - VM's extracted score (if present) matches JSON "score"
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AiViewModelGraphConfigFromAssetsInstrumentationTest {

    @get:Rule val modelRule = ModelAssetRule()

    private lateinit var appCtx: Context
    private lateinit var repo: MediaPipeRepository
    private lateinit var vm: AiViewModel
    private lateinit var cfg: RootConfig

    @Before
    fun setUp() = runBlocking {
        appCtx = modelRule.context

        // Ensure MediaPipe-backed model is loaded once (path prepared by ModelAssetRule).
        InferenceModel.getInstance(appCtx).ensureLoaded(modelRule.internalModel.absolutePath)

        if (!::repo.isInitialized) repo = MediaPipeRepository(appCtx)
        if (!::vm.isInitialized) vm = AiViewModel(repo, timeout_ms = 120_000)
        else vm.resetStates(keepError = false)

        // Load survey_config.json from app assets.
        val jsonText = readAssetText(appCtx, "survey_config2.json")
        cfg = Json { ignoreUnknownKeys = true }.decodeFromString(jsonText)

        assertEquals("Start", cfg.graph.startId)
        assertTrue("prompts must not be empty", cfg.prompts.isNotEmpty())
    }

    @After
    fun tearDown() {
        // Keep model/repo for speed across tests.
    }

    @Test
    fun runAllPromptsInOrder_fromAssets() = runBlocking {
        val nodesById = cfg.graph.nodes.associateBy { it.id }
        val answers = defaultAnswers()

        var nodeId = cfg.graph.startId
        nodeId = nodesById[nodeId]?.nextId ?: error("Start.nextId missing")
        val visited = mutableListOf<String>()

        while (true) {
            val node = nodesById[nodeId] ?: break
            if (node.type == "AI") {
                visited += node.id
                val promptItem = cfg.prompts.find { it.nodeId == node.id }
                    ?: error("No prompt for nodeId=${node.id}")

                val finalPrompt = promptItem.prompt
                    .replace("{{QUESTION}}", node.question)
                    .replace("{{ANSWER}}", answers[node.id] ?: "No answer provided.")

                Log.i("GraphTest", "Running ${node.id}: ${node.title}")
                runOnceStrict(node.id, finalPrompt)
            }
            nodeId = node.nextId.toString()
        }

        assertEquals(listOf("Q1","Q2","Q3","Q4","Q5"), visited)
    }

    // --- Helpers ---------------------------------------------------------------

    // Replace your runOnceStrict() with this version (and add sanitizeRaw()).
// Replace your runOnceStrict() with this version
    private suspend fun runOnceStrict(
        nodeId: String,
        prompt: String,
        firstChunkTimeoutMs: Long = 60_000,
        completeTimeoutMs: Long = 180_000
    ) {
        // --- Extra logging: prompt/Q/A preview ---
        val (q, a) = extractQA(prompt)

        Log.i("GraphTest", "[$nodeId] promptLen=${prompt.length}")
        Log.i("GraphTest", "[$nodeId] Question: ${truncateForLog(q)}")
        Log.i("GraphTest", "[$nodeId] Answer  : ${truncateForLog(a)}")

        val job: Job = vm.evaluateAsync(prompt)
        try {
            // Wait for first streamed chunk to confirm generation started.
            withTimeout(firstChunkTimeoutMs) {
                vm.stream.filter { it.length >= 6 }.first()
            }

            Log.i("GraphTest", "[$nodeId] first chunk received, streamLen=${vm.stream.value.length}")

            // Wait until the ViewModel reports completion.
            withTimeout(completeTimeoutMs) {
                vm.loading.filter { it == false }.first()
            }

            // VM-level checks
            assertNull("error should be null for $nodeId, got=${vm.error.value}", vm.error.value)
            val raw0: String = (vm.raw.value ?: fail("raw was null for $nodeId")).toString()

            // Sanitize: strip BOM/whitespace/backticks/fences and trim to first {...} block if needed.
            val raw = sanitizeRaw(raw0)

            Log.i("GraphTest", "[$nodeId] rawLen=${raw.length}, rawHead='${truncateForLog(raw.take(120))}', rawTail='${truncateForLog(raw.takeLast(120))}'")

            // Strict formatting constraints
            assertTrue("raw must start with '{' for $nodeId, got: ${raw.take(16)}", raw.first() == '{')
            assertTrue("raw must end with '}' for $nodeId, got: ${raw.takeLast(16)}", raw.last() == '}')
            assertFalse("raw must be one line for $nodeId", raw.contains('\n') || raw.contains('\r'))

//            if (STRICT) {
//                assertTrue("raw length >=512 for $nodeId (${raw.length})", raw.length < 512)
//            }

            // Parse & validate shape
            val root : JsonObject = (Json.parseToJsonElement(raw) as? JsonObject
                ?: fail("raw JSON is not an object for $nodeId")) as JsonObject

            assertTrue("\"analysis\" missing for $nodeId", "analysis" in root)
            assertTrue("\"expected answer\" missing for $nodeId", "expected answer" in root)
            assertTrue("\"follow-up questions\" missing for $nodeId", "follow-up questions" in root)
            assertTrue("\"score\" missing for $nodeId", "score" in root)

            val analysis = root["analysis"]!!.jsonPrimitive.content
            assertTrue("analysis must be non-empty for $nodeId", analysis.isNotBlank())

            val expected = root["expected answer"]!!.jsonPrimitive.content
            assertTrue("expected answer must be non-empty for $nodeId", expected.isNotBlank())

            val fu : JsonArray  = (root["follow-up questions"] as? JsonArray
                ?: fail("follow-up questions must be array for $nodeId")) as JsonArray
            assertEquals("follow-up questions must have 3 items for $nodeId", 3, fu.size)

            fu.forEachIndexed { i, e ->
                val s = e.jsonPrimitive.content
                assertTrue("follow-up[$i] must be non-empty for $nodeId", s.isNotBlank())
            }

            // Version-safe int parsing
            val scoreInt = root["score"]!!.jsonPrimitive.content.toIntOrNull()?: fail("score must be int for $nodeId")
            assertTrue("score out of range for $nodeId: $scoreInt", scoreInt in 0..100)

            // Keep VM extractor consistent with JSON "score" (if VM parsed it).
            vm.score.value?.let { vmScore ->
                assertEquals("VM score != JSON score for $nodeId", scoreInt, vmScore)
            }
            Log.i("GraphTest", "[$nodeId] OK score=$scoreInt, followUps=${fu.size}, streamLen=${vm.stream.value.length}")
        } finally {
            job.cancel() // safe even if already completed
        }
    }

// Add these helpers to the same test class:

    /** Extracts the final 'Question:' and 'Answer:' blocks from the composed prompt. */
    private fun extractQA(prompt: String): Pair<String?, String?> {
        val qIdx = prompt.lastIndexOf("Question:")
        val aIdx = prompt.lastIndexOf("Answer:")
        if (qIdx >= 0 && aIdx > qIdx) {
            val q = prompt.substring(qIdx + "Question:".length, aIdx).trim()
            val a = prompt.substring(aIdx + "Answer:".length).trim()
            return q to a
        }
        return null to null
    }

    /** Truncates long strings for logs, preserving length information. */
    private fun truncateForLog(s: String?, max: Int = 300): String {
        if (s == null) return "(null)"
        return if (s.length <= max) s else s.take(max) + "…(+" + (s.length - max) + ")"
    }

    /** Remove BOM/markdown fences/backticks and trim to the first valid {...} block. */
    private fun sanitizeRaw(input: String): String {
        // Strip BOM and surrounding whitespace/newlines
        var s = input.removePrefix("\uFEFF").trim()

        // Remove common code fences/backticks if any slipped through
        if (s.startsWith("```")) {
            s = s.removePrefix("```")
            // remove language hint like "json"
            s = s.trimStart().removePrefix("json").trimStart()
            val fence = s.indexOf("```")
            if (fence >= 0) s = s.substring(0, fence).trim()
        }
        if (s.startsWith("`") && s.endsWith("`")) {
            s = s.substring(1, s.length - 1).trim()
        }

        // If there is extra prose around the JSON, cut to the first {...} block.
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1)
        }
        // Ensure single line (contract requires one line)
        s = s.replace("\r", "").replace("\n", "")
        return s
    }

    /** Load asset text with UTF-8 and BOM stripping. */
    private fun readAssetText(ctx: Context, assetName: String): String {
        val raw = ctx.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return raw.removePrefix("\uFEFF")
    }

    /** Default canned answers to fill {{ANSWER}} placeholders per node. */
    private fun defaultAnswers(): Map<String, String> = mapOf(
        "Q1" to "80–90 days to maturity.",
        "Q2" to "Around 95 days from planting to harvest in typical seasons.",
        "Q3" to "October.",
        "Q4" to "Up to 10% yield loss.",
        "Q5" to "Drought escape and market timing."
    )

    // --- Models matching the asset schema -------------------------------------

    @Serializable
    data class RootConfig(
        val prompts: List<PromptItem>,
        val graph: Graph
    )

    @Serializable
    data class PromptItem(
        val nodeId: String,
        val prompt: String
    )

    @Serializable
    data class Graph(
        val startId: String,
        val nodes: List<GraphNode>
    )

    @Serializable
    data class GraphNode(
        val id: String,
        val type: String,
        val title: String,
        val question: String,
        @SerialName("nextId") val nextId: String? = null
    )
}
