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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
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

    companion object {
        private const val TAG = "AiViewModelSurveyBaseTest"

        // -------- Instrumentation/Env Args (with speed-oriented defaults) --------
        private fun argString(key: String): String? {
            val a = InstrumentationRegistry.getArguments()
            return a.getString(key) ?: System.getenv(key)
        }

        private fun argInt(key: String): Int? = argString(key)?.toIntOrNull()
        private fun argLong(key: String): Long? = argString(key)?.toLongOrNull()
        private fun argBool(key: String): Boolean? =
            when (argString(key)?.lowercase()?.trim()) {
                "1", "true", "yes", "y", "on" -> true
                "0", "false", "no", "n", "off" -> false
                else -> null
            }

        // Global (kept for compatibility)
        private const val INIT_TIMEOUT_SEC = 15L
        private const val VM_TIMEOUT_SEC = 45L

        // Tunables (defaults prefer speed)
        private val FIRST_CHUNK_TIMEOUT_MS: Long by lazy {
            argLong("FIRST_CHUNK_TIMEOUT_MS") ?: 5_000L
        }
        private val COMPLETE_TIMEOUT_MS: Long by lazy { argLong("COMPLETE_TIMEOUT_MS") ?: 45_000L }
        private val PER_PROMPT_GUARD_MS: Long by lazy {
            argLong("PER_PROMPT_GUARD_MS") ?: (VM_TIMEOUT_SEC * 1_000L + 10_000L)
        }

        private val INSTANCE_WAIT_MS: Long by lazy { argLong("INSTANCE_WAIT_MS") ?: 5_000L }
        private val BETWEEN_PROMPTS_IDLE_WAIT_MS: Long by lazy { argLong("IDLE_WAIT_MS") ?: 2_000L }
        private val BETWEEN_PROMPTS_COOLDOWN_MS: Long by lazy { argLong("COOLDOWN_MS") ?: 300L }

        private val MIN_STREAM_CHARS: Int by lazy { argInt("MIN_STREAM_CHARS") ?: 1 }
        private val MIN_FINAL_CHARS: Int by lazy { argInt("MIN_FINAL_CHARS") ?: 1 }

        private val PROMPT_LIMIT: Int? by lazy { argInt("PROMPT_LIMIT") }
        private val TEST_BUDGET_MS: Long by lazy { argLong("TEST_BUDGET_MS") ?: Long.MAX_VALUE }

        private val ANSWER_MODE: String by lazy {
            argString("ANSWER_MODE")?.lowercase()?.trim() ?: "short"
        }
        private val VERBOSE: Boolean by lazy { argBool("VERBOSE") ?: true }
        private val LOG_FULL_PROMPT: Boolean by lazy { argBool("LOG_FULL_PROMPT") ?: true }
        private val MAX_TOKENS_OVERRIDE: Int by lazy { argInt("MAX_TOKENS") ?: 2048 }

        private lateinit var model: Model
        private val initialized = AtomicBoolean(false)

        @AfterClass
        @JvmStatic
        fun afterClass() {
            runCatching {
                if (::model.isInitialized && !SLM.isBusy(model)) {
                    SLM.cleanUp(model) {}
                }
            }.onFailure { Logx.w(TAG, "SLM cleanup failed: ${it.message}") }
        }
    }

    // ---- Helpers ---------------------------------------------------------------

    /** Normalize minor Unicode and formatting differences for model stability. */
    private fun normalizeForModel(s: String): String = s
        .replace(Regex("[\\u2012-\\u2015]"), "-")
        .replace('\u00A0', ' ')
        .trim()

    private fun defaultAccel(): Accelerator {
        val args = InstrumentationRegistry.getArguments()
        val acc = (args.getString("ACCELERATOR") ?: System.getenv("ACCELERATOR"))
            ?.uppercase()?.trim()
        return if (acc == "CPU") Accelerator.CPU else Accelerator.GPU
    }

    @Before
    fun setUp() {

        appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        config = SurveyConfigLoader.fromAssets(appCtx, "survey_config1.json")

        val issues = config.validate()
        assertTrue("SurveyConfig invalid:\n- " + issues.joinToString("\n- "), issues.isEmpty())

        if (initialized.compareAndSet(false, true)) {
            var accel = defaultAccel()
            val maxTokens = MAX_TOKENS_OVERRIDE
            model = Model(
                name = "gemma-3n-E4B-it",
                taskPath = modelRule.internalModel.absolutePath,
                config = mapOf(
                    ConfigKey.ACCELERATOR to Accelerator.GPU.label,
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
        vm = AiViewModel(repo, timeout_ms = VM_TIMEOUT_SEC * 1000L)

        runCatching { assertFalse("SLM should be idle on start", SLM.isBusy(model)) }
            .onFailure { Logx.w(TAG, "SLM.isBusy check failed: ${it.message}") }
    }

    @After
    fun tearDown() {
        runCatching { vm.cancel() }
        val idle = waitUntil(3_000L) { !SLM.isBusy(model) }
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
            SystemClock.sleep(15L)
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

    // ---- Prompt builder (exemplar/“strong answer” style) -----------------------

    private data class StrongAnswerStyle(
        val persona: String = "Kenyan smallholder maize farmer",
        val wordsMin: Int = 25,
        val wordsMax: Int = 35,
        val requireNumbers: Boolean = true,
        val requireUnits: Boolean = true,
        val mentionSeasonOrMonthIfImplied: Boolean = true,
        val forbidHedging: Boolean = true,
        val oneSentence: Boolean = true,
        val plainAscii: Boolean = true
    )

    private fun buildStrongAnswerPrompt(
        question: String,
        style: StrongAnswerStyle = StrongAnswerStyle()
    ): String {
        val rules = buildString {
            appendLine("ROLE: ${style.persona}.")
            appendLine("TASK: Answer the question below as a definitive, exemplary response from your own perspective.")
            append("OUTPUT RULES: ")
            val rulesList = mutableListOf<String>()
            if (style.oneSentence) rulesList += "one sentence"
            rulesList += "between ${style.wordsMin}-${style.wordsMax} words (strict)"
            rulesList += "plain text only"
            rulesList += "single line only"
            rulesList += "no bullet points"
            rulesList += "no quotes"
            rulesList += "no follow-up questions"
            rulesList += "no preamble"
            if (style.plainAscii) rulesList += "ASCII punctuation only"
            if (style.requireNumbers) rulesList += "include at least one specific number or range"
            if (style.requireUnits) rulesList += "use clear units when applicable"
            if (style.mentionSeasonOrMonthIfImplied) rulesList += "mention season or month if relevant"
            if (style.forbidHedging) rulesList += "avoid hedging words"
            appendLine(rulesList.joinToString("; ") + ".")
            appendLine("TONE: practical, concise, first-person farmer voice (I/we), field-tested advice.")
            appendLine("CONSTRAIN: Do not restate the question. Do not add explanations.")
            appendLine()
            appendLine("Question: ${question.replace('\n', ' ').trim()}")
            append("Answer:")
        }.trim()
        return rules.replace(Regex("\\s+"), " ")
    }

    // ---- Core eval runner ------------------------------------------------------
    private suspend fun runOnce(
        prompt: String,
        firstChunkTimeoutMs: Long = FIRST_CHUNK_TIMEOUT_MS,
        completeTimeoutMs: Long = COMPLETE_TIMEOUT_MS,
        minStreamChars: Int = MIN_STREAM_CHARS,
        tailGraceMs: Long = 300L,
        minFinalChars: Int = MIN_FINAL_CHARS
    ): String {
        val job = vm.evaluateAsync(prompt)
        try {
            withTimeout(firstChunkTimeoutMs) {
                merge(
                    vm.stream.filter { it.length >= minStreamChars }.map { Unit },
                    vm.raw.filterNotNull().map { Unit },
                    vm.error.filterNotNull().map { e ->
                        throw CancellationException("model error (first-signal): $e")
                    }
                ).first()
            }

            val completionTag = withTimeout(completeTimeoutMs) {
                val loadingToFalseAfterChange = vm.loading
                    .drop(1)
                    .filter { !it }
                    .map { "LOADED" }

                merge(
                    vm.raw.filterNotNull().map { "RAW" },
                    loadingToFalseAfterChange,
                    vm.error.filterNotNull().map { e ->
                        throw CancellationException("model error (completion): $e")
                    }
                ).first()
            }

            if (completionTag == "LOADED" && vm.raw.value.isNullOrBlank()) {
                withTimeoutOrNull<Unit>(tailGraceMs) {
                    merge(
                        vm.raw.filterNotNull().map { Unit },
                        vm.stream.drop(1).map { Unit },
                        vm.error.filterNotNull().map { e ->
                            throw CancellationException("model error (tail): $e")
                        }
                    ).first()
                }
                val settleBudgetMs = minOf(150L, tailGraceMs / 2)
                val stableWindowMs = 80L
                val start = SystemClock.elapsedRealtime()
                var lastLen = vm.stream.value.length
                var lastChangeAt = start
                while (SystemClock.elapsedRealtime() - start < settleBudgetMs) {
                    delay(30L)
                    val now = SystemClock.elapsedRealtime()
                    val cur = vm.stream.value.length
                    if (cur != lastLen) {
                        lastLen = cur
                        lastChangeAt = now
                    }
                    if (now - lastChangeAt >= stableWindowMs) break
                }
            }

            val out = vm.raw.value?.takeIf { it.isNotBlank() } ?: vm.stream.value
            require(out.length >= minFinalChars) {
                "empty/short output @finalize: len=${out.length}, " +
                        "error=${vm.error.value}, loading=${vm.loading.value}, stream.len=${vm.stream.value.length}"
            }
            return out
        } finally {
            runCatching { vm.cancel() }
            runCatching { job.cancel() }
        }
    }

    private fun oneLine(s: String?): String =
        s?.replace("\r", " ")?.replace("\n", " ")?.trim().orEmpty()

    @Suppress("unused")
    private fun assertStrictJson(outOneLine: String) {
        require(outOneLine.isNotBlank()) { "Output blank" }
        require(!outOneLine.contains('\n') && !outOneLine.contains('\r')) { "Output must be single line" }
        require(outOneLine.first() == '{' && outOneLine.last() == '}') { "Output must be a JSON object" }
        val req =
            listOf("\"analysis\"", "\"expected answer\"", "\"follow-up questions\"", "\"score\"")
        require(req.all { outOneLine.contains(it) }) { "Missing required keys (need: $req)" }
    }

    private fun dumpAllFollowups() {
        val fuList = try {
            vm.followups.value.toList()
        } catch (_: Throwable) {
            emptyList()
        }
        if (fuList.isEmpty()) {
            if (VERBOSE) Logx.block(TAG, "FOLLOWUPS (0)", "<none>")
            return
        }
        val body = buildString {
            fuList.forEachIndexed { i, s ->
                append(i + 1).append(". ").append(oneLine(s)).append('\n')
            }
        }.trimEnd()
        if (VERBOSE) Logx.block(TAG, "FOLLOWUPS (${fuList.size})", body)
    }

    // ---- SLM直叩き（本命） ------------------------------------------------------
    private class PartialAssembler {
        private val sb = StringBuilder()
        private var latest: String = ""
        fun ingest(part: String) {
            if (part.isEmpty()) return
            sb.append(part)
            latest = sb.toString()
        }

        fun result(): String = latest
    }

    private suspend fun generateAnswerWithSlm(
        model: Model,
        question: String,
        firstChunkTimeoutMs: Long = FIRST_CHUNK_TIMEOUT_MS,
        completeTimeoutMs: Long = COMPLETE_TIMEOUT_MS,
        quietMs: Long = 250L,
        enforceWordCap: Boolean = true
    ): String {

        check(waitUntil(firstChunkTimeoutMs) { !SLM.isBusy(model) }) {
            "SLM stayed busy for ${firstChunkTimeoutMs}ms before runInference"
        }

        val prompt = buildStrongAnswerPrompt(question)

        val firstSeen = CompletableDeferred<Unit>()
        val doneSeen = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()

        var lastChangeAt = SystemClock.elapsedRealtime()
        val assembler = PartialAssembler()

        SLM.runInference(
            model = model,
            input = prompt,
            listener = { partial: String, done: Boolean ->
                // どちらのストリーム形でも復元できるように吸収
                if (partial.isNotEmpty()) {
                    assembler.ingest(partial)
                    lastChangeAt = SystemClock.elapsedRealtime()
                    if (!firstSeen.isCompleted && partial.any { !it.isWhitespace() }) {
                        firstSeen.complete(Unit)
                    }
                }
                if (done && !doneSeen.isCompleted) {
                    doneSeen.complete(Unit)
                    if (!firstSeen.isCompleted) firstSeen.complete(Unit) // done先行対策
                }
            },
            onClean = {
                if (!cleaned.isCompleted) cleaned.complete(Unit)
                if (!firstSeen.isCompleted) firstSeen.complete(Unit)      // clean先行対策
            }
        )

        try {

            withTimeout(firstChunkTimeoutMs) { firstSeen.await() }

            val finished = withTimeoutOrNull(completeTimeoutMs) {
                while (true) {
                    if (doneSeen.isCompleted || cleaned.isCompleted) break
                    val quiet = SystemClock.elapsedRealtime() - lastChangeAt >= quietMs
                    if (quiet && !SLM.isBusy(model)) break
                    delay(25L)
                }
            } != null

            if (!finished) {
                Logx.w(
                    TAG,
                    "slm stream soft-timeout; using partial (len=${assembler.result().length})"
                )
            }
        } finally {
            if (SLM.isBusy(model)) runCatching { SLM.cancel(model) }
            waitUntil(5_000L) { !SLM.isBusy(model) }
            runCatching { SLM.resetSession(model) }
            SystemClock.sleep(200L)
        }

        val out = assembler.result()

        require(out.isNotBlank()) { "empty answer from SLM for q='${question.take(80)}...'" }

        return out
    }

    // ---- Test ------------------------------------------------------------------

    @Test
    fun evaluateAllPrompts() = runBlocking {
        val questionById = config.graph.nodes.associate { it.id to it.question }
        val all = config.prompts
        val prompts = PROMPT_LIMIT?.takeIf { it > 0 }?.let { all.take(it) } ?: all

        var tested = 0
        val testStart = System.currentTimeMillis()

        for (idx in prompts.indices) {
            val p = prompts[idx]

            val elapsed = System.currentTimeMillis() - testStart
            if (elapsed > TEST_BUDGET_MS) {
                Logx.w(
                    TAG,
                    "Test budget exceeded: $elapsed ms > $TEST_BUDGET_MS ms; stopping at idx=$idx"
                )
                break
            }

            val originalQuestion = questionById[p.nodeId]
            assumeTrue(
                "Skip: no question for nodeId=${p.nodeId}",
                !originalQuestion.isNullOrBlank()
            )

            // SLM直叩きで回答生成
            val generatedAnswer = generateAnswerWithSlm(
                model = model,
                question = originalQuestion!!,
                firstChunkTimeoutMs = FIRST_CHUNK_TIMEOUT_MS,
                completeTimeoutMs = COMPLETE_TIMEOUT_MS,
                quietMs = 250L,
                enforceWordCap = true
            )

            Logx.w(TAG, "Original Question : $originalQuestion")
            Logx.w(TAG, "Generated Answer  : $generatedAnswer")

            // 既存パイプラインに流す
            val answerText = normalizeForModel(generatedAnswer)
            val filledPrompt = fillPlaceholders(p.prompt, originalQuestion, answerText)
            val fullPrompt = repo.buildPrompt(filledPrompt)

            if (VERBOSE) {
                Logx.kv(
                    TAG, "PROMPT META",
                    mapOf(
                        "idx" to "${idx + 1}/${config.prompts.size}",
                        "nodeId" to p.nodeId,
                        "template.len" to "${p.prompt.length}",
                        "question.len" to "${originalQuestion.length}",
                        "answer.len" to "${answerText.length}",
                        "fullPrompt.len" to "${fullPrompt.length}",
                        "answerMode" to ANSWER_MODE
                    )
                )
                if (LOG_FULL_PROMPT) Logx.block(TAG, "FULL PROMPT", fullPrompt)
            }

            val t0 = System.currentTimeMillis()

            val out: String = try {
                withTimeout(PER_PROMPT_GUARD_MS) {
                    runOnce(
                        prompt = fullPrompt,
                        firstChunkTimeoutMs = FIRST_CHUNK_TIMEOUT_MS,
                        completeTimeoutMs = COMPLETE_TIMEOUT_MS,
                        minStreamChars = MIN_STREAM_CHARS,
                        tailGraceMs = 300L,
                        minFinalChars = MIN_FINAL_CHARS
                    )
                }
            } catch (t: Throwable) {
                runCatching { vm.cancel() }
                runCatching { if (SLM.isBusy(model)) SLM.cancel(model) }
                val errState =
                    "vm.error=${vm.error.value}, loading=${vm.loading.value}, stream.len=${vm.stream.value.length}"
                throw AssertionError(
                    "Model error (phase=await) for nodeId=${p.nodeId}: ${t::class.simpleName}: ${t.message} ($errState)",
                    t
                )
            }

            val dur = System.currentTimeMillis() - t0
            assertTrue("Empty output for nodeId=${p.nodeId}", out.isNotBlank())

            val outOne = oneLine(out)
            val followupsCount = vm.followups.value?.size ?: 0
            val scoreVal = vm.score.value
            scoreVal?.let { assertTrue("score must be 1..100", it in 1..100) }

            if (VERBOSE) {
                if (outOne.length >= 500) {
                    Logx.w(
                        TAG,
                        "Output length nearing 512: len=${outOne.length} nodeId=${p.nodeId}"
                    )
                }
                val qLog = originalQuestion.replace("\r", " ").replace("\n", " ").take(200)
                val aLog = answerText.replace("\r", " ").replace("\n", " ").take(200)
                val scoreLog = scoreVal?.toString() ?: "<none>"

                Logx.kv(
                    TAG, "EVAL DONE", mapOf(
                        "raw.buf" to outOne,
                        "raw.len" to "${outOne.length}",
                        "question" to qLog,
                        "answer" to aLog,
                        "score" to scoreLog,
                        "followups.count" to "$followupsCount",
                        "ms" to "$dur"
                    )
                )
            }

            dumpAllFollowups()

            val becameIdle = waitUntil(BETWEEN_PROMPTS_IDLE_WAIT_MS) { !SLM.isBusy(model) }
            assertTrue("Engine did not become idle after nodeId=${p.nodeId}", becameIdle)
            if (becameIdle) runCatching { SLM.resetSession(model) }

            SystemClock.sleep(BETWEEN_PROMPTS_COOLDOWN_MS)
            tested++
        }

        assertTrue("No prompts were tested (0)", tested > 0)
    }
}
