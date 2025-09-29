// app/src/androidTest/java/com/negi/survey/vm/AiViewModelInstrumentationTest.kt
package com.negi.survey.vm

import android.content.Context
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Instrumentation tests for AiViewModel with a real on-device model.
 * Notes:
 * - We intentionally reuse a single Repository/ViewModel instance across tests to amortize model load.
 * - Each test run resets ViewModel observable states, so cross-test interference is minimal.
 * - Marked as @LargeTest because it runs a real model and may take time.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AiViewModelInstrumentationTest {

    @get:Rule val modelRule = ModelAssetRule()

    private lateinit var appCtx: Context
    private lateinit var repo: MediaPipeRepository
    private lateinit var vm: AiViewModel

    @Before
    fun setUp() = runBlocking {
        appCtx = modelRule.context

        // Ensure the native/MediaPipe-backed model is loaded from filesDir (prepared by the Rule).
        InferenceModel.getInstance(appCtx).ensureLoaded(modelRule.internalModel.absolutePath)

        // Lazily reuse singletons across tests to avoid repeated init cost.
        if (!::repo.isInitialized) repo = MediaPipeRepository(appCtx)
        if (!::vm.isInitialized) vm = AiViewModel(repo, timeout_ms = 120_000)
        else vm.resetStates(keepError = false)
    }

    @After
    fun tearDown() {
        // We intentionally do not close the model/repo to speed up subsequent tests.
        // If isolation is desired, call vm.cancel() or resetStates() here.
    }

    // ----- Shared test helper -------------------------------------------------

    /**
     * Runs one end-to-end evaluation and asserts minimal invariants.
     * Waits for the first stream chunk and for completion (loading=false).
     */
    private suspend fun runOnce(
        prompt: String = "Please score this answer and give a follow-up question.",
        firstChunkTimeoutMs: Long = 60_000L,
        completeTimeoutMs: Long = 120_000L,
        minStreamChars: Int = 8
    ) {
        val job: Job = vm.evaluateAsync(prompt)
        try {
            // 1) At least one streamed chunk should arrive.
            withTimeout(firstChunkTimeoutMs) {
                vm.stream.filter { it.length >= minStreamChars }.first()
            }
            // 2) Wait until the ViewModel reports completion.
            withTimeout(completeTimeoutMs) {
                vm.loading.filter { it == false }.first()
            }
            // 3) Basic sanity checks (model-dependent => keep lenient).
            assertNull("error should be null", vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue("score out of range: $score", score in 0..100)
            assertTrue("followupQuestion was blank", !vm.followupQuestion.value.isNullOrBlank())
            assertTrue("stream was empty", vm.stream.value.isNotEmpty())
            assertNotNull("raw output should be set", vm.raw.value)
        } finally {
            // Safe to cancel even if already completed.
            job.cancel()
        }
    }

    // ----- Repeated real-model smoke tests (12 runs) --------------------------

    @Test fun canUseRealModel()   = runBlocking { runOnce() }
    @Test fun canUseRealModel2()  = runBlocking { runOnce() }
    @Test fun canUseRealModel3()  = runBlocking { runOnce() }
    @Test fun canUseRealModel4()  = runBlocking { runOnce() }
    @Test fun canUseRealModel5()  = runBlocking { runOnce() }
    @Test fun canUseRealModel6()  = runBlocking { runOnce() }
    @Test fun canUseRealModel7()  = runBlocking { runOnce() }
    @Test fun canUseRealModel8()  = runBlocking { runOnce() }
    @Test fun canUseRealModel9()  = runBlocking { runOnce() }
    @Test fun canUseRealModel10() = runBlocking { runOnce() }
    @Test fun canUseRealModel11() = runBlocking { runOnce() }
    @Test fun canUseRealModel12() = runBlocking { runOnce() }

    // ----- Extra behavior tests (optional but useful) -------------------------

    @Test
    fun cancelsCleanly() = runBlocking {
        // Start evaluation, wait until it begins streaming, then cancel.
        val job = vm.evaluateAsync("Please score and then keep chatting...")
        try {
            withTimeout(30_000) {
                vm.stream.filter { it.isNotEmpty() }.first()
            }
        } finally {
            vm.cancel()
            job.cancel()
        }
        // Ensure VM reports completion and 'cancelled' error.
        withTimeout(30_000) {
            vm.loading.filter { it == false }.first()
        }
        assertEquals("cancelled", vm.error.value)
    }

    @Test
    fun timesOutProperly() = runBlocking {
        // Run with a very small timeout to force timeout path.
        val job = vm.evaluateAsync(
            prompt = "Please score this answer and give a follow-up question.",
            timeoutMs = 1_000 // 1s
        )
        try {
            withTimeout(30_000) {
                vm.loading.filter { it == false }.first()
            }
            assertEquals("timeout", vm.error.value)
        } finally {
            job.cancel()
        }
    }
}
