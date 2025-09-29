package com.negi.survey.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlin.math.min

data class GitHubConfig(
    val owner: String,
    val repo: String,
    val token: String,
    val branch: String = "main",
    val pathPrefix: String = "exports"
)

data class UploadResult(
    val fileUrl: String?,
    val commitSha: String?
)

/**
 * Uploader for GitHub "Create or update file contents" API.
 *
 * Key features:
 * - Progress callback reports percentage (0..100).
 * - Safe path encoding per path segment.
 * - Detects existing file SHA to avoid 409 conflicts.
 * - Robust error messages with HTTP code and response body.
 * - Simple retry for transient errors (429/5xx) with exponential backoff.
 *
 * API doc: https://docs.github.com/rest/repos/contents#create-or-update-file-contents
 */
object GitHubUploader {

    /**
     * Convenience wrapper using [GitHubConfig].
     */
    suspend fun uploadJson(
        cfg: GitHubConfig,
        relativePath: String,
        content: String,
        message: String = "Upload via SurveyNav",
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }
    ): UploadResult = uploadJson(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = buildPath(cfg.pathPrefix, relativePath),
        token = cfg.token,
        content = content,
        message = message,
        onProgress = onProgress
    )

    /**
     * Upload (create or update) a text file as Base64 to GitHub Contents API.
     *
     * @param owner   Repository owner (user or org)
     * @param repo    Repository name
     * @param branch  Target branch
     * @param path    File path within the repo. Each segment is URL-encoded safely.
     * @param token   Personal access token (classic or fine-grained). Must be non-blank.
     * @param content Raw text content. Will be UTF-8 encoded then Base64(NO_WRAP).
     * @param message Commit message
     * @param onProgress Progress callback. Called with (progressPercent, 100).
     */
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
        val encodedPath = encodePath(path)

        // --- 0–10%: Check existing SHA (if file already exists) ---
        onProgress(0, 100)
        val existingSha = getExistingSha(owner, repo, branch, encodedPath, token)
        onProgress(10, 100)

        // Build request payload
        val payload = JSONObject().apply {
            put("message", message)
            put("branch", branch)
            put(
                "content",
                Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            )
            if (existingSha != null) put("sha", existingSha)
        }.toString()

        // --- 10–90%: Stream request body with progress ---
        val url = URL("https://api.github.com/repos/$owner/$repo/contents/$encodedPath")
        val requestBytes = payload.toByteArray(Charsets.UTF_8)
        val total = requestBytes.size
        val writeBody: (HttpURLConnection) -> Unit = { conn ->
            conn.outputStream.use { os: OutputStream ->
                val chunk = 8 * 1024
                var sent = 0
                var off = 0
                while (off < total) {
                    val len = min(chunk, total - off)
                    os.write(requestBytes, off, len)
                    off += len
                    sent = off
                    val pct = 10 + ((sent.toDouble() / total) * 80.0).toInt()
                    onProgress(pct.toLong(), 100L)
                }
                os.flush()
            }
        }

        // Execute with simple retry for transient errors
        val response = executeWithRetry(
            method = "PUT",
            url = url,
            token = token,
            writeBody = writeBody
        )

        // --- 90–100%: Parse response ---
        onProgress(95, 100)
        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            throw IOException("Malformed JSON in GitHub response: ${e.message}", e)
        }
        val contentObj = json.optJSONObject("content")
        val commitObj = json.optJSONObject("commit")
        onProgress(100, 100)

        UploadResult(
            fileUrl = contentObj?.optString("html_url")?.takeIf { it.isNotBlank() },
            commitSha = commitObj?.optString("sha")?.takeIf { it.isNotBlank() }
        )
    }

    // --------------------
    // Internal helpers
    // --------------------

    /** Encode each path segment safely; keeps slashes between segments. */
    private fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") {
            // URLEncoder encodes spaces as '+', convert to %20 to match GitHub URLs
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    /** Join prefix and relative path with a single slash boundary. */
    private fun buildPath(prefix: String, relative: String): String =
        listOf(prefix.trim('/'), relative.trim('/')).filter { it.isNotBlank() }.joinToString("/")

    private data class HttpResponse(val code: Int, val body: String, val headers: Map<String, List<String>>)

    /**
     * Execute an HTTP request with:
     * - Common GitHub headers
     * - Timeouts
     * - Body writer callback
     * - Retry for 429 / 5xx with exponential backoff (3 attempts)
     */
    private suspend fun executeWithRetry(
        method: String,
        url: URL,
        token: String,
        writeBody: (HttpURLConnection) -> Unit,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 30_000,
        maxAttempts: Int = 3
    ): HttpResponse {
        var attempt = 0
        var lastError: IOException? = null

        while (attempt < maxAttempts) {
            attempt++
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                doOutput = true
                setRequestProperty("Authorization", "token ${token.trim()}") // "Bearer" also works
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "SurveyNav/1.0")
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }

            try {
                writeBody(conn)

                val code = conn.responseCode
                val headers = conn.headerFields.filterKeys { it != null }

                val body = if (code in 200..299) {
                    conn.inputStream.use(::readAll)
                } else {
                    val err = conn.errorStream?.use(::readAll).orEmpty()
                    // Retry on 429 / 5xx. GitHub returns 409 for SHA mismatch (do not retry).
                    if (code == 429 || code in 500..599) {
                        throw TransientHttpException(code, err)
                    } else {
                        throw HttpFailureException(code, err)
                    }
                }
                return HttpResponse(code, body, headers)
            } catch (e: TransientHttpException) {
                lastError = IOException("Transient HTTP ${e.code}: ${e.body.take(512)}", e)
                if (attempt >= maxAttempts) throw lastError
                // Backoff: 0.5s, 1s, 2s...
                val backoffMs = 500L shl (attempt - 1)
                delay(backoffMs)
            } catch (e: IOException) {
                lastError = e
                if (attempt >= maxAttempts) throw e
                val backoffMs = 500L shl (attempt - 1)
                delay(backoffMs)
            } finally {
                conn.disconnect()
            }
        }

        throw lastError ?: IOException("HTTP failed after $maxAttempts attempts (unknown error)")
    }

    private class TransientHttpException(val code: Int, val body: String) : IOException()
    private class HttpFailureException(val code: Int, val body: String) :
        IOException("GitHub request failed ($code): ${body.take(2048)}")

    /** Read entire stream as UTF-8 string. */
    private fun readAll(stream: InputStream): String =
        stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)

    /**
     * Query GitHub Contents API to obtain existing file SHA (if any).
     * Returns null when the file does not exist or on non-200 status.
     */
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
                val body = conn.inputStream.use(::readAll)
                JSONObject(body).optString("sha").takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) {
            null // Non-existent or transient error → treat as "no SHA"
        } finally {
            conn.disconnect()
        }
    }
}
