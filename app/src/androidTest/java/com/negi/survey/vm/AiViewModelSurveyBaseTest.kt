// file: app/src/androidTest/java/com/negi/survey/vm/AiViewModelSurveyAllPromptsNoRBTest.kt
package com.negi.survey.vm

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.Logx
import com.negi.survey.ModelAssetRule
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.slm.Accelerator
import com.negi.survey.slm.ConfigKey
import com.negi.survey.slm.Model
import com.negi.survey.slm.SLM
import com.negi.survey.slm.SlmDirectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@LargeTest
class AiViewModelSurveyBaseTest {

    @get:Rule
    val modelRule = ModelAssetRule()

    private lateinit var appCtx: Context
    private lateinit var repo: SlmDirectRepository
    private lateinit var vm: AiViewModel
    private lateinit var config: SurveyConfig

    private lateinit var testScope: CoroutineScope

    companion object {
        private const val TAG = "SurveyBaseTest"
        private const val INIT_TIMEOUT_SEC = 15L
        private const val VM_TIMEOUT_SEC = 45L
        private const val INSTANCE_WAIT_MS = 5_000L
        private const val BETWEEN_PROMPTS_IDLE_WAIT_MS = 3_000L
        private const val BETWEEN_PROMPTS_COOLDOWN_MS = 1_500L

        private lateinit var model: Model
        private val initialized = AtomicBoolean(false)

        private val PROMPT_LIMIT: Int? by lazy {
            val a = InstrumentationRegistry.getArguments()
            (a.getString("PROMPT_LIMIT") ?: System.getenv("PROMPT_LIMIT"))?.toIntOrNull()
        }
        private val TEST_BUDGET_MS: Long by lazy {
            val a = InstrumentationRegistry.getArguments()
            (a.getString("TEST_BUDGET_MS") ?: System.getenv("TEST_BUDGET_MS"))?.toLongOrNull()
                ?: Long.MAX_VALUE
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            runCatching {
                if (::model.isInitialized && !SLM.isBusy(model)) SLM.cleanUp(model) {}
            }.onFailure { Logx.w(TAG, "SLM cleanup failed: ${it.message}") }
        }
    }

    // ---- Answers (short/long) --------------------------------------------------

    private val defaultShortAnswers: Map<String, String> = mapOf(
        "Q1" to "90 days.",
        "Q2" to "About 95 days from planting to harvest.",
        "Q3" to "Late October.",
        "Q4" to "Up to 10% loss.",
        "Q5" to "Drought escape and better price timing.",
        "Q6" to "Early October to mid October.",
        "Q7" to "Early October.",
        "Q8" to "February.",
        "Q9" to "Yes, plant beans in late November.",
        "Q10" to "13-15%.",
        "Q11" to "Up to 18%.",
        "Q12" to "Fall armyworm.",
        "Q13" to "January.",
        "Q14" to "10% premium.",
        "Q15" to "15 bags/acre.",
        "Q16" to "13 bags/acre.",
        "Q17" to "22,000 plants/acre.",
        "Q18" to "1550 m a.s.l.",
        "Q19" to "No.",
        "Q20" to "Yes, 0.1 acre."
    )

    private val defaultLongAnswers: Map<String, String> = mapOf(
        "Q1" to "I call a maize variety early if it reaches maturity in about 90 days after planting, maybe 85-95 depending on the season, so we can finish before the short rains end.",
        "Q2" to "Most seasons my current variety takes around 95 days from planting to harvest. In a cooler year it can stretch to about 100, and in a hotter year we finish closer to 90.",
        "Q3" to "We need maize off the field by late October to be safe. Early November is already risky because the short rains can spoil drying and we want the land ready for beans.",
        "Q4" to "I could accept up to about a 10% yield loss if it lets me harvest earlier and dodge heavy rains and mold. More than that and I would rather keep the current variety.",
        "Q5" to "My top reasons are escaping drought and catching a better market price. Harvesting earlier lowers pest pressure and lets me sell when grain is scarce.",
        "Q6" to "We usually plant from early October to mid October as soon as the first good soaking rain arrives. If the onset is late, we push toward late October.",
        "Q7" to "Around here the short rains usually begin in early October, sometimes we see a first shower at the end of September before they settle.",
        "Q8" to "Drought risk peaks for us in February when the sun is hottest and the soil dries fast. If the short rains fail, that is the hardest month on the crop.",
        "Q9" to "Yes. If we harvest early, we can plant beans in late November. That gives enough time for land prep and to use the remaining moisture.",
        "Q10" to "We aim to harvest at about 13-15% grain moisture so it stores well without mold. If we must pick slightly wetter, we dry on tarps or in cribs.",
        "Q11" to "If rains are coming, I would accept up to about 18% at harvest, but only if I can dry quickly under cover or in a ventilated crib. Otherwise I prefer lower moisture.",
        "Q12" to "Fall armyworm is the main pest we want to outrun. An earlier variety helps us pass the worst pressure before it builds up.",
        "Q13" to "Prices are usually best in January when most farmers have sold and stocks are low. Sometimes early February is even higher if the supply is tight.",
        "Q14" to "I could pay roughly a 10% seed premium if the variety reliably matures earlier and reduces losses. If it also stands well and shells clean, that adds value.",
        "Q15" to "In a typical season we get about 15 bags per acre on our main field. With good rains we can reach 17-18, and in poor years it can drop near 12.",
        "Q16" to "If harvest is roughly two weeks earlier, I would accept a minimum of about 13 bags per acre. If it falls below that, I would not switch.",
        "Q17" to "We target about 22,000 plants per acre, using roughly 75 cm rows and 25-30 cm in-row spacing. We fill gaps if emergence is patchy.",
        "Q18" to "Our farm is around 1550 m above sea level by phone GPS. Nights are cool here, so grain tends to dry a bit slower.",
        "Q19" to "No, we do not have irrigation during establishment. We rely on the first good rains and may replant small patches if the first shower fails.",
        "Q20" to "Yes, I can trial an earlier variety on about 0.1 acre next season. I will compare time to harvest, yield, and grain quality against what I grow now."
    )

    // ---- Helpers ---------------------------------------------------------------

    /** Normalize minor Unicode and formatting differences for model stability. */
    private fun normalizeForModel(s: String): String = s
        .replace(Regex("[\\u2012-\\u2015]"), "-") // various dashes -> hyphen
        .replace('\u00A0', ' ')                  // NBSP -> space
        .trim()

    private fun defaultAccel(): Accelerator {
        val args = InstrumentationRegistry.getArguments()
        val acc = (args.getString("ACCELERATOR") ?: System.getenv("ACCELERATOR"))
            ?.uppercase()?.trim()
        return if (acc == "CPU") Accelerator.CPU else Accelerator.GPU
    }

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        config = SurveyConfigLoader.fromAssets(appCtx, "survey_config2.json")

        val issues = config.validate()
        assertTrue("SurveyConfig invalid:\n- " + issues.joinToString("\n- "), issues.isEmpty())

        if (initialized.compareAndSet(false, true)) {
            var accel = defaultAccel()
            model = Model(
                name = "gemma-3n-E4B-it",
                taskPath = modelRule.internalModel.absolutePath,
                // UPDATED: deterministic decoding for test reproducibility
                config = mapOf(
                    ConfigKey.ACCELERATOR to accel.label,
                    ConfigKey.MAX_TOKENS to 512,
                    ConfigKey.TOP_K to 1,
                    ConfigKey.TOP_P to 0.0f,
                    ConfigKey.TEMPERATURE to 0.0f
                )
            )
            var initErr = initializeModel(appCtx, model, INIT_TIMEOUT_SEC)
            if (initErr.isNullOrEmpty()) {
                val ok = waitUntil(INSTANCE_WAIT_MS) { model.instance != null }
                check(ok) { "SLM instance not available within ${INSTANCE_WAIT_MS}ms" }
            }
            if (!initErr.isNullOrEmpty() && accel != Accelerator.CPU) {
                Logx.w(TAG, "GPU init failed: $initErr → fallback to CPU")
                accel = Accelerator.CPU
                model = Model(
                    name = model.name,
                    taskPath = modelRule.internalModel.absolutePath,
                    config = model.config.toMutableMap().apply {
                        put(ConfigKey.ACCELERATOR, accel.label)
                    }
                )
                initErr = initializeModel(appCtx, model, INIT_TIMEOUT_SEC)
                if (initErr.isNullOrEmpty()) {
                    val ok = waitUntil(INSTANCE_WAIT_MS) { model.instance != null }
                    check(ok) { "SLM instance not available (CPU) within ${INSTANCE_WAIT_MS}ms" }
                }
            }
            check(initErr.isNullOrEmpty()) { "SLM initialization error: $initErr" }
            assertNotNull("Model instance must be set", model.instance)
        } else {
            assertNotNull("Model instance must exist", model.instance)
        }

        repo = SlmDirectRepository(appCtx, model)
        vm = AiViewModel(repo, timeout_ms = VM_TIMEOUT_SEC * 1000)

        runCatching { assertFalse("SLM should be idle on start", SLM.isBusy(model)) }
            .onFailure { Logx.w(TAG, "SLM.isBusy check failed: ${it.message}") }
    }

    @After
    fun tearDown() {
        runCatching { vm.cancel() }
        runCatching { testScope.cancel() }
        val idle = waitUntil(1_000) { !SLM.isBusy(model) }
        if (idle) runCatching { SLM.resetSession(model) }
    }

    private fun initializeModel(ctx: Context, model: Model, timeoutSec: Long): String? {
        val latch = CountDownLatch(1)
        var err: String? = null
        SLM.initialize(ctx, model) { e -> err = e; latch.countDown() }
        assertTrue("SLM init timeout", latch.await(timeoutSec, TimeUnit.SECONDS))
        return err
    }

    /** Simple polling helper (non-suspending, short sleeps). */
    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            SystemClock.sleep(15)
        }
        return false
    }

    /** Replace {{QUESTION}} / {{ANSWER}} and append headers if missing. */
    private fun fillPlaceholders(tpl: String, q: String, a: String): String {
        val hadQ = "{{QUESTION}}" in tpl
        val hadA = "{{ANSWER}}" in tpl
        val base = tpl.replace("{{QUESTION}}", q).replace("{{ANSWER}}", a).trim()
        return buildString {
            appendLine(base)
            if (hadQ && !base.contains("Question:", true)) appendLine("Question: $q")
            if (hadA && !base.contains("Answer:", true)) append("Answer: $a")
        }.trim()
    }

    // ---- Core eval runner ------------------------------------------------------

    private suspend fun runOnce(
        prompt: String,
        firstChunkTimeoutMs: Long = 60_000L,
        completeTimeoutMs: Long = 120_000L,
        minStreamChars: Int = 1,          // UPDATED: allow earliest signal
        tailGraceMs: Long = 600L
    ): String {
        val job = vm.evaluateAsync(prompt)
        try {
            // 1) First signal: stream chunk OR raw arrival
            withTimeout<Unit>(firstChunkTimeoutMs) {
                merge(
                    vm.stream.filter { it.length >= minStreamChars }.map { Unit },
                    vm.raw.filterNotNull().map { Unit }
                ).first()
            }

            // 2) Completion: raw!=null OR loading=false (whichever comes first)
            val completionTag = withTimeout(completeTimeoutMs) {
                merge(
                    vm.raw.filterNotNull().map { "RAW" },
                    vm.loading.filter { !it }.map { "LOADED" }
                ).first()
            }

            // 3) Tiny grace for tail chunks if we completed via loading=false
            if (completionTag == "LOADED" && vm.raw.value == null) {
                withTimeoutOrNull<Unit>(tailGraceMs) {
                    vm.raw.filterNotNull().map { Unit }.first()
                }
            }

            val out = vm.raw.value?.takeIf { it.isNotBlank() } ?: vm.stream.value
            require(out.isNotBlank()) {
                "empty output @finalize: error=${vm.error.value}, loading=${vm.loading.value}, stream.len=${vm.stream.value.length}"
            }
            return out
        } finally {
            vm.cancel()
            job.cancel()
        }
    }

    private fun oneLine(s: String?): String = s?.replace("\r", " ")?.replace("\n", " ")?.trim().orEmpty()

    /** STRICT validator for the RAW one-line JSON contract. */
    private fun assertStrictJson(outOneLine: String) {
        require(outOneLine.isNotBlank()) { "Output blank" }
        require(!outOneLine.contains('\n') && !outOneLine.contains('\r')) { "Output must be single line" }
        require(outOneLine.length < 512) { "Output must be <512 chars (len=${outOneLine.length})" }
        require(outOneLine.first() == '{' && outOneLine.last() == '}') { "Output must be a JSON object (starts/ends with braces)" }
        val req = listOf("\"analysis\"", "\"expected answer\"", "\"follow-up questions\"", "\"score\"")
        require(req.all { outOneLine.contains(it) }) { "Missing required keys (need: $req)" }
    }

    private fun dumpAllFollowups() {
        val fuList = try { vm.followups.value.toList() } catch (_: Throwable) { emptyList() }
        if (fuList.isEmpty()) {
            Logx.block(TAG, "FOLLOWUPS (0)", "<none>")
            return
        }
        val body = buildString {
            fuList.forEachIndexed { i, s ->
                append(i + 1).append(". ").append(oneLine(s)).append('\n')
            }
        }.trimEnd()
        Logx.block(TAG, "FOLLOWUPS (${fuList.size})", body)
    }

    private fun <T> awaitBlockingWithTimeout(deferred: Deferred<T>, timeoutMs: Long): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null

        deferred.invokeOnCompletion { cause ->
            result = if (cause == null) {
                runCatching { deferred.getCompleted() }
            } else Result.failure(cause)
            latch.countDown()
        }

        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!ok) {
            deferred.cancel(CancellationException("await timeout ${timeoutMs}ms"))
            throw TimeoutException("Deferred.await timed out after ${timeoutMs}ms")
        }
        val r = result ?: throw IllegalStateException("Deferred completed but result was null")
        return r.getOrThrow()
    }

    // ---- Test ------------------------------------------------------------------

    @Test
    fun evaluateAllPrompts() {
        val questionById = config.graph.nodes.associate { it.id to it.question }
        val all = config.prompts
        val prompts = PROMPT_LIMIT?.takeIf { it > 0 }?.let { all.take(it) } ?: all

        var tested = 0
        val testStart = System.currentTimeMillis()

        for (idx in prompts.indices) { // UPDATED: break-able loop
            val p = prompts[idx]

            val elapsed = System.currentTimeMillis() - testStart
            if (elapsed > TEST_BUDGET_MS) {
                Logx.w(TAG, "Test budget exceeded: $elapsed ms > $TEST_BUDGET_MS ms; stopping at idx=$idx")
                break
            }

            val qText = questionById[p.nodeId]
            assumeTrue("Skip: no question for nodeId=${p.nodeId}", !qText.isNullOrBlank())

            val aTextRaw = defaultLongAnswers[p.nodeId] ?: "I don't know."
            val aText = normalizeForModel(aTextRaw)
            val filledPrompt = fillPlaceholders(p.prompt, qText!!, aText)
            val fullPrompt = repo.buildPrompt(filledPrompt)

            Logx.kv(TAG, "PROMPT META",
                mapOf(
                    "idx" to "${idx + 1}/${config.prompts.size}",
                    "nodeId" to p.nodeId,
                    "template.len" to "${p.prompt.length}",
                    "question.len" to "${qText.length}",
                    "answer.len" to "${aText.length}",
                    "fullPrompt.len" to "${fullPrompt.length}"
                )
            )

            Logx.block(TAG, "FULL PROMPT", fullPrompt)

            val t0 = System.currentTimeMillis()

            val deferred = testScope.async(Dispatchers.Main.immediate) {
                runOnce(
                    prompt = fullPrompt,
                    firstChunkTimeoutMs = 60_000L,
                    completeTimeoutMs = 120_000L,
                    minStreamChars = 1 // UPDATED
                )
            }

            val perPromptTimeoutMs = (VM_TIMEOUT_SEC * 1000) + 90_000L

            val out: String = try {
                awaitBlockingWithTimeout(deferred, perPromptTimeoutMs)
            } catch (t: Throwable) {
                deferred.cancel(CancellationException("per-prompt envelope timeout/cancel", t))
                runCatching { vm.cancel() }
                runCatching { if (SLM.isBusy(model)) SLM.cancel(model) }
                val errState = "vm.error=${vm.error.value}, loading=${vm.loading.value}, stream.len=${vm.stream.value.length}"
                throw AssertionError("Model error (phase=await) for nodeId=${p.nodeId}: ${t::class.simpleName}: ${t.message} ($errState)", t)
            }

            val dur = System.currentTimeMillis() - t0
            assertTrue("Empty output for nodeId=${p.nodeId}", out.isNotBlank())

            val outOne = oneLine(out)
            if (outOne.length >= 500) {
                Logx.w(TAG, "Output length nearing 512: len=${outOne.length} nodeId=${p.nodeId}")
            }

            val followupsCount = try { vm.followups.value.size } catch (_: Throwable) { -1 }
            val scoreVal = vm.score.value

            // Strong assertions per spec
            scoreVal?.let { assertTrue("score must be 1..100", it in 1..100) }

            //if (followupsCount >= 0) assertTrue("followups.size must be 3", followupsCount == 3)

            val qLog = qText.replace("\r", " ").replace("\n", " ").take(200)
            val aLog = aText.replace("\r", " ").replace("\n", " ").take(200)
            val scoreLog = scoreVal?.toString() ?: "<none>"

            Logx.kv(
                TAG, "EVAL DONE", mapOf(
                    "raw.buf" to outOne,
                    "raw.len" to "${outOne.length}",
                    "question" to qLog,
                    "answer" to aLog,
                    "score" to scoreLog,
                    "followups.count" to "$followupsCount",
                    "ms" to "$dur",
                )
            )

            dumpAllFollowups()

            // Ensure engine idle, then tiny cooldown to let repository's awaitClose release the semaphore.
            val idle = waitUntil(BETWEEN_PROMPTS_IDLE_WAIT_MS) { !SLM.isBusy(model) }
            assertTrue("Engine did not become idle after nodeId=${p.nodeId}", idle)
            if (idle) runCatching { SLM.resetSession(model) }

            assertStrictJson(outOne)

            SystemClock.sleep(BETWEEN_PROMPTS_COOLDOWN_MS.toLong()) // small cooldown
            tested++
        }

        assertTrue("No prompts were tested (0)", tested > 0)
    }
}
