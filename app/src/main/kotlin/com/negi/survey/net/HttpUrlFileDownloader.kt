package com.negi.survey.net

import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.pow

/**
 * Robust HTTP downloader based on HttpURLConnection with:
 *  - HEAD probe + manual redirects to get final URL, size, and cache validators (ETag/Last-Modified)
 *  - Safe resume using Range/If-Range, with .part and .meta sidecar files
 *  - Exponential backoff with optional Retry-After honoring
 *  - Progress callback and SHA-256 verification
 *  - Optional Hugging Face bearer token (applied only to huggingface.co hosts)
 *
 * Notes:
 *  - We use readTimeout for both first byte and stall detection (HttpURLConnection cannot switch timeouts mid-stream).
 *    The separate firstByteTimeoutMs is only applied to the HEAD probe to fail fast on dead links.
 *  - For GET we enable instanceFollowRedirects because presigned endpoints may still redirect (e.g., S3/CloudFront).
 */
class HttpUrlFileDownloader(
    private val hfToken: String? = null,   // "hf_xxx" (only added for huggingface.co)
    private val debugLogs: Boolean = true
) {
    private val tag = "HttpUrlFileDl"

    suspend fun downloadToFile(
        url: String,                       // e.g., huggingface.co/.../resolve/...
        dst: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
        expectedSha256: String? = null,
        // Timeouts / retry / buffers
        connectTimeoutMs: Int = 20_000,
        firstByteTimeoutMs: Int = 30_000,   // applied to HEAD
        stallTimeoutMs: Int = 90_000,       // applied to GET reads
        ioBufferBytes: Int = 1 * 1024 * 1024,
        maxRetries: Int = 3
    ) = withContext(Dispatchers.IO) {
        // Resolve a safe parent directory even if dst has no explicit parent
        val parent = dst.absoluteFile.parentFile
            ?: throw IOException("Invalid dst (no parent): ${dst.absolutePath}")
        parent.mkdirs()

        val part = File(parent, dst.name + ".part")
        val meta = MetaFile(part)

        // Optional early exit: if local file already matches remote size (and optional SHA)
        runCatching { headProbe(url, connectTimeoutMs, firstByteTimeoutMs).total }.getOrNull()
            ?.let { headLen ->
                if (dst.exists() && dst.length() == headLen &&
                    (expectedSha256 == null || sha256(dst).equals(expectedSha256, true))
                ) {
                    onProgress(dst.length(), dst.length())
                    logd("Already complete -> return")
                    return@withContext
                }
            }

        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < maxRetries) {
            try {
                coroutineContext.ensureActive()

                // Always refresh presigned final URL + validators before (re)starting
                val probe = headProbe(url, connectTimeoutMs, firstByteTimeoutMs)
                val total =
                    probe.total ?: throw IOException("Remote doesn't include Content-Length.")
                if (!probe.acceptRanges) throw IOException("Server doesn't advertise range support (Accept-Ranges).")
                var finalUrl = probe.finalUrl

                // Ensure there is enough free space (requested remaining + 50 MiB cushion)
                val already = if (part.exists()) part.length() else 0L
                checkFreeSpaceOrThrow(
                    parent,
                    (total - already).coerceAtLeast(0L) + 50L * 1024 * 1024
                )

                // Persist validators for If-Range
                meta.write(Meta(probe.etag, probe.lastModified, total))

                var resumeFrom = already.coerceIn(0, total)
                var triesOnThisStream = 0

                STREAM@ while (true) {
                    if (triesOnThisStream > 0) {
                        // Refresh presigned URL between stream retries, also validate size consistency.
                        val refreshed = headProbe(url, connectTimeoutMs, firstByteTimeoutMs)
                        if (refreshed.total != null && refreshed.total != total) {
                            throw IOException("Remote size changed (old=$total new=${refreshed.total}).")
                        }
                        finalUrl = refreshed.finalUrl
                    }

                    val conn = openConn(
                        finalUrl,
                        method = "GET",
                        connectTimeoutMs = connectTimeoutMs,
                        readTimeoutMs = stallTimeoutMs,
                        followRedirects = true  // allow 3xx on GET (common with presigned endpoints)
                    )
                    try {
                        setCommonHeaders(conn, finalUrl)
                        if (resumeFrom > 0) {
                            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
                            meta.read()?.let { m ->
                                val ifRange = m.etag ?: m.lastModified
                                if (ifRange != null) conn.setRequestProperty("If-Range", ifRange)
                            }
                        }

                        val code = conn.responseCode

                        // Handle auth errors by re-probing the URL (likely expired token/presign)
                        if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                            logw("GET $code -> refresh HEAD and retry.")
                            triesOnThisStream++
                            resumeFrom = part.length().coerceIn(0, total)
                            continue@STREAM
                        }

                        // Some servers ignore Range and return 200; if resuming, force restart from 0
                        if (code == HttpURLConnection.HTTP_OK && resumeFrom > 0) {
                            logw("200 on resume -> restart from 0.")
                            part.delete()
                            resumeFrom = 0L
                            triesOnThisStream++
                            if (triesOnThisStream <= 3) continue@STREAM
                            throw IOException("Server ignored Range repeatedly.")
                        }

                        // Ensure 206 aligns with requested start
                        if (code == HttpURLConnection.HTTP_PARTIAL) {
                            val start = parseContentRangeStart(conn.getHeaderField("Content-Range"))
                            if (start != null && start != resumeFrom) {
                                logw("Content-Range mismatch: got=$start want=$resumeFrom -> restart from 0.")
                                part.delete()
                                resumeFrom = 0L
                                triesOnThisStream++
                                if (triesOnThisStream <= 3) continue@STREAM
                                throw IOException("Content-Range mismatch repeatedly.")
                            }
                        }
                        // If server says Range Not Satisfiable (416)
                        if (code == 416) {
                            val onDisk = part.length()
                            when {
                                onDisk == total -> {
                                    // Treat as complete: rename .part to dst
                                    if (dst.exists()) dst.delete()
                                    if (!part.renameTo(dst)) {
                                        part.copyTo(dst, overwrite = true); part.delete()
                                    }
                                    if (expectedSha256 != null) {
                                        val gotSha = sha256(dst)
                                        if (!gotSha.equals(expectedSha256, ignoreCase = true)) {
                                            dst.delete()
                                            throw IOException("SHA-256 mismatch after 416: expected=$expectedSha256 got=$gotSha")
                                        }
                                    }
                                    onProgress(total, total)
                                    logd("Completed via 416 reconciliation.")
                                    return@withContext
                                }

                                else -> {
                                    // Our local .part is incompatible; restart from 0
                                    logw("416 with onDisk=$onDisk, total=$total -> restart from 0.")
                                    part.delete()
                                    resumeFrom = 0L
                                    triesOnThisStream++
                                    if (triesOnThisStream <= 3) continue@STREAM
                                    throw IOException("HTTP 416 persisted after retries.")
                                }
                            }
                        }

                        // Non-success responses (except handled above)
                        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                            val snippet = readErrorSnippet(conn)
                            throw IOException("GET HTTP $code${snippet?.let { ": $it" } ?: ""}")
                        }

                        // Stream copy with progress; clamp buffer size to a sane range
                        val bufSize = ioBufferBytes.coerceIn(64 * 1024, 2 * 1024 * 1024)
                        val append = resumeFrom > 0
                        var downloaded = resumeFrom
                        onProgress(downloaded, total)

                        try {
                            conn.inputStream.use { input ->
                                FileOutputStream(part, append).use { fos ->
                                    BufferedOutputStream(fos, bufSize).use { out ->
                                        val buf = ByteArray(bufSize)
                                        while (true) {
                                            coroutineContext.ensureActive()
                                            val n = input.read(buf)
                                            if (n == -1) break
                                            out.write(buf, 0, n)
                                            downloaded += n
                                            onProgress(downloaded, total)
                                        }
                                        out.flush()
                                    }
                                }
                            }
                        } catch (t: SocketTimeoutException) {
                            // Stall timeout: refresh and resume from current .part length
                            logw("Socket timeout (stall). Will resume.")
                            resumeFrom = part.length().coerceIn(0, total)
                            triesOnThisStream++
                            if (triesOnThisStream <= 3) continue@STREAM
                            throw t
                        } catch (t: IOException) {
                            // Generic stream I/O error: refresh and resume
                            resumeFrom = part.length().coerceIn(0, total)
                            triesOnThisStream++
                            logw("Stream IO error: ${t.message}. resumeFrom=$resumeFrom")
                            if (triesOnThisStream <= 3) continue@STREAM
                            throw t
                        }

                        // Success path: finalize
                        if (dst.exists()) dst.delete()
                        if (!part.renameTo(dst)) {
                            part.copyTo(dst, overwrite = true); part.delete()
                        }

                        // Final sanity checks
                        if (dst.length() != total) {
                            val got = dst.length()
                            dst.delete()
                            throw IOException("Size mismatch after copy: expected=$total got=$got")
                        }
                        if (expectedSha256 != null) {
                            val gotSha = sha256(dst)
                            if (!gotSha.equals(expectedSha256, ignoreCase = true)) {
                                dst.delete()
                                throw IOException("SHA-256 mismatch: expected=$expectedSha256 got=$gotSha")
                            }
                        }

                        logd("Saved: ${dst.absolutePath} (${dst.length()} bytes)")
                        return@withContext
                    } finally {
                        conn.disconnect()
                    }
                }
            } catch (t: Throwable) {
                lastError = t
                logw("Attempt ${attempt + 1} failed: ${t::class.simpleName}: ${t.message}")

                // Honor Retry-After when available in the last error (if any connection was open)
                val retryAfterMs = (t as? HttpExceptionWithRetryAfter)?.retryAfterMs
                if (attempt < maxRetries - 1) {
                    val backoffMs = retryAfterMs ?: (500.0 * 2.0.pow(attempt.toDouble())).toLong()
                    logw("Retrying (attempt ${attempt + 2}/$maxRetries) in ${backoffMs}ms …")
                    delay(backoffMs)
                }
            }
            attempt++
        }
        throw IOException(
            "Download failed after $maxRetries attempts: ${lastError?.message}",
            lastError
        )
    }

    // ---------- HEAD probe (manual redirects to capture final URL, size, and validators) ----------
    private data class Probe(
        val total: Long?,
        val acceptRanges: Boolean,
        val etag: String?,
        val lastModified: String?,
        val finalUrl: String
    )

    private fun headProbe(
        srcUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): Probe {
        var current = srcUrl
        var hops = 0
        var lastConn: HttpURLConnection? = null
        while (true) {
            lastConn?.disconnect()
            val conn = openConn(
                current, method = "HEAD",
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                followRedirects = false // we handle redirects manually to update 'current'
            )
            try {
                setCommonHeaders(conn, current)
                conn.connect()
                val code = conn.responseCode

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("HEAD $code without Location.")
                    val next = URL(URL(current), loc).toString()
                    current = next
                    hops++
                    if (hops > 10) throw IOException("Too many redirects on HEAD.")
                    lastConn = conn
                    continue
                }

                if (code == 429 || code == 503) {
                    val retryAfterMs = readRetryAfterMs(conn)
                    throw HttpExceptionWithRetryAfter("HEAD HTTP $code", retryAfterMs)
                }

                if (code !in 200..299) {
                    val snippet = readErrorSnippet(conn)
                    throw IOException("HEAD HTTP $code${snippet?.let { ": $it" } ?: ""}")
                }

                val total = conn.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0 }
                val acceptRanges =
                    (conn.getHeaderField("Accept-Ranges") ?: "").contains("bytes", true)
                val etag = conn.getHeaderField("ETag")
                val lastMod = conn.getHeaderField("Last-Modified")
                val finalUrl = conn.url.toString()
                return Probe(total, acceptRanges, etag, lastMod, finalUrl)
            } finally {
                conn.disconnect()
            }
        }
    }

    // ---------- HttpURLConnection helpers ----------
    private fun openConn(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean
    ): HttpURLConnection {
        val u = URL(url)
        return (u.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = followRedirects
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            doInput = true
        }
    }

    private fun setCommonHeaders(conn: HttpURLConnection, url: String) {
        conn.setRequestProperty("User-Agent", "SurveyNav/1.0 (Android)")
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.setRequestProperty("Accept-Charset", "UTF-8")
        conn.setRequestProperty(
            "Accept-Encoding",
            "identity"
        ) // avoid implicit gzip for binary payloads

        // Add Authorization only for huggingface hosts. Do not attempt to "unset" headers (may throw NPE).
        if (isHfHost(url) && !hfToken.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $hfToken")
        }
    }

    private fun readErrorSnippet(conn: HttpURLConnection): String? = try {
        val s = (conn.errorStream ?: return null).use { it.readBytes().decodeToString() }
        s.replace("\n", " ").take(300)
    } catch (_: Throwable) {
        null
    }

    private fun readRetryAfterMs(conn: HttpURLConnection): Long? {
        // Retry-After can be seconds or an HTTP date. We only implement seconds here for simplicity.
        val v = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: return null
        return (v * 1000).coerceAtLeast(0)
    }

    // ---------- meta + integrity ----------
    private class MetaFile(private val part: File) {
        private val file = File(part.parentFile, part.name + ".meta")

        fun read(): Meta? = runCatching {
            if (!file.exists()) return null
            val m = file.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
            Meta(m["etag"], m["lastModified"], m["total"]?.toLongOrNull())
        }.getOrNull()

        fun write(meta: Meta) = runCatching {
            file.writeText(buildString {
                meta.etag?.let { append("etag=$it\n") }
                meta.lastModified?.let { append("lastModified=$it\n") }
                meta.total?.let { append("total=$it\n") }
            })
        }.getOrNull()

        fun delete() {
            runCatching { file.delete() }
        }
    }

    private data class Meta(val etag: String?, val lastModified: String?, val total: Long?)

    private fun parseContentRangeStart(h: String?): Long? {
        if (h.isNullOrBlank()) return null
        val m = Regex("""bytes\s+(\d+)-""").find(h) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { fis ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = fis.read(buf); if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkFreeSpaceOrThrow(dir: File, required: Long) {
        runCatching {
            val fs = StatFs(dir.absolutePath)
            val avail = max(0L, fs.availableBytes)
            if (avail < required) throw IOException("Not enough free space: need ${required}B, available ${avail}B")
        }.onFailure { logw("StatFs failed: ${it.message}") }
    }

    private fun isHfHost(u: String): Boolean {
        val h = runCatching { URL(u).host ?: "" }.getOrElse { "" }
        return h == "huggingface.co" || h.endsWith(".huggingface.co")
    }

    private fun logd(msg: String) {
        if (debugLogs) Log.d(tag, msg)
    }

    private fun logw(msg: String) {
        if (debugLogs) Log.w(tag, msg)
    }

    // Simple wrapper to propagate Retry-After across the outer retry loop.
    private class HttpExceptionWithRetryAfter(message: String, val retryAfterMs: Long?) :
        IOException(message)
}
