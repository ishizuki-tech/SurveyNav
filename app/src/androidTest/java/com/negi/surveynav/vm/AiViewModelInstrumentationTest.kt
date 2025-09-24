package com.negi.surveynav.vm

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.surveynav.slm.InferenceModel
import com.negi.surveynav.slm.MediaPipeRepository
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AiViewModelInstrumentationTest {

    private lateinit var context: Context
    private lateinit var repo: MediaPipeRepository
    private lateinit var mockModel: InferenceModel
    private lateinit var partials: MutableSharedFlow<InferenceModel.PartialResult>
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prepareModelMock()
        repo = MediaPipeRepository(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun evaluate_collectsStreamAndScore() = runTest(dispatcher.scheduler) {
        val vm = AiViewModel(repo)

        val job = vm.evaluateAsync("prompt")
        advanceUntilIdle()

        partials.emit(InferenceModel.PartialResult("other", "ignore", done = false))
        partials.emit(
            InferenceModel.PartialResult(
                REQ_ID,
                "{\"score\":80,\"followup\":\"Q1\"}",
                done = false
            )
        )
        advanceUntilIdle()
        partials.emit(InferenceModel.PartialResult(REQ_ID, "", done = true))
        advanceUntilIdle()
        job.join()
        advanceUntilIdle()

        assertEquals(80, vm.score.value)
        assertEquals("Q1", vm.followupQuestion.value)
        assertTrue(vm.stream.value.contains("\"score\":80"))
        assertNull(vm.error.value)
        verify(exactly = 1) { mockModel.cancelRequest(REQ_ID) }
        job.cancel()
    }

    @Test
    fun evaluate_handlesTimeout() = runTest(dispatcher.scheduler) {
        val vm = AiViewModel(repo, timeout_ms = 50)

        val deferred = async { vm.evaluate("prompt") }
        advanceUntilIdle()

        advanceTimeBy(60)
        advanceUntilIdle()

        assertNull(deferred.await())
        assertEquals("timeout", vm.error.value)
        assertTrue(vm.stream.value.isEmpty())
        verify(exactly = 1) { mockModel.startRequest(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { mockModel.cancelRequest(REQ_ID) }
    }

    @Test
    fun evaluateAsync_cancelStopsLoading() = runTest(dispatcher.scheduler) {
        val vm = AiViewModel(repo, timeout_ms = 5_000)

        val job = vm.evaluateAsync("prompt")
        advanceUntilIdle()
        assertTrue(vm.loading.value)

        partials.emit(InferenceModel.PartialResult(REQ_ID, "partial", done = false))
        advanceUntilIdle()

        vm.cancel()
        advanceUntilIdle()

        assertNull(vm.score.value)
        assertEquals("", vm.stream.value)
        assertTrue(!vm.loading.value)
        verify(atLeast = 1) { mockModel.cancelRequest(REQ_ID) }
        job.cancel()
    }

    private fun prepareModelMock() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        partials = MutableSharedFlow(extraBufferCapacity = 8)
        mockModel = mockk(relaxUnitFun = true) {
            every { partialResults } returns partials
            every { startRequest(any(), any(), any(), any(), any()) } returns REQ_ID
            every { cancelRequest(any()) } just Runs
        }
        mockkObject(InferenceModel.Companion)
        every { InferenceModel.getInstance(any()) } returns mockModel
    }

    companion object {
        private const val REQ_ID = "req-1"
    }
}
