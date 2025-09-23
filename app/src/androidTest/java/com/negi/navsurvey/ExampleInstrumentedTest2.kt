// file: src/androidTest/java/com/example/llm/ExampleInstrumentedTest.kt
package com.negi.navsurvey

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.utils.Accelerator
import com.negi.utils.ConfigKeys
import com.negi.utils.FollowupExtractor
import com.negi.utils.Model
import com.negi.utils.SLMModule
import okio.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.text.isEmpty

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    private lateinit var context: Context
    private lateinit var model: Model

    private val TAG = "ExampleInstrumentedTest"

    // タイムアウトは端末性能に合わせて調整
    private val INIT_TIMEOUT_SEC = 60L
    private val GEN_TIMEOUT_SEC = 90L

    // テスト対象のモデルファイル名（assetsに置く場合の名前）
    private val MODEL_NAME = "gemma-3n-E4B-it-int4.litertlm"

    /**
     * モデルの実体を filesDir に用意して File を返す。
     * 優先順:
     * 1) 既に filesDir に存在
     * 2) instrumentation args: -P android.testInstrumentationRunnerArguments.modelPath=/abs/path
     * 3) /data/local/tmp/<MODEL_NAME> （adb pushで事前配置）
     * 4) assets/<MODEL_NAME> を filesDir へコピー
     */
    private fun ensureModelPresent(ctx: Context): File {
        val dst = File(ctx.filesDir, MODEL_NAME)
        if (dst.exists() && dst.length() > 0L) {
            require(dst.canRead()) { "Model not readable: ${dst.absolutePath}" }
            return dst
        }

        // 2) instrumentation runner arguments
        InstrumentationRegistry.getArguments().getString("modelPath")?.let { path ->
            val src = File(path)
            require(src.exists() && src.length() > 0L) { "modelPath not found or empty: $path" }
            src.inputStream().use { ins -> dst.outputStream().use { outs -> ins.copyTo(outs) } }
            require(dst.exists() && dst.length() > 0L) { "Failed to copy from modelPath" }
            require(dst.canRead()) { "Model not readable after copy: ${dst.absolutePath}" }
            return dst
        }

        // 3) /data/local/tmp からのコピー
        val tmp = File("/data/local/tmp/$MODEL_NAME")
        if (tmp.exists() && tmp.length() > 0L) {
            tmp.inputStream().use { ins -> dst.outputStream().use { outs -> ins.copyTo(outs) } }
            require(dst.exists() && dst.length() > 0L) { "Failed to copy from /data/local/tmp" }
            require(dst.canRead()) { "Model not readable after copy: ${dst.absolutePath}" }
            return dst
        }

        // 4) assets からのコピー（存在すれば）
        try {
            ctx.assets.open(MODEL_NAME).use { input ->
                FileOutputStream(dst).use { output -> input.copyTo(output) }
            }
            require(dst.exists() && dst.length() > 0L) { "Failed to copy from assets" }
            require(dst.canRead()) { "Model not readable after copy: ${dst.absolutePath}" }
            return dst
        } catch (_: Exception) {
            // assets に無ければ fall-through
        }

        error(
            "Model not found: ${dst.absolutePath}. " +
                    "Provide via -P android.testInstrumentationRunnerArguments.modelPath=/abs/path, " +
                    "or adb push $MODEL_NAME /data/local/tmp/, or place in app/src/main/assets/."
        )
    }

    @Before
    fun setup() {
        Log.i(TAG, "setup() CALLED.")

        context = ApplicationProvider.getApplicationContext()
        val modelFile = ensureModelPresent(context)

        // ★ 絶対パスを渡す：先頭が "/" の場合、Model はそのままパスとして扱う想定
        model = Model(
            name = "gemma3n_test",
            pathOrAsset = modelFile.absolutePath
        )
            .set(ConfigKeys.ACCELERATOR, Accelerator.GPU.label) // テストはCPUが安定しやすい
            .set(ConfigKeys.MAX_TOKENS, 4096)
            .set(ConfigKeys.TOP_K, 20)
            .set(ConfigKeys.TOP_P, 0.9f)
            .set(ConfigKeys.TEMPERATURE, 0.1f)
    }

    /** 初期化→テキスト推論（ストリーミング完了まで待つ） */
    @Test
    fun init_and_generate_text() {
        val initLatch = CountDownLatch(1)
        val initError = AtomicReference("")

        SLMModule.initialize(
            context = context,
            model = model,
            supportImage = false,
            supportAudio = false
        ) { err ->
            initError.set(err)
            initLatch.countDown()
        }

        assertTrue("Initialization timed out", initLatch.await(INIT_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue("Initialization error: ${initError.get()}", initError.get().isEmpty())

        //val prompt = "次の文章を20文字以内で要約して: メディアパイプのLLM推論のテストをしています。"
        val prompt = "Hello!!"
        val genLatch = CountDownLatch(1)
        val out = StringBuilder()

        SLMModule.runInference(
            model = model,
            input = prompt,
            images = emptyList(),
            audioClips = emptyList(),
            resultListener = { partial, done ->
                if (partial.isNotEmpty()) out.append(partial)
                if (done) genLatch.countDown()
            },
            cleanUpListener = {
                Log.i(TAG, "CleanUp listener called.")
            }
        )

        assertTrue("Generation timed out", genLatch.await(GEN_TIMEOUT_SEC, TimeUnit.SECONDS))
        val text = out.toString().trim()
        Log.i(TAG, "Generated text: $text")
        assertTrue("Generated text should not be empty", text.isNotEmpty())
    }

    /** 画像/音声フラグを切り替えてセッションだけ再生成（エラーなく通ること） */
    @Test
    fun reset_session_toggle_modalities() {
        val initLatch = CountDownLatch(1)
        SLMModule.initialize(
            context = context,
            model = model,
            supportImage = false,
            supportAudio = false
        ) { _ -> initLatch.countDown() }
        assertTrue(initLatch.await(INIT_TIMEOUT_SEC, TimeUnit.SECONDS))

        SLMModule.resetSession(context, model, supportImage = true, supportAudio = false)
        SLMModule.resetSession(context, model, supportImage = true, supportAudio = true)

        val latch = CountDownLatch(1)
        val out = StringBuilder()
        SLMModule.runInference(
            model = model,
            input = "1語だけ返して: テスト",
            resultListener = { partial, done ->
                if (partial.isNotEmpty()) out.append(partial)
                if (done) latch.countDown()
            },
            cleanUpListener = {}
        )
        assertTrue(latch.await(GEN_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue(out.isNotEmpty())
    }

    /** cleanup の冪等性（複数回呼んでも落ちない） */
    @Test
    fun cleanup_idempotent() {
        val initLatch = CountDownLatch(1)
        SLMModule.initialize(
            context = context,
            model = model,
            supportImage = false,
            supportAudio = false
        ) { _ -> initLatch.countDown() }
        assertTrue(initLatch.await(INIT_TIMEOUT_SEC, TimeUnit.SECONDS))

        val c1 = CountDownLatch(1)
        SLMModule.cleanUp(model) { c1.countDown() }
        assertTrue(c1.await(15, TimeUnit.SECONDS))

        // 2回目（未初期化でも落ちないことの確認）
        SLMModule.cleanUp(model) { /* onDoneは呼ばれない仕様なら何もしない */ }
        assertTrue(true)
    }

    private val singleRunLock = ReentrantLock()

    private fun startAndAwait(body: String): Pair<String, Int> = singleRunLock.withLock {

        SLMModule.resetSession(context, model, supportImage = false, supportAudio = false)

        val latch = CountDownLatch(1)
        val buf = StringBuilder()
        val chunkCount = intArrayOf(0)

        SLMModule.runInference(
            model = model,
            input = body,
            resultListener = { partial, done ->
                if (partial.isNotEmpty()) {
                    Log.i(TAG, "::: partial = $partial")
                    buf.append(partial)
                    chunkCount[0]++
                }
                if (done) {
                    latch.countDown()
                }
            },
            cleanUpListener = {
                // モデル破棄時に呼ばれる（今回は特に何もしない）
            }
        )
        val ok = latch.await(GEN_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!ok) throw AssertionError("Generation timed out")
        buf.toString() to chunkCount[0]
    }


    // ---------------- Prompt helpers ----------------
    private val FOLLOWUP_TAG = "FollowupInstrumentedTest"
    private val MAX_RAW_LOG_CHARS = 600

    private fun wrapWithTurns(body: String): String = """
        <start_of_turn>user
        ${body.trimIndent()}
        <end_of_turn>
        <start_of_turn>model
    """.trimIndent()

    private fun renderTemplate(template: String, vars: Map<String, String>): String {
        return Regex("\\{\\{\\s*([A-Z0-9_]+)\\s*\\}\\}")
            .replace(template) { m -> vars[m.groupValues[1]] ?: m.value }
            .trim()
    }

    private fun getPrompt(question: String, answer: String): String {
        Log.i(FOLLOWUP_TAG, " Question ::: $question")
        Log.i(FOLLOWUP_TAG, " Answer   ::: $answer")

        val tpl = """
            You are a concise English survey expert. Read the Question and the Answer.

            Produce STRICT ONE-LINE JSON with EXACT keys:
            - "analysis": string (brief)
            - "expected answer": string
            - "follow-up questions": array of EXACTLY 3 strings
            - "score": integer 0-100

            HARD RULES:
            - One single line. First char '{', last '}'.
            - No markdown, no code fences, no extra keys.
            - Keep total under 512 characters.

            EXAMPLE (ONE LINE):
            {"analysis":"brief","expected answer":"<answer>","follow-up questions":["Q1","Q2","Q3"],"score":87}

            Question: {{QUESTION}}
            Answer: {{ANSWER}}
        """.trimIndent()

        return renderTemplate(
            tpl,
            mapOf("QUESTION" to question.trim(), "ANSWER" to answer.trim())
        )
    }

    // ---------------- Output parsing ----------------

    private fun parseFollowupsFromJsonStrict(raw: String): List<String>? {
        val one = raw.trim().replace(Regex("\\s+"), " ")
        if (!(one.startsWith("{") && one.endsWith("}"))) return null
        return try {
            val obj = JSONObject(one)
            val arr: JSONArray = obj.getJSONArray("follow-up questions")
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.getString(i).trim()
                if (s.isNotEmpty()) list += s
            }
            if (list.isNotEmpty()) list.take(3) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractFollowupQuestions(rawText: String): List<String> {
        val strict = parseFollowupsFromJsonStrict(rawText)
        if (strict != null) return strict.take(3)
        val fx = FollowupExtractor.fromRaw(rawText, max = 3)
        if (fx.size != 3) {
            Log.w(
                FOLLOWUP_TAG,
                "Heuristic extracted ${fx.size} followups (expected 3). raw='${
                    rawText.take(
                        MAX_RAW_LOG_CHARS
                    )
                }...'"
            )
        }
        return fx
    }

    data class QACase(
        val question: String,
        val answer: String,
        val expectedStatus: String,   // informational (not asserted)
        val expectedFollowup: String  // informational (not asserted)
    )

    // --------------- Tests ---------------


    @Test
    fun validateEarlyMaturitySetWithQAMatcher() {
        val cases = listOf(
            QACase(
                question = "What would you consider to be an early maturing maize variety?",
                answer = "A maize variety that matures in 90 days",
                expectedStatus = "Complete",
                expectedFollowup = ""
            ),
            QACase(
                question = "What would you consider to be an early maturing maize variety?",
                answer = "Variety that i can harvest before short rains end in my area",
                expectedStatus = "Incomplete",
                expectedFollowup = "When do short rains start and end in your area?"
            ),
            QACase(
                question = "What would you consider to be an early maturing maize variety?",
                answer = "One that allows me to plant another crop in the same season",
                expectedStatus = "Incomplete",
                expectedFollowup = "How many days should this variety take to mature to allow you to plant another crop in the same season?"
            ),
            QACase(
                question = "What would you consider to be an early maturing maize variety?",
                answer = "Variety that i can harvest before pest destroy it",
                expectedStatus = "Incomplete",
                expectedFollowup = "Within how many days does the maize need to mature to avoid being damaged by those pests?"
            ),
            QACase(
                question = "What would you consider to be an early maturing maize variety?",
                answer = "Any variety that matures within 3 months",
                expectedStatus = "Complete",
                expectedFollowup = ""
            ),
            QACase(
                question = "How long does the maize you currently grow take to mature?",
                answer = "It takes 90 days from planting to harvesting",
                expectedStatus = "Complete",
                expectedFollowup = ""
            ),
            QACase(
                question = "How long does the maize you currently grow take to mature?",
                answer = "It takes around 3 to 4 months depending on the rains",
                expectedStatus = "Incomplete",
                expectedFollowup = "In most occassions, does the variety take 3 months or 4 months to mature?"
            ),
            QACase(
                question = "How long does the maize you currently grow take to mature?",
                answer = "It doesn’t take too long, maybe a few months",
                expectedStatus = "Incomplete",
                expectedFollowup = "Could you please estimate the number of days it takes to mature?"
            ),
            QACase(
                question = "How long does the maize you currently grow take to mature?",
                answer = "I’m not sure, I just harvest when it’s ready",
                expectedStatus = "Incomplete",
                expectedFollowup = "In most cases, after how long is it usually ready? How many days or month?"
            ),
            QACase(
                question = "How long does the maize you currently grow take to mature?",
                answer = "it takes too long, sometimes more than half of the year",
                expectedStatus = "Complete",
                expectedFollowup = ""
            ),
            QACase(
                question = "How much yield would you give up for harvesting earlier?",
                answer = "I would accept loosing 10% of my yield to harvest earlier",
                expectedStatus = "Complete",
                expectedFollowup = ""
            ),
            QACase(
                question = "How much yield would you give up for harvesting earlier?",
                answer = "I wouldn't give up any yield, I would rather harvest late",
                expectedStatus = "Complete",
                expectedFollowup = ""
            ),
            QACase(
                question = "How much yield would you give up for harvesting earlier?",
                answer = "Loosing 1 to 2 bags is okay for more so long i escape the dry spell",
                expectedStatus = "Complete",
                expectedFollowup = ""
            ),
            QACase(
                question = "How much yield would you give up for harvesting earlier?",
                answer = "I would only give up a little of my harvest",
                expectedStatus = "Incomplete",
                expectedFollowup = "How much is a little? Can you estimate the quantity in bags or percentage of your harvest"
            ),
            QACase(
                question = "How much yield would you give up for harvesting earlier?",
                answer = "I could give up about one bag per acre",
                expectedStatus = "Complete",
                expectedFollowup = ""
            )
        )

        val initLatch = CountDownLatch(1)
        val initError = AtomicReference("")

        SLMModule.initialize(
            context = context,
            model = model,
            supportImage = false,
            supportAudio = false
        ) { err ->
            initError.set(err)
            initLatch.countDown()
        }

        assertTrue("Initialization timed out", initLatch.await(INIT_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue("Initialization error: ${initError.get()}", initError.get().isEmpty())

        cases.forEachIndexed { idx, tc ->
            Log.w(TAG, "== Begin Q&A =================================================")
            Log.w(TAG, "[${idx + 1}/${cases.size}] Question='${tc.question}'")
            Log.w(TAG, "[${idx + 1}/${cases.size}] Answer  ='${tc.answer}'")

            val prompt = wrapWithTurns(
                getPrompt(
                    question = tc.question,
                    answer = tc.answer
                )
            )



            val (response, chunkCount) = startAndAwait(prompt)

            val rawShort = response.take(MAX_RAW_LOG_CHARS)
            Log.i(
                TAG,
                "chunks: $chunkCount, raw response: '${rawShort}${if (response.length > MAX_RAW_LOG_CHARS) "..." else ""}'"
            )

            val followup = extractFollowupQuestions(response)
            Log.i(FOLLOWUP_TAG, "Followup Questions ::: $followup")

            // Basic assertions
            assertTrue("no chunks emitted", chunkCount > 0)
            assertTrue("empty response", response.isNotBlank())

            // 参考：厳格JSONのときは 3件ある前提
            val fromJson = parseFollowupsFromJsonStrict(response)
            if (fromJson != null) {
                assertTrue("expected exactly 3 followups in JSON", fromJson.size == 3)
            }
            Log.w(TAG, "== End Q&A =================================================")
        }

        val c1 = CountDownLatch(1)
        SLMModule.cleanUp(model) { c1.countDown() }
        assertTrue(c1.await(15, TimeUnit.SECONDS))

        Log.w(TAG, "== Finished =================================================")
    }
}