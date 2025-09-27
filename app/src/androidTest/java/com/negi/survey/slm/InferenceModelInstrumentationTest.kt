package com.negi.survey.slm

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.ModelAssetRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Method

@RunWith(AndroidJUnit4::class)
class InferenceModelInstrumentationTest {

    @get:Rule val modelRule = ModelAssetRule()

    private lateinit var appCtx: Context
    private lateinit var model: InferenceModel

    private companion object {
        private const val EPS = 1e-6f
    }

    @Before
    fun setUp() = runBlocking {
        appCtx = modelRule.context
        model = freshModel()
        model.ensureLoaded(modelRule.internalModel.absolutePath)
    }

    @After
    fun tearDown() {
        runCatching { model.close() }
        clearModelSingleton()
    }

    @Test
    fun sanitizeTopP_handlesNaNAndPercentages() {
        val defaultTopP = getPrivateFloatField("DEFAULT_TOP_P")
        val sanitizeTopP = getPrivateMethod("sanitizeTopP", java.lang.Float.TYPE)

        val fromNaN      = sanitizeTopP.invoke(model, Float.NaN) as Float
        val fromPercent  = sanitizeTopP.invoke(model, 50f) as Float
        val upperBound   = sanitizeTopP.invoke(model, 150f) as Float

        assertEquals(defaultTopP, fromNaN, EPS)
        assertEquals(0.5f, fromPercent, EPS)
        assertEquals(1.0f, upperBound, EPS)
    }

    @Test
    fun sanitizeTopK_andTemperature_applyBounds() {
        val sanitizeTopK        = getPrivateMethod("sanitizeTopK", Integer.TYPE)
        val sanitizeTemperature = getPrivateMethod("sanitizeTemperature", java.lang.Float.TYPE)
        val defaultTemp         = getPrivateFloatField("DEFAULT_TEMPERATURE")

        val nonNegative = sanitizeTopK.invoke(model, -5) as Int
        val cappedTemp  = sanitizeTemperature.invoke(model, 10f) as Float
        val tempFromNaN = sanitizeTemperature.invoke(model, Float.NaN) as Float

        assertEquals(0, nonNegative)
        assertEquals(5f, cappedTemp, EPS)
        assertEquals(defaultTemp, tempFromNaN, EPS)
    }

    @Test
    fun ensureModelPresent_prefersExistingFiles() {
        val ensureModelPresent = getPrivateMethod(
            "ensureModelPresent",
            Context::class.java,
            String::class.java
        )

        val tmpAbsolute = File(appCtx.cacheDir, "fake-model-abs.litertlm").apply {
            writeText("noop")
        }
        val returnedAbsolute = ensureModelPresent.invoke(model, appCtx, tmpAbsolute.absolutePath) as String
        assertEquals(tmpAbsolute.absolutePath, returnedAbsolute)

        val nameOnly = "fake-model-files.litertlm"
        val inFilesDir = File(appCtx.filesDir, nameOnly).apply {
            writeText("noop")
        }
        val resolvedFromFilesDir = ensureModelPresent.invoke(model, appCtx, nameOnly) as String
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

        val ex = runCatching {
            ensureModelPresent.invoke(model, appCtx, missing)
        }.exceptionOrNull()

        val cause = (ex as? java.lang.reflect.InvocationTargetException)?.targetException ?: ex
        assertTrue(cause is IllegalStateException)
    }

    // ---------- helpers (安定化) ----------

    /** 毎回クリーンなインスタンスを取得（シングルトン汚染対策） */
    private fun freshModel(): InferenceModel {
        clearModelSingleton()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return InferenceModel.getInstance(ctx)
    }

    /** InferenceModel.INSTANCE を null クリア（テスト限定） */
    private fun clearModelSingleton() {
        runCatching {
            val f = InferenceModel::class.java.getDeclaredField("INSTANCE")
            f.isAccessible = true
            f.set(null, null)
        }
    }

    /** private メソッド取得（プリミティブ型を厳密指定し accessible=true） */
    private fun getPrivateMethod(name: String, vararg params: Class<*>): Method =
        InferenceModel::class.java.getDeclaredMethod(name, *params).apply { isAccessible = true }

    /** static float フィールド取得（instance フィールドなら getFloat(model) に変更） */
    private fun getPrivateFloatField(name: String): Float =
        InferenceModel::class.java.getDeclaredField(name).run {
            isAccessible = true
            getFloat(null)
        }
}
