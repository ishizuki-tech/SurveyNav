package com.negi.surveynav.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Configuration for uploading via the GitHub Contents API.
 * - The token must have permission to write contents to the target repository.
 *   (Fine-grained: Repository access -> selected repo, Permissions -> Contents: Read & Write)
 */
data class GitHubConfig(
    val owner: String,
    val repo: String,
    val token: String,
    val branch: String = "main",
    val pathPrefix: String = "exports"
)

/** Result of a successful upload. */
data class UploadResult(
    /** e.g., https://github.com/<owner>/<repo>/blob/main/exports/file.json */
    val fileUrl: String?,
    /** Commit SHA for the PUT operation. */
    val commitSha: String?
)

/**
 * Minimal GitHub Contents API client using HttpURLConnection (no extra deps).
 *
 * Public API:
 *  - uploadJson(cfg, fileName, content, message)  // common path (uses cfg.pathPrefix)
 *  - uploadJson(owner, repo, branch, path, token, content, message) // low-level
 *  - diagnoseAuth(cfg) // quick auth & repo-access diagnostics
 */
object GitHubUploader {

    // ----------------------------
    // Public API (suspend)
    // ----------------------------

    /**
     * Uploads JSON text to `<cfg.pathPrefix>/<fileName>` (creates or updates).
     */
    suspend fun uploadJson(
        cfg: GitHubConfig,
        fileName: String,
        content: String,
        message: String = "Upload $fileName via SurveyNav"
    ): UploadResult = uploadJson(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = buildPath(cfg.pathPrefix, fileName),
        token = cfg.token,
        content = content,
        message = message
    )

    /**
     * Uploads JSON text to an explicit path (e.g., "exports/survey_123.json").
     * If the file already exists, fetches its SHA and updates it.
     */
    suspend fun uploadJson(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        content: String,
        message: String = "Upload via SurveyNav"
    ): UploadResult = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "GitHub token is empty." }

        val encodedPath = encodePath(path)
        val sha = getExistingSha(owner, repo, branch, encodedPath, token)

        val url = "https://api.github.com/repos/$owner/$repo/contents/$encodedPath"
        val payload = JSONObject().apply {
            put("message", message)
            put("branch", branch)
            put(
                "content",
                Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            )
            if (sha != null) put("sha", sha)
        }.toString()

        val res = http(
            method = "PUT",
            urlStr = url,
            token = token,
            body = payload
        )
        if (res.code !in 200..299) {
            throw RuntimeException("GitHub PUT failed (${res.code}): ${res.message()}")
        }

        val json = JSONObject(res.body)
        val contentObj = json.optJSONObject("content")
        val commitObj = json.optJSONObject("commit")
        UploadResult(
            fileUrl = contentObj?.optString("html_url")?.takeIf { it.isNotBlank() },
            commitSha = commitObj?.optString("sha")?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Quick diagnostics:
     * - Checks if token is valid (/user)
     * - Checks if repo is visible (/repos/{owner}/{repo})
     *
     * Returns a human-readable message. For UI debugging.
     */
    suspend fun diagnoseAuth(cfg: GitHubConfig): String = withContext(Dispatchers.IO) {
        val t = cfg.token.trim()
        if (t.isEmpty()) return@withContext "Token is empty."

        val u = http("GET", "https://api.github.com/user", t)
        if (u.code != 200) {
            return@withContext "Failed /user (${u.code}): ${u.message()}"
        }

        val r = http(
            "GET",
            "https://api.github.com/repos/${cfg.owner}/${cfg.repo}",
            t
        )
        if (r.code != 200) {
            return@withContext "Failed /repos (${r.code}): ${r.message()}"
        }

        "OK: token valid and repository accessible."
    }

    // ----------------------------
    // Internals
    // ----------------------------

    /** Encode each segment of the path (spaces -> %20) and re-join with '/'. */
    private fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    /** Join prefix and fileName into a clean path (no duplicate slashes). */
    private fun buildPath(prefix: String, fileName: String): String =
        listOf(prefix.trim('/'), fileName.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    /** If file exists on the given branch, return its SHA; otherwise null. */
    private fun getExistingSha(
        owner: String,
        repo: String,
        branch: String,
        encodedPath: String,
        token: String
    ): String? {
        val url = "https://api.github.com/repos/$owner/$repo/contents/$encodedPath?ref=$branch"
        val res = http("GET", url, token)
        if (res.code == 200) {
            val json = JSONObject(res.body)
            return json.optString("sha").takeIf { it.isNotBlank() }
        }
        // 404 -> does not exist, create new
        return null
    }

    // Simple HTTP wrapper around HttpURLConnection
    private data class HttpRes(val code: Int, val body: String)

    private fun http(method: String, urlStr: String, token: String, body: String? = null): HttpRes {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            // "token" works for both classic & fine-grained PATs and is widely compatible
            setRequestProperty("Authorization", "token ${token.trim()}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "SurveyNav/1.0")
            connectTimeout = 20_000
            readTimeout = 30_000
            if (body != null) doOutput = true
        }

        if (body != null) {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        }

        val code = conn.responseCode
        val response = try {
            val stream =
                if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        } finally {
            conn.disconnect()
        }
        return HttpRes(code = code, body = response)
    }

    /** Extracts a helpful message from a GitHub JSON error body, if available. */
    private fun HttpRes.message(): String {
        return runCatching {
            val obj = JSONObject(body)
            buildString {
                val msg = obj.optString("message").takeIf { it.isNotBlank() }
                if (msg != null) append(msg)
                val doc = obj.optString("documentation_url").takeIf { it.isNotBlank() }
                if (doc != null) {
                    if (isNotEmpty()) append(" ")
                    append("[$doc]")
                }
            }.ifEmpty { body.take(400) }
        }.getOrElse { body.take(400) }
    }
}
