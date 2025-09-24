package com.negi.surveynav.slm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InferenceModelLoadInstrumentationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val model by lazy { InferenceModel.getInstance(context) }

    @After
    fun tearDown() {
        model.close()
    }

    @Test
    fun ensureModelLoadsFromFilesDir() = runBlocking {
        val modelFile = File(context.filesDir, "models/gemma-3n-E4B-it-int4.litertlm")
        assumeTrue(
            "Model file not found at ${modelFile.absolutePath}. Push it via adb before running this test.",
            modelFile.exists()
        )

        model.ensureLoaded(modelFile.absolutePath)
        assertTrue(modelFile.exists())
    }
}
