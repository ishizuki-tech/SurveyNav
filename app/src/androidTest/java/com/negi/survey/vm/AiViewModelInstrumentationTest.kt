// app/src/androidTest/java/com/negi/survey/vm/AiViewModelInstrumentationTest.kt
package com.negi.survey.vm

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.negi.survey.ModelAssetRule
import com.negi.survey.slm.InferenceModel
import com.negi.survey.slm.MediaPipeRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiViewModelInstrumentationTest {

    @get:Rule val modelRule = ModelAssetRule()

    // —— 単一インスタンスをテスト内で共有 ——
    private lateinit var appCtx: Context
    private lateinit var repo: MediaPipeRepository
    private lateinit var vm: AiViewModel

    @Before
    fun setUp() = runBlocking {
        appCtx = modelRule.context
        // モデルを先にロード（filesDir 上のパスを Rule が用意）
        InferenceModel.getInstance(appCtx).ensureLoaded(modelRule.internalModel.absolutePath)

        // 既存を再利用（未初期化なら作成）
        if (!::repo.isInitialized) repo = MediaPipeRepository(appCtx)
        if (!::vm.isInitialized) vm = AiViewModel(repo, timeout_ms = 120_000)
    }

    @After
    fun tearDown() {
        // ここでは close/cancel はしない（次テストでも同一 VM を使う想定）
        // 必要なら vm.cancel() を追加
    }

    @Test
    fun canUseRealModel() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel2() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel3() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel4() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel5() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel6() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel7() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel8() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel9() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel10() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel11() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }

    @Test
    fun canUseRealModel12() = runBlocking {
        val job = vm.evaluateAsync("Please score this answer and give a follow-up question.")
        try {
            // 1) まずチャンクが1つ以上来ること
            withTimeout(60_000) {
                vm.stream.filter { it.length >= 8 }.first()
            }
            // 2) 完了（loading=false）まで到達
            withTimeout(120_000) {
                vm.loading.filter { it == false }.first()
            }
            // 3) 最低限の整合性チェック（実モデル依存のため緩め）
            assertNull(vm.error.value)
            val score = vm.score.value ?: fail("score was null")
            assertTrue(score in 0..100)
            assertTrue(!vm.followupQuestion.value.isNullOrBlank())
            assertTrue(vm.stream.value.isNotEmpty())
        } finally {
            // 後続テストのために明示キャンセル（評価は完了済みでも安全）
            job.cancel()
        }
    }
}
