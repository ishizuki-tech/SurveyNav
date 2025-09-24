package com.negi.surveynav.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GitHubConfig(
    val owner: String,
    val repo: String,
    val token: String,
    val branch: String = "main",
    val pathPrefix: String = "exports"
)

data class UploadResult(val fileUrl: String?, val commitSha: String?)

/** onProgress: (sentBytes, totalBytes) -> Unit を追加 */
object GitHubUploader {

    suspend fun uploadJson(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        content: String,
        message: String = "Upload via SurveyNav",
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): UploadResult = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "GitHub token is empty." }

        val encodedPath = path.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

        // 0–10%: 既存SHAチェック
        onProgress(0, 100)
        val sha = getExistingSha(owner, repo, branch, encodedPath, token)
        onProgress(10, 100)

        val url = URL("https://api.github.com/repos/$owner/$repo/contents/$encodedPath")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("Authorization", "token ${token.trim()}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "SurveyNav/1.0")
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 30_000
        }

        val payload = JSONObject().apply {
            put("message", message)
            put("branch", branch)
            put(
                "content",
                Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            )
            if (sha != null) put("sha", sha)
        }.toString()

        // 10–90% をバイト進捗に充てる
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val total = bytes.size
        var sent = 0

        conn.outputStream.use { os: OutputStream ->
            val chunk = 8 * 1024
            var off = 0
            while (off < total) {
                val len = minOf(chunk, total - off)
                os.write(bytes, off, len)
                off += len
                sent = off
                val pct = 10 + ((sent.toDouble() / total) * 80.0).toInt()
                onProgress(pct.toLong(), 100L)
            }
            os.flush()
        }

        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            throw RuntimeException("GitHub PUT failed ($code): $err")
        }

        // 90–100%: 応答処理
        onProgress(95, 100)
        val json = JSONObject(body)
        val contentObj = json.optJSONObject("content")
        val commitObj = json.optJSONObject("commit")
        onProgress(100, 100)
        UploadResult(
            fileUrl = contentObj?.optString("html_url")?.takeIf { it.isNotBlank() },
            commitSha = commitObj?.optString("sha")?.takeIf { it.isNotBlank() }
        )
    }

    private fun getExistingSha(
        owner: String,
        repo: String,
        branch: String,
        encodedPath: String,
        token: String
    ): String? {
        val url = URL("https://api.github.com/repos/$owner/$repo/contents/$encodedPath?ref=$branch")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "token ${token.trim()}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "SurveyNav/1.0")
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                JSONObject(body).optString("sha").takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
