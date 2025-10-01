// app/src/androidTest/java/com/negi/survey/vm/AiViewModelInstrumentationTest.kt
package com.negi.survey.vm

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.ModelAssetRule
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.slm.Accelerator
import com.negi.survey.slm.ConfigKey
import com.negi.survey.slm.Model
import com.negi.survey.slm.SLM
import com.negi.survey.slm.SlmDirectRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@LargeTest
class AiViewModelSurveyBaseTest {

    @get:Rule
    val modelRule = ModelAssetRule()

    private lateinit var appCtx: Context
    private lateinit var repo: SlmDirectRepository
    private lateinit var vm: AiViewModel

    companion object {
        private const val TAG = "AiViewModelSurveyBaseTest"
        private const val TIMEOUT_SEC = 60L
        private const val COMPLETE_TIMEOUT_MS = 120_000L
        private const val INIT_TIMEOUT_SEC = 45L

        private lateinit var model: Model
        private val initialized = AtomicBoolean(false)

        @AfterClass
        @JvmStatic
        fun afterClass() {
            runCatching {
                if (this::model.isInitialized) SLM.cleanUp(model) {}
            }
        }
    }

    // === テストで使う入力（JSON） ===
    private lateinit var config: SurveyConfig

    // デフォ回答（nodeId -> answer）— 必要に応じて調整
    private val defaultAnswers: Map<String, String> = mapOf(
        "Q1" to "90 days.",
        "Q2" to "About 95 days from planting to harvest.",
        "Q3" to "Late October.",
        "Q4" to "Up to 10% loss.",
        "Q5" to "Drought escape and better price timing.",
        "Q6" to "Early Oct to mid Oct.",
        "Q7" to "Early October.",
        "Q8" to "February.",
        "Q9" to "Yes, plant beans in late November.",
        "Q10" to "13–15%.",
        "Q11" to "Up to 18%.",
        "Q12" to "Fall armyworm.",
        "Q13" to "January.",
        "Q14" to "10% premium.",
        "Q15" to "15 bags/acre.",
        "Q16" to "13 bags/acre.",
        "Q17" to "22,000 plants/acre.",
        "Q18" to "1550 m.a.s.l.",
        "Q19" to "No.",
        "Q20" to "Yes, 0.1 acre."
    )

    @Before
    fun setUp() {
        runBlocking {

            appCtx = InstrumentationRegistry.getInstrumentation().targetContext
            requireNotNull(appCtx) { "targetContext is null" }

            config = SurveyConfigLoader.fromAssets(
                context = appCtx,
                fileName = "survey_config2.json"
            )

            // 2) 構造検証
            val issues = config.validate()
            assertTrue(
                "SurveyConfig validation failed:\n- " + issues.joinToString("\n- "),
                issues.isEmpty()
            )

            // 3) SLM 初期化（環境変数 ACCELERATOR=CPU/GPU で切替。デフォはGPU）
            if (initialized.compareAndSet(false, true)) {
                val accel = when (System.getenv("ACCELERATOR")?.uppercase()?.trim()) {
                    "CPU" -> Accelerator.CPU
                    else -> Accelerator.GPU
                }
                Log.i(TAG, "Initializing SLM with accelerator=${accel.label}")

                model = Model(
                    name = "gemma-3n-E4B-it",
                    taskPath = modelRule.internalModel.absolutePath,
                    config = mapOf(
                        ConfigKey.ACCELERATOR to Accelerator.GPU.label,
                        ConfigKey.MAX_TOKENS to 512,
                        ConfigKey.TOP_K to 40,
                        ConfigKey.TOP_P to 0.9f,
                        ConfigKey.TEMPERATURE to 0.7f
                    )
                )
                val latch = CountDownLatch(1)
                var initError: String? = null
                SLM.initialize(appCtx, model) { err ->
                    initError = err
                    latch.countDown()
                }
                assertTrue("SLM init timeout", latch.await(INIT_TIMEOUT_SEC, TimeUnit.SECONDS))
                require(initError != null) { "SLM init callback not invoked" }
                require(initError!!.isEmpty()) { "SLM initialization error: $initError" }
                assertNotNull("Model instance must be created", model.instance)
            }
            else {
                assertNotNull("Model instance must exist", model.instance)
            }

            // 4) Repo / VM
            repo = SlmDirectRepository(appCtx, model)
            vm = AiViewModel(repo, timeout_ms = TIMEOUT_SEC * 1000)

            runCatching { assertFalse("busy should be false at test start", SLM.isBusy(model)) }
        }
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { vm.cancel() } }
    }

    // ==== ユーティリティ ====
    private fun preview(s: String, max: Int = 200): String =
        if (s.length <= max) s else s.take(max) + "…(" + s.length + ")"

    private fun fillPlaceholders(tpl: String, question: String, answer: String): String {
        val hadQ = "{{QUESTION}}" in tpl
        val hadA = "{{ANSWER}}" in tpl
        val base = tpl.replace("{{QUESTION}}", question).replace("{{ANSWER}}", answer).trim()
        return buildString {
            appendLine(base)
            if (hadQ && !base.contains("Question:", ignoreCase = true)) appendLine("Question: $question")
            if (hadA && !base.contains("Answer:", ignoreCase = true)) append("Answer: $answer")
        }.trim()
    }

    /**
     * 1プロンプトを投げて出力文字列を返す。
     * - 先頭チャンクは非致命プローブ（出なければログだけ）
     * - 完了は loading の true→false 遷移 or error 発火のどちらかで判定
     * - 最終的に raw があれば raw、なければ stream を返す
     */
    private suspend fun runOnce(
        prompt: String,
        firstChunkProbeMs: Long = 8_000L,
        completeTimeoutMs: Long = COMPLETE_TIMEOUT_MS
    ): String {
        val job: Job = vm.evaluateAsync(prompt)
        try {
            // 先頭チャンクの“観測のみ”（失敗させない）
            if (firstChunkProbeMs > 0) {
                val seen = withTimeoutOrNull(firstChunkProbeMs) {
                    merge(
                        vm.loading.filter { it }.map { Unit },
                        vm.stream.filter { it.isNotEmpty() }.map { Unit },
                        vm.error.filter { it != null }.map { Unit }
                    ).first()
                }
                if (seen == null) Log.w(TAG, "runOnce: first chunk not seen within ${firstChunkProbeMs}ms (continuing)")
            }

            // 完了待ち：loading が一度 true になってから false へ戻る or error
            withTimeout(completeTimeoutMs) {
                merge(
                    vm.loading
                        .dropWhile { it == false }   // 最初の true が来るまで捨てる
                        .filter { it == false }       // その後の false（完了）を待つ
                        .map { Unit },
                    vm.error.filter { it != null }.map { Unit }
                ).first()
            }

            val out = vm.raw.value ?: vm.stream.value
            require(out.isNotBlank()) { "empty output (error=${vm.error.value})" }

            Log.i(
                TAG,
                "runOnce: done err=${vm.error.value}, score=${vm.score.value}, " +
                        "followup=${!vm.followupQuestion.value.isNullOrBlank()}, len=${out.length}"
            )
            return out
        } finally {
            vm.cancel()
            job.cancel()
        }
    }

    // ==== メイン：assets の survey_config2.json を読んで各 Prompt を評価（JSON厳格検証なし） ====
    @Test
    fun evaluate_AllSurveyPrompts() {
        runBlocking {
            val questionById: Map<String, String> =
                config.graph.nodes.associate { it.id to it.question }

            var tested = 0
            config.prompts.forEachIndexed { idx, p ->
                val node = config.graph.nodes.firstOrNull { it.id == p.nodeId }
                val nodeType = node?.type ?: "?"
                val nodeTitle = node?.title ?: ""
                val qText = questionById[p.nodeId]

                if (qText.isNullOrBlank()) {
                    Log.w(TAG, "[${idx + 1}/${config.prompts.size}] Skip nodeId=${p.nodeId} (no question). Graph validate() should flag this.")
                    return@forEachIndexed
                }

                val aText = defaultAnswers[p.nodeId] ?: "OK."
                val containsQ = "{{QUESTION}}" in p.prompt
                val containsA = "{{ANSWER}}" in p.prompt
                val filledPrompt = fillPlaceholders(p.prompt, qText, aText)

                Log.i(
                    TAG,
                    """
                    [${idx + 1}/${config.prompts.size}] nodeId=${p.nodeId} type=$nodeType title="$nodeTitle"
                      - template.len=${p.prompt.length} has{{Q}}=$containsQ has{{A}}=$containsA
                      - question.len=${qText.length} answer.len=${aText.length}
                      - filled_prompt.len=${filledPrompt.length}
                      - filled_prompt   ="$filledPrompt"
                    """.trimIndent()
                )

                val t0 = System.currentTimeMillis()
                try {
                    val raw = runOnce(filledPrompt)
                    val dur = System.currentTimeMillis() - t0

                    Log.i(
                        TAG,
                        """
                        [${idx + 1}/${config.prompts.size}] nodeId=${p.nodeId} DONE in ${dur}ms
                          - raw.len=${raw.length}
                          - raw.head="${preview(raw, 200)}"
                        """.trimIndent()
                    )
                    tested++
                } catch (t: Throwable) {
                    val dur = System.currentTimeMillis() - t0
                    Log.e(
                        TAG,
                        """
                        [${idx + 1}/${config.prompts.size}] nodeId=${p.nodeId} FAILED after ${dur}ms
                          - template.head="${preview(p.prompt, 200)}"
                          - filledPrompt.head="${preview(filledPrompt, 200)}"
                          - error=${t::class.java.simpleName}: ${t.message}
                        """.trimIndent()
                    )
                    throw t
                }
            }

            assertTrue("No prompts were tested (0)", tested > 0)
            Log.i(TAG, "evaluate_AllSurveyPrompts_NoJsonStrictness: tested=$tested/${config.prompts.size} prompts")
        }
    }
}
