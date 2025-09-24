package com.negi.surveynav.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GitHubUploadWorker のリモートパス生成をデバイス上で検証する Instrumented Test。
 * buildRemotePath は private メソッドのため、反射を用いて呼び出している。
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest2 {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun buildRemotePath_withoutPrefix_returnsFileNameOnly() {
        val path = invokeBuildRemotePath(prefix = "", fileName = "survey.json")
        assertEquals("survey.json", path)
    }

    @Test
    fun buildRemotePath_trimsPrefixSlashes() {
        val path = invokeBuildRemotePath(prefix = "exports/", fileName = "survey.json")
        assertEquals("exports/survey.json", path)
    }

    @Test
    fun buildRemotePath_removesLeadingSlashFromFileName() {
        val path = invokeBuildRemotePath(prefix = "exports", fileName = "/survey.json")
        assertEquals("exports/survey.json", path)
    }

    private fun invokeBuildRemotePath(prefix: String, fileName: String): String {
        val worker = TestListenableWorkerBuilder<GitHubUploadWorker>(context)
            .setInputData(androidx.work.workDataOf())
            .build()

        val method = GitHubUploadWorker::class.java.getDeclaredMethod(
            "buildRemotePath",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(worker, prefix, fileName) as String
    }
}
