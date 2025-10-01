// file: app/src/androidTest/java/com/negi/survey/slm/SlmHelperInstrumentationTest.kt
package com.negi.survey.slm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.ModelAssetRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class SlmHelperInstrumentationTest {

    companion object {
        private const val TAG = "SlmHelperInstrTest"
        private const val TIMEOUT_SEC = 60L

        private lateinit var appCtx: Context
        private lateinit var model: Model
        private val initialized = AtomicBoolean(false)

        @BeforeClass @JvmStatic
        fun beforeClass() {
            appCtx = InstrumentationRegistry.getInstrumentation().targetContext
            Log.i(TAG, "targetContext=${appCtx.packageName}")
        }

        @AfterClass @JvmStatic
        fun afterClass() {
            runCatching { SLM.cleanUp(model) {} }
        }
    }

    @get:Rule
    val modelRule = ModelAssetRule()

    @Before
    fun setUp() {
        if (initialized.compareAndSet(false, true)) {
            model = Model(
                name = "gemma3-local-test",
                taskPath = modelRule.internalModel.absolutePath,
                config = mapOf(
                    ConfigKey.ACCELERATOR to Accelerator.GPU.label,
                    ConfigKey.MAX_TOKENS to 512,
                    ConfigKey.TOP_K to 40,
                    ConfigKey.TOP_P to 0.9f,
                    ConfigKey.TEMPERATURE to 0.7f
                )
            )
            val errHolder = arrayOf<String?>(null)
            SLM.initialize(appCtx, model) { err -> errHolder[0] = err }
            assertEquals("Init error: ${errHolder[0]}", "", errHolder[0])
            assertNotNull("Model instance must be created", model.instance)
        } else {
            assertNotNull("Model instance must exist", model.instance)
        }
        assertFalse("busy should be false at test start", SLM.isBusy(model))
    }

    // ========== ヘルパ ==========

    private fun waitUntilBusy(expectBusy: Boolean, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (SLM.isBusy(model) == expectBusy) return true
            Thread.sleep(15)
        }
        return false
    }

    /** ストリーム完了まで待機して結果を返す（partial→連結、done→最終保障） */
    private fun askAndAwait(prompt: String, timeoutSec: Long = TIMEOUT_SEC): String {

        val done = CountDownLatch(1)
        val sb = StringBuilder()
        SLM.runInference(
            model = model,
            input = prompt,
            listener = { partial, finished ->
                if (partial.isNotEmpty()) {
                    if (!finished) {
                        Log.i(TAG, "partial=$partial")
                        sb.append(partial) // done時の全文と二重連結しない工夫
//                        SLM.cancel(model = model)
                    }
                }
                if (finished) done.countDown()
            },
            onClean = { done.countDown() }
        )

        assertTrue("generation did not finish within $timeoutSec sec",
            done.await(timeoutSec, TimeUnit.SECONDS)
        )

        assertTrue("busy should drop to false after finish", waitUntilBusy(false))

        val out = sb.toString().trim()
        Log.i(TAG, "final(${out.length})=${out.take(200)}")
        assertTrue("output should not be blank", out.isNotBlank())

        if (!SLM.isBusy(model)) {
            SLM.resetSession(model = model)
        }

        return out
    }

    /** 厳密版 ask（cancel 後の健全性確認に使用） */
    private fun askAndAwaitStrict(prompt: String, timeoutSec: Long = TIMEOUT_SEC): String {
        val done = CountDownLatch(1)
        val sb = StringBuilder()

        SLM.runInference(
            model = model,
            input = prompt,
            listener = { partial, finished ->
                if (partial.isNotEmpty()) {
                    Log.i(TAG, "partial=$partial")
                    if (!finished) {
                        sb.append(partial) // done時の全文と二重連結しない工夫
                    }
                }
                if (finished) done.countDown()
            },
            onClean = { done.countDown() }
        )

        assertTrue("busy should be true shortly after start", waitUntilBusy(true))
        assertTrue("generation did not finish within $timeoutSec sec", done.await(timeoutSec, TimeUnit.SECONDS))
        assertTrue("busy should drop to false after finish", waitUntilBusy(false))

        return sb.toString()
    }

    /** 長文を要求してストリーミングを十分継続させる */
    private fun longPrompt(): String =
        "Write a very long, multi-paragraph explanation about Android instrumentation testing, " +
                "including test runners, rules, IdlingResource, synchronization pitfalls, best practices, " +
                "and code snippets. Make it detailed and comprehensive."

    // ========== テストケース ==========

    @Test fun generate_simple_prompt_returns_text1() { assertTrue(askAndAwait("100文字でラーメンの作り方を教えて下さい").isNotBlank()) }
    @Test fun generate_simple_prompt_returns_text2() { assertTrue(askAndAwait("100文字でラーメンの作り方を教えて下さい").isNotBlank()) }
    @Test fun generate_simple_prompt_returns_text3() { assertTrue(askAndAwait("100文字でラーメンの作り方を教えて下さい").isNotBlank()) }
    @Test fun generate_simple_prompt_returns_text4() { assertTrue(askAndAwait("100文字でラーメンの作り方を教えて下さい").isNotBlank()) }

    /** ▼ キャンセル本体の動作検証 */
    @Test
    fun cancel_stops_generation_and_allows_next() {
        val done = CountDownLatch(1)
        SLM.runInference(
            model = model,
            input = longPrompt(),
            listener = { partial, finished ->
                Log.i(TAG, "partial=$partial")
                if (finished) done.countDown()
            },
            onClean = { done.countDown() }
        )

        assertTrue("busy should become true", waitUntilBusy(true))

        val tCancel = SystemClock.elapsedRealtime()

        SLM.cancel(model = model)

        done.await(TIMEOUT_SEC, TimeUnit.SECONDS) // 成否は問わず待ってみる（ログ確認用）

        assertTrue("busy should drop after cancel/done", waitUntilBusy(false))

        val tDone = SystemClock.elapsedRealtime()
        Log.i(TAG, "cancel → done elapsed = ${tDone - tCancel} ms")

        val out2 = askAndAwaitStrict("Confirm you can respond after a cancel.")
        assertTrue("output after cancel should not be blank", out2.isNotBlank())
    }
}
