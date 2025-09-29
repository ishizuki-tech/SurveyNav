//package com.negi.survey
//
//import androidx.test.ext.junit.runners.AndroidJUnit4
//import com.negi.survey.slm.InferenceModel
//import kotlinx.coroutines.runBlocking
//import kotlinx.coroutines.withTimeout
//import org.junit.Assert.assertTrue
//import org.junit.Rule
//import org.junit.Test
//import org.junit.runner.RunWith
//
//@RunWith(AndroidJUnit4::class)
//class AnyInstrumentationTest {
//    @get:Rule val modelRule = ModelAssetRule()
//    @Test
//    fun canUseRealModel() = runBlocking {
//        val file = modelRule.internalModel
//        val ctx = modelRule.context
//        InferenceModel.getInstance(ctx).ensureLoaded(file.absolutePath)
//        val chunks = mutableListOf<String>()
//        withTimeout(30_000) {
//            MediaPipeRepository(ctx)
//                .request("""Return a tiny JSON like {"score":80}""")
//                .collect { chunks += it }
//        }
//        assertTrue(chunks.isNotEmpty())
//    }
//    @Test
//    fun canUseRealModel2() = runBlocking {
//        val file = modelRule.internalModel
//        val ctx = modelRule.context
//        InferenceModel.getInstance(ctx).ensureLoaded(file.absolutePath)
//        val chunks = mutableListOf<String>()
//        withTimeout(30_000) {
//            MediaPipeRepository(ctx)
//                .request("""Return a tiny JSON like {"score":80}""")
//                .collect { chunks += it }
//        }
//        assertTrue(chunks.isNotEmpty())
//    }
//    @Test
//    fun canUseRealModel3() = runBlocking {
//        val file = modelRule.internalModel
//        val ctx = modelRule.context
//        InferenceModel.getInstance(ctx).ensureLoaded(file.absolutePath)
//        val chunks = mutableListOf<String>()
//        withTimeout(30_000) {
//            MediaPipeRepository(ctx)
//                .request("""Return a tiny JSON like {"score":80}""")
//                .collect { chunks += it }
//        }
//        assertTrue(chunks.isNotEmpty())
//    }
//}
