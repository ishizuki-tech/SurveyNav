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
import kotlin.math.max
import kotlin.math.min

/**
 * Immutable configuration for GitHub uploads.
 *
 * @property owner       The repository owner (user or organization).
 * @property repo        The repository name.
 * @property token       A Personal Access Token (classic or fine-grained) with "contents:write" scope.
 * @property branch      Target branch to commit into. Defaults to "main".
 * @property pathPrefix  Base directory inside the repository where files are written (no leading/trailing slash needed).
 */
data class GitHubConfig(
    val owner: String,
    val repo: String,
    val token: String,
    val branch: String = "main",
    val pathPrefix: String = "exports"
)

/**
 * Result of an upload attempt returned by the high-level API.
 *
 * @property fileUrl   HTML web URL of the created/updated file (null on failure or if absent).
 * @property commitSha Commit SHA of the write operation (null on failure or if absent).
 */
data class UploadResult(
    val fileUrl: String?,
    val commitSha: String?
)

/**
 * High-level helper for GitHub "Create or update file contents" REST API.
 *
 * Design goals:
 *  - Provide a suspend API suitable for Android/Kotlin coroutines (IO dispatcher).
 *  - Hide all HTTP plumbing behind a small surface area.
 *  - Offer robust error messages that include HTTP status and a trimmed response body.
 *  - Avoid 409 conflicts by prefetching an existing file's SHA (if present).
 *  - Respect rate limiting via Retry-After and backoff for 429/5xx.
 *  - Report coarse progress in percentage (0..100) that maps well to UI progress bars.
 *
 * API reference:
 * https://docs.github.com/rest/repos/contents#create-or-update-file-contents
 */
object GitHubUploader {

    // ------------------------------------------------------------
    // Public APIs (preferred overloads)
    // ------------------------------------------------------------

    /**
     * Convenience overload that composes the final path using [GitHubConfig.pathPrefix] and a user-supplied relative path.
     *
     * Progress callback semantics:
     *  - Called with an integer 0..100.
     *  - Milestones: 0 (start), ~10 (after SHA lookup), 10..90 (streaming body), 95 (parsing), 100 (done).
     *
     * Typical UI usage:
     *  - Map the integer directly to a progress bar value.
     *  - Consider treating 0..10 as "preparing / checking file" and 10..90 as "uploading".
     */
    suspend fun uploadJson(
        cfg: GitHubConfig,
        relativePath: String,
        content: String,
        message: String = "Upload via SurveyNav",
        onProgressPercent: (Int) -> Unit = { _ -> }
    ): UploadResult = uploadJson(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = buildPath(cfg.pathPrefix, relativePath),
        token = cfg.token,
        content = content,
        message = message,
        onProgressPercent = onProgressPercent
    )

    /**
     * Create or update a UTF-8 text file at the given repository path.
     *
     * @param owner    Repository owner (user or org).
     * @param repo     Repository name.
     * @param branch   Target branch to commit to.
     * @param path     Path within the repo (e.g., "exports/2025/10/result.json"). Slash-separated; each segment will be URL-encoded.
     * @param token    GitHub token with permissions to write contents.
     * @param content  Raw text to be stored; will be encoded as Base64 per API requirements.
     * @param message  Commit message shown in Git history.
     * @param onProgressPercent Callback receiving 0..100 upload progress.
     *
     * Behavior:
     *  1) Fetches existing file metadata to learn its "sha" (if any), avoiding 409 conflict on update.
     *  2) Streams a PUT with a fixed content length to reduce buffering and enable predictable progress.
     *  3) Retries on 429/5xx with Retry-After support, up to [maxAttempts] (see executeWithRetry).
     *  4) Parses the response JSON to return the public file URL and commit SHA.
     *
     * Throws:
     *  - [IOException] for network errors or malformed responses.
     *  - [HttpFailureException] for non-retryable HTTP errors (e.g., 400/401/403/404/409).
     */
    suspend fun uploadJson(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        content: String,
        message: String = "Upload via SurveyNav",
        onProgressPercent: (Int) -> Unit = { _ -> }
    ): UploadResult = withContext(Dispatchers.IO) {
        // Defensive guard: better to fail fast than to hit GitHub with a bad header.
        require(token.isNotBlank()) { "GitHub token is empty." }

        // Encode the path safely per segment (spaces => %20, keep '/').
        val encodedPath = encodePath(path)

        // --- Phase 1: 0..10% — probe for existing file SHA to prevent 409 conflicts on update ---
        onProgressPercent(0)
        val existingSha = getExistingSha(owner, repo, branch, encodedPath, token)
        onProgressPercent(10)

        // Build the JSON payload required by the Contents API.
        // Note: content MUST be base64-encoded UTF-8. We also pass "branch" and "sha" (when updating).
        val payload = JSONObject().apply {
            put("message", message)
            put("branch", branch)
            put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (existingSha != null) put("sha", existingSha)
        }.toString()

        // --- Phase 2: 10..90% — stream the PUT body to GitHub with deterministic progress updates ---
        val url = URL("https://api.github.com/repos/$owner/$repo/contents/$encodedPath")
        val requestBytes = payload.toByteArray(Charsets.UTF_8)
        val total = requestBytes.size

        // Writes the request body in fixed-length streaming mode to:
        //  - Avoid chunked transfer encoding.
        //  - Improve progress accuracy for large payloads.
        //  - Reduce memory footprint compared to buffering the entire body.
        val writeBody: (HttpURLConnection) -> Unit = { conn ->
            conn.setFixedLengthStreamingMode(total)
            conn.outputStream.use { os: OutputStream ->
                val chunk = 8 * 1024
                var off = 0
                while (off < total) {
                    val len = min(chunk, total - off)
                    os.write(requestBytes, off, len)
                    off += len
                    // Map byte progress into 10..90% range, clamped just in case.
                    val pct = 10 + ((off.toDouble() / total) * 80.0).toInt()
                    onProgressPercent(min(90, pct))
                }
                os.flush()
            }
        }

        // Execute the HTTP request, handling transient failures (429/5xx) with backoff/Retry-After.
        val response = executeWithRetry(
            method = "PUT",
            url = url,
            token = token,
            writeBody = writeBody
        )

        // --- Phase 3: 90..100% — parse JSON and surface result fields to the caller ---
        onProgressPercent(95)
        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            // If GitHub ever returns non-JSON (rare), bubble up a clear error to the caller.
            throw IOException("Malformed JSON in GitHub response: ${e.message}", e)
        }
        val contentObj = json.optJSONObject("content")
        val commitObj = json.optJSONObject("commit")
        onProgressPercent(100)

        // Extract the human-readable file HTML URL and the commit SHA (if present).
        UploadResult(
            fileUrl = contentObj?.optString("html_url")?.takeIf { it.isNotBlank() },
            commitSha = commitObj?.optString("sha")?.takeIf { it.isNotBlank() }
        )
    }

    // ------------------------------------------------------------
    // Backward-compatible APIs (deprecated)
    // ------------------------------------------------------------

    /**
     * Deprecated overload that historically treated (sent,total) as (percent,100).
     * Kept for source compatibility; internally delegates to the preferred API.
     *
     * Migrate to: [uploadJson] with `onProgressPercent: (Int) -> Unit`.
     */
    @Deprecated("Use the onProgressPercent(Int) overload instead.")
    suspend fun uploadJson(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        content: String,
        message: String = "Upload via SurveyNav",
        onProgress: (sent: Long, total: Long) -> Unit
    ): UploadResult = uploadJson(
        owner = owner,
        repo = repo,
        branch = branch,
        path = path,
        token = token,
        content = content,
        message = message
    ) { pct -> onProgress(pct.toLong(), 100L) }

    /**
     * Deprecated convenience overload mirroring the legacy progress signature.
     * Kept for source compatibility; internally delegates to the preferred API.
     */
    @Deprecated("Use the onProgressPercent(Int) overload instead.")
    suspend fun uploadJson(
        cfg: GitHubConfig,
        relativePath: String,
        content: String,
        message: String = "Upload via SurveyNav",
        onProgress: (sent: Long, total: Long) -> Unit
    ): UploadResult = uploadJson(
        cfg, relativePath, content, message
    ) { pct -> onProgress(pct.toLong(), 100L) }

    // ------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------

    /**
     * URL-encode each path segment independently so that:
     *  - Directory separators ('/') are preserved as actual path delimiters.
     *  - Unsafe characters inside a segment are percent-encoded (e.g., spaces => %20).
     *
     * Example:
     *   "exports/2025 10/report.json"  =>  "exports/2025%2010/report.json"
     */
    private fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") {
            // URLEncoder turns spaces into '+'. GitHub URLs commonly use '%20'.
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    /**
     * Join a prefix directory and a relative file path with exactly one slash between them,
     * trimming any accidental leading/trailing slashes from inputs.
     *
     * Example:
     *  prefix="exports/", relative="/2025/10/out.json"  =>  "exports/2025/10/out.json"
     */
    private fun buildPath(prefix: String, relative: String): String =
        listOf(prefix.trim('/'), relative.trim('/')).filter { it.isNotBlank() }.joinToString("/")

    /**
     * Minimal container for an HTTP response needed by the uploader.
     * We keep headers so we can read Retry-After and other diagnostic values.
     */
    private data class HttpResponse(
        val code: Int,
        val body: String,
        val headers: Map<String, List<String>>
    )

    /**
     * Marker exception for errors that are potentially recoverable via retry (429/5xx).
     *
     * @property code              HTTP status code (429 or 5xx).
     * @property body              Response body (trimmed later for logs).
     * @property retryAfterSeconds If present, the number of seconds suggested by the server to wait.
     */
    private class TransientHttpException(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?
    ) : IOException()

    /**
     * Exception representing a non-retryable HTTP failure (e.g., 400/401/403/404/409).
     * The message includes a trimmed slice of the server response for quick diagnosis.
     */
    private class HttpFailureException(val code: Int, val body: String) :
        IOException("GitHub request failed ($code): ${body.take(1024)}")

    /**
     * Execute an HTTP request with a write-body callback and standardized headers/timeouts.
     *
     * Features:
     *  - Adds the modern "Bearer" authentication scheme (classic "token" would also work).
     *  - Uses a specific GitHub API version for forward compatibility.
     *  - Retries transient failures (429/5xx). If the server supplies "Retry-After",
     *    that duration takes precedence; otherwise, exponential backoff is applied (0.5s, 1s, 2s).
     *  - Bubbles up non-retryable errors immediately with clear messaging.
     *
     * @param method   HTTP verb (PUT for uploads).
     * @param url      Fully formed endpoint URL.
     * @param token    GitHub PAT for Authorization.
     * @param writeBody Callback that writes the request body into the connection.
     * @param connectTimeoutMs   Connection timeout in milliseconds.
     * @param readTimeoutMs      Read timeout in milliseconds.
     * @param maxAttempts        Maximum total attempts including the first try.
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
                // Prefer "Bearer" per current GH docs; "token" still works if needed.
                setRequestProperty("Authorization", "Bearer ${token.trim()}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "SurveyNav/1.0")
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }

            try {
                // Delegates request body streaming to the caller provided lambda.
                writeBody(conn)

                val code = conn.responseCode
                val headers = conn.headerFields.filterKeys { it != null }

                if (code in 200..299) {
                    // Success path: read response and return immediately (no retry).
                    val body = conn.inputStream.use(::readAll)
                    return HttpResponse(code, body, headers)
                } else {
                    // Failure path: drain error stream for diagnostics (often contains JSON with "message").
                    val errBody = conn.errorStream?.use(::readAll).orEmpty()

                    // Retry policy: 429 (rate limit) and 5xx are considered transient.
                    if (code == 429 || code in 500..599) {
                        val retryAfterSec = parseRetryAfterSeconds(headers)
                        throw TransientHttpException(code, errBody, retryAfterSec)
                    } else {
                        // Non-retryable: auth errors, not found, conflict without fix, etc.
                        throw HttpFailureException(code, errBody)
                    }
                }
            } catch (e: TransientHttpException) {
                // Construct a readable message and decide sleep duration.
                lastError = IOException("Transient HTTP ${e.code}: ${e.body.take(256)}", e)
                if (attempt >= maxAttempts) throw lastError

                // If server dictates Retry-After, prefer it. Otherwise backoff exponentially.
                val backoffMsDefault = 500L shl (attempt - 1) // 0.5s, 1s, 2s, ...
                val waitMs = e.retryAfterSeconds?.let { max(0L, it * 1000L) } ?: backoffMsDefault
                delay(waitMs)
            } catch (e: IOException) {
                // Network I/O errors (timeouts, connection resets, etc.) may be transient.
                lastError = e
                if (attempt >= maxAttempts) throw e
                val backoffMs = 500L shl (attempt - 1)
                delay(backoffMs)
            } finally {
                // Always release the underlying socket/resources.
                conn.disconnect()
            }
        }

        // Should be unreachable; safety net in case the loop exits abnormally.
        throw lastError ?: IOException("HTTP failed after $maxAttempts attempts (unknown error)")
    }

    /**
     * Parse the Retry-After header (if present) into whole seconds.
     * GitHub typically returns a delta-seconds value for API requests.
     * If absent or malformed, returns null so the caller can use a default backoff.
     */
    private fun parseRetryAfterSeconds(headers: Map<String, List<String>>): Long? {
        val value = headers["Retry-After"]?.firstOrNull() ?: return null
        return value.toLongOrNull()
    }

    /**
     * Read an entire InputStream into a UTF-8 String safely and efficiently.
     * Uses a BufferedReader under the hood and ensures the stream is closed.
     */
    private fun readAll(stream: InputStream): String =
        stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)

    /**
     * Retrieve the existing file's SHA, if any, to allow an update without conflict.
     *
     * Why this matters:
     *  - GitHub Contents API requires the current blob SHA for updates (optimistic concurrency).
     *  - If omitted and the file exists, the server returns 409 (Conflict).
     *
     * Failure handling:
     *  - Any non-200 response is treated as "file does not exist" to simplify the call flow.
     *  - Network/JSON exceptions are swallowed and interpreted as "no SHA" (best effort).
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
            setRequestProperty("Authorization", "Bearer ${token.trim()}")
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
            // Treat network/parse issues as "not found"; upload path will proceed as a create.
            null
        } finally {
            conn.disconnect()
        }
    }
}
