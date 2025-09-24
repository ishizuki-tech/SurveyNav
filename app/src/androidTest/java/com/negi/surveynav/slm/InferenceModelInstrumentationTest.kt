package com.negi.surveynav.slm

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InferenceModelInstrumentationTest {

    private lateinit var context: Context
    private lateinit var model: InferenceModel

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        model = InferenceModel.getInstance(context)
    }

    @After
    fun tearDown() {
        model.close()
    }

    @Test
    fun sanitizeTopP_handlesNaNAndPercentages() {
        val defaultTopP = getPrivateFloatField("DEFAULT_TOP_P")
        val sanitizeTopP = getPrivateMethod("sanitizeTopP", Float::class.javaPrimitiveType!!)

        val fromNaN = sanitizeTopP.invoke(model, Float.NaN) as Float
        val fromPercent = sanitizeTopP.invoke(model, 50f) as Float
        val upperBound = sanitizeTopP.invoke(model, 150f) as Float

        assertEquals(defaultTopP, fromNaN)
        assertEquals(0.5f, fromPercent)
        assertEquals(1.0f, upperBound)
    }

    @Test
    fun sanitizeTopK_andTemperature_applyBounds() {
        val sanitizeTopK = getPrivateMethod("sanitizeTopK", Int::class.javaPrimitiveType!!)
        val sanitizeTemperature = getPrivateMethod("sanitizeTemperature", Float::class.javaPrimitiveType!!)
        val defaultTemp = getPrivateFloatField("DEFAULT_TEMPERATURE")

        val nonNegative = sanitizeTopK.invoke(model, -5) as Int
        val cappedTemp = sanitizeTemperature.invoke(model, 10f) as Float
        val tempFromNaN = sanitizeTemperature.invoke(model, Float.NaN) as Float

        assertEquals(0, nonNegative)
        assertEquals(5f, cappedTemp)
        assertEquals(defaultTemp, tempFromNaN)
    }

    @Test
    fun ensureModelPresent_prefersExistingFiles() {
        val ensureModelPresent = getPrivateMethod(
            "ensureModelPresent",
            Context::class.java,
            String::class.java
        )

        val tmpAbsolute = File(context.cacheDir, "fake-model-abs.litertlm").apply {
            writeText("noop")
        }
        val returnedAbsolute = ensureModelPresent.invoke(model, context, tmpAbsolute.absolutePath) as String
        assertEquals(tmpAbsolute.absolutePath, returnedAbsolute)

        val nameOnly = "fake-model-files.litertlm"
        val inFilesDir = File(context.filesDir, nameOnly).apply {
            writeText("noop")
        }
        val resolvedFromFilesDir = ensureModelPresent.invoke(model, context, nameOnly) as String
        assertEquals(inFilesDir.absolutePath, resolvedFromFilesDir)

        tmpAbsolute.delete()
        inFilesDir.delete()
    }

    @Test
    fun ensureModelPresent_throwsWhenMissing() {
        val ensureModelPresent = getPrivateMethod(
            "ensureModelPresent",
            Context::class.java,
            String::class.java
        )
        val missing = "non-existent-model-${System.currentTimeMillis()}.litertlm"
        val exception = runCatching {
            ensureModelPresent.invoke(model, context, missing)
        }.exceptionOrNull()

        val cause = (exception as? java.lang.reflect.InvocationTargetException)?.targetException ?: exception
        assertTrue(cause is IllegalStateException)
    }

    private fun getPrivateMethod(name: String, vararg params: Class<*>): java.lang.reflect.Method =
        InferenceModel::class.java.getDeclaredMethod(name, *params).apply { isAccessible = true }

    private fun getPrivateFloatField(name: String): Float =
        InferenceModel::class.java.getDeclaredField(name).run {
            isAccessible = true
            getFloat(null)
        }
}
