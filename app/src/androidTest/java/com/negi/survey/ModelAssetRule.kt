package com.negi.survey

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume
import org.junit.rules.ExternalResource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Instrumentation用の共通Rule。
 * - MediaStore経由でモデルを確保し、内部(filesDir/models)へ配置する。
 * - ロジックは元実装と等価（探索順序・権限採用・フォールバック・ダウンロード・コピー手順）。
 */
class ModelAssetRule(
    private val modelName: String = "gemma-3n-E4B-it-int4.litertlm",
    private val relativeDir: String = Environment.DIRECTORY_DOWNLOADS + "/SurveyNavModels",
    private val modelUrl: String = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
    private val bearerToken: String? = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() },
) : ExternalResource() {

    lateinit var context: Context; private set
    lateinit var internalModel: File; private set

    private val TAG = "MS-ModelPrep"
    private val prefsName = "ms_cache"
    private val keyModelUri = "model_uri"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private var adoptedShellRead = false
    private var adoptedForApi: Int = -1

    // ---------------- lifecycle ----------------

    override fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue("Requires API 29+ (Android 10+).", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        adoptReadExternalIfNeeded()

        // 1) 内部に既存なら終了
        internalModel = File(File(context.filesDir, "models"), modelName).apply { parentFile?.mkdirs() }
        if (internalModel.exists() && internalModel.length() > 0) {
            Log.i(TAG, "Skip: internal exists -> ${internalModel.absolutePath}")
            return
        }

        // 2) 既存探索（キャッシュ → 自アプリ所有(API33+) → どの所有者でも(API33+) → レガシー(API<=32)）
        val cached = loadCachedUri()?.takeIf { getSize(it) > 0 }

        // API33+ 自アプリ所有
        val selfOwned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cached ?: querySelfOwnedByNamePreferPathLike(modelName, relativeDir)?.also { cacheUri(it) }
        } else null

        if (selfOwned != null) {
            // 後段でコピー
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API33+ 任意所有者（必要時のみ一時的にAll files相当の読み権限）
            val copied = withAllFilesAccessForRead {
                queryAnyOwnerByNamePreferPathLikeApi33(modelName, relativeDir)?.let { uri ->
                    if (getSize(uri) > 0) { copyUriToFile(uri, internalModel); true } else false
                } ?: false
            }
            if (copied) {
                check(internalModel.exists() && internalModel.length() > 0)
                return
            }
        }

        // API<=32 レガシー探索
        val legacyFound = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            runCatching { kickMediaScanner(relativeDir, modelName) }
            cached ?: queryByNamePreferPathLikeLegacy(modelName, relativeDir)?.also { cacheUri(it) }
        } else null

        // 3) 無ければ新規作成→DL（アプリIDで所有）→検証→キャッシュ
        val target = selfOwned ?: legacyFound ?: run {
            val created = insertDownloadOwned(modelName, relativeDir)
            try {
                downloadToUriOwned(created, modelUrl, bearerToken)
                require(getSize(created) > 0) { "Zero bytes after download via MediaStore" }
                verifyOwnerOrWarn(created)
                cacheUri(created)
            } catch (t: Throwable) {
                safeDelete(created)
                throw t
            }
            created
        }

        // 4) 内部へコピー
        copyUriToFile(target, internalModel)
        Log.i(TAG, "internal=${internalModel.absolutePath} len=${internalModel.length()}")
        check(internalModel.exists() && internalModel.length() > 0)
    }

    override fun after() {
        if (adoptedShellRead) {
            runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity() }
            adoptedShellRead = false
        }
    }

    // ---------------- adopt / identity ----------------

    private fun adoptReadExternalIfNeeded() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            adoptedForApi = 33
            Log.i(TAG, "skip adopt for API>=33 (self-owned search)")
        } else {
            ui.adoptShellPermissionIdentity(Manifest.permission.READ_EXTERNAL_STORAGE)
            adoptedShellRead = true
            adoptedForApi = 32
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "adopt READ_EXTERNAL_STORAGE; checkSelfPermission=$granted")
        }
    }

    private inline fun <T> withAllFilesAccessForRead(block: () -> T): T {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        var adopted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { ui.adoptShellPermissionIdentity(Manifest.permission.MANAGE_EXTERNAL_STORAGE) }
                .onSuccess { adopted = true; Log.i(TAG, "adopt MANAGE_EXTERNAL_STORAGE (temp)") }
                .onFailure { Log.i(TAG, "cannot adopt MANAGE_EXTERNAL_STORAGE: ${it.message}") }
        } else {
            runCatching { ui.adoptShellPermissionIdentity(Manifest.permission.READ_EXTERNAL_STORAGE) }
                .onSuccess { adopted = true; Log.i(TAG, "adopt READ_EXTERNAL_STORAGE (temp)") }
        }
        return try {
            block()
        } finally {
            if (adopted) {
                runCatching { ui.dropShellPermissionIdentity() }
                Log.i(TAG, "drop temp all-files access")
                adoptReadExternalIfNeeded()
            }
        }
    }

    private inline fun <T> withAppIdentity(block: () -> T): T {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val dropped = if (adoptedShellRead) runCatching { ui.dropShellPermissionIdentity() }
            .onSuccess { adoptedShellRead = false; Log.i(TAG, "drop shell identity for write") }
            .isSuccess else false
        return try {
            block()
        } finally {
            if (dropped) {
                adoptReadExternalIfNeeded()
                Log.i(TAG, "re-adopt after write; api=$adoptedForApi")
            }
        }
    }

    // ---------------- query helpers ----------------

    private data class Cand(val uri: Uri, val rel: String?, val size: Long, val mod: Long)

    private fun volumes(): Set<String> =
        MediaStore.getExternalVolumeNames(context).ifEmpty { setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY) }

    private fun <T> acrossVolumes(mapper: (Uri) -> List<T>): List<T> = buildList {
        for (v in volumes()) {
            addAll(mapper(MediaStore.Downloads.getContentUri(v)))
            addAll(mapper(MediaStore.Files.getContentUri(v)))
        }
    }

    private data class RelPathLikes(
        val likeA: String, val likeB: String, val likeC: String, val likeD: String, val preferSuffix: String
    )

    private fun relLikes(preferRelPath: String): RelPathLikes {
        val prefNoSlash = preferRelPath.trimEnd('/')
        val prefWithSlash = normalizeRelPath(preferRelPath)
        val altNoSlash = prefNoSlash.replaceFirst("Download", "Downloads")
        val altWithSlash = normalizeRelPath(altNoSlash)
        return RelPathLikes(
            "%${escapeLike(prefWithSlash)}",
            "%${escapeLike(prefNoSlash)}",
            "%${escapeLike(altWithSlash)}",
            "%${escapeLike(altNoSlash)}",
            normalizeRelPath(prefWithSlash)
        )
    }

    /** API33+: 自アプリ所有（owner=null も許容） */
    private fun querySelfOwnedByNamePreferPathLike(displayName: String, preferRelPath: String): Uri? {
        val (likeName, preferSuffix) = likeNameAndSuffix(displayName, preferRelPath).first to
                relLikes(preferRelPath).preferSuffix
        val owners = ownerCandidates()
        fun query(baseUri: Uri): List<Cand> {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                "is_trashed"
            )
            val (likeA, likeB, likeC, likeD) = relLikes(preferRelPath)
            val ownerSel = """
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? OR
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? OR
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? OR
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? OR
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? OR
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} IS NULL
            """.trimIndent()
            val relSel = """
                ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\' OR
                ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\' OR
                ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\' OR
                ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\' OR
                ${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL
            """.trimIndent()
            val sel = """
                ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\' AND
                ($ownerSel) AND
                ($relSel) AND
                (${MediaStore.MediaColumns.IS_PENDING} IS NULL OR ${MediaStore.MediaColumns.IS_PENDING}=0) AND
                (is_trashed IS NULL OR is_trashed=0)
            """.trimIndent()
            val args = arrayOf(
                likeName,
                owners[0], owners.getOrNull(1) ?: owners[0], owners.getOrNull(2) ?: owners[0],
                owners.getOrNull(3) ?: owners[0], owners.getOrNull(4) ?: owners[0],
                likeA, likeB, likeC, likeD
            )
            return queryCandidates(baseUri, projection, sel, args)
        }

        val cands = acrossVolumes(::query)
        return pickBestByRelAndTime(cands, preferSuffix)
    }

    /** API33+: 任意オーナー（まず相対パス重視、無ければ緩める） */
    private fun queryAnyOwnerByNamePreferPathLikeApi33(displayName: String, preferRelPath: String): Uri? {
        val (likeName, preferSuffix) = likeNameAndSuffix(displayName, preferRelPath).first to
                relLikes(preferRelPath).preferSuffix

        fun query(baseUri: Uri, strictRel: Boolean): List<Cand> {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.IS_PENDING,
                "is_trashed"
            )
            val sel = StringBuilder("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'")
            val args = mutableListOf(likeName)
            if (strictRel) {
                val (likeA, likeB, likeC, likeD) = relLikes(preferRelPath)
                sel.append(" AND (")
                    .append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' OR ")
                    .append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' OR ")
                    .append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' OR ")
                    .append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' OR ")
                    .append("${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL)")
                args += listOf(likeA, likeB, likeC, likeD)
            }
            sel.append(" AND (${MediaStore.MediaColumns.IS_PENDING} IS NULL OR ${MediaStore.MediaColumns.IS_PENDING}=0)")
            sel.append(" AND (is_trashed IS NULL OR is_trashed=0)")
            return queryCandidates(baseUri, projection, sel.toString(), args.toTypedArray())
        }

        var cands = acrossVolumes { query(it, true) }
        if (cands.isEmpty()) cands = acrossVolumes { query(it, false) }
        if (cands.isEmpty()) return null
        return pickBestByRelAndTime(cands, preferSuffix)
    }

    /** API<=32: DISPLAY_NAME LIKE で探索（DATE_MODIFIED優先） */
    private fun queryByNamePreferPathLikeLegacy(displayName: String, preferRelPath: String): Uri? {
        val (base, ext) = displayName.substringBeforeLast('.') to displayName.substringAfterLast('.', "")
        val likePattern = if (ext.isNotEmpty()) "${escapeLike(base)}%.$ext" else "${escapeLike(base)}%"
        data class CandL(val uri: Uri, val rel: String?, val size: Long, val mod: Long)

        fun query(baseUri: Uri): List<CandL> {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.IS_PENDING,
                "is_trashed"
            )
            val out = mutableListOf<CandL>()
            context.contentResolver.query(
                baseUri,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                arrayOf(likePattern),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val rel = c.getColumnIndexOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
                val size = c.getColumnIndexOrNull(MediaStore.MediaColumns.SIZE)
                val mod = c.getColumnIndexOrNull(MediaStore.MediaColumns.DATE_MODIFIED)
                val pend = c.getColumnIndexOrNull(MediaStore.MediaColumns.IS_PENDING)
                val trash = c.getColumnIndexOrNull("is_trashed")
                while (c.moveToNext()) {
                    val s = size?.let { if (it >= 0) c.getLong(it) else -1L } ?: -1L
                    if (s <= 0) continue
                    if (pend?.let { it >= 0 && c.getInt(it) == 1 } == true) continue
                    if (trash?.let { it >= 0 && c.getInt(it) == 1 } == true) continue
                    out += CandL(
                        ContentUris.withAppendedId(baseUri, c.getLong(id)),
                        rel?.let { if (it >= 0) c.getString(it) else null },
                        s,
                        mod?.let { if (it >= 0) c.getLong(it) else 0L } ?: 0L
                    )
                }
            }
            return out
        }

        val cands = acrossVolumes(::query)
        if (cands.isEmpty()) return null
        val preferSuffix = normalizeRelPath(preferRelPath)
        val exact = cands.filter { it.rel?.let { r -> normalizeRelPath(r).endsWith(preferSuffix) } == true }
            .maxWithOrNull(compareBy<CandL> { it.mod }.thenBy { it.size })
        return (exact ?: cands.maxWithOrNull(compareBy<CandL> { it.mod }.thenBy { it.size }))?.uri
    }

    // 共通クエリ実行
    private fun queryCandidates(
        baseUri: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>
    ): List<Cand> {
        val out = mutableListOf<Cand>()
        context.contentResolver.query(
            baseUri, projection, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val rel = c.getColumnIndexOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
            val size = c.getColumnIndexOrNull(MediaStore.MediaColumns.SIZE)
            val mod = c.getColumnIndexOrNull(MediaStore.MediaColumns.DATE_MODIFIED)
            val trash = c.getColumnIndexOrNull("is_trashed")
            while (c.moveToNext()) {
                if (trash?.let { it >= 0 && c.getInt(it) == 1 } == true) continue
                val s = size?.let { if (it >= 0) c.getLong(it) else -1L } ?: -1L
                if (s <= 0) continue
                out += Cand(
                    ContentUris.withAppendedId(baseUri, c.getLong(id)),
                    rel?.let { if (it >= 0) c.getString(it) else null },
                    s,
                    mod?.let { if (it >= 0) c.getLong(it) else 0L } ?: 0L
                )
            }
        }
        return out
    }

    private fun pickBestByRelAndTime(cands: List<Cand>, preferSuffix: String): Uri? {
        if (cands.isEmpty()) return null
        val exact = cands.filter { it.rel?.let { r -> normalizeRelPath(r).endsWith(preferSuffix) } == true }
            .maxWithOrNull(compareBy<Cand> { it.mod }.thenBy { it.size })
        return (exact ?: cands.maxWithOrNull(compareBy<Cand> { it.mod }.thenBy { it.size }))?.uri
    }

    private fun likeNameAndSuffix(displayName: String, preferRelPath: String): Pair<String, String> {
        val (base, ext) = displayName.substringBeforeLast('.') to displayName.substringAfterLast('.', "")
        val likeName = if (ext.isNotEmpty()) "${escapeLike(base)}%.$ext" else "${escapeLike(base)}%"
        return likeName to relLikes(preferRelPath).preferSuffix
    }

    private fun ownerCandidates(): List<String> {
        val myPkg = context.packageName
        return listOf(myPkg, "$myPkg.debug", "$myPkg.staging", "$myPkg.beta", "$myPkg.release").distinct()
    }

    // ---------------- MediaStore write (owned by app) ----------------

    private fun insertDownloadOwned(displayName: String, relPath: String): Uri = withAppIdentity {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, normalizeRelPath(relPath))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        context.contentResolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv
        ) ?: throw IOException("MediaStore insert failed")
    }

    private fun downloadToUriOwned(dstUri: Uri, url: String, token: String?) = withAppIdentity {
        val req = Request.Builder().url(url).apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty().take(200)
                throw IOException("HTTP ${resp.code} $body".trim())
            }
            val body = resp.body ?: throw IOException("empty body")
            context.contentResolver.openOutputStream(dstUri, "w")?.use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw IOException("openOutputStream failed: $dstUri")
        }
        context.contentResolver.update(
            dstUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
        )
    }

    private fun verifyOwnerOrWarn(uri: Uri) {
        val owner = getOwner(uri)
        Log.i(TAG, "owner check: $owner expected=${context.packageName}")
        if (owner != null && owner != context.packageName) Log.w(TAG, "unexpected OWNER_PACKAGE_NAME: $owner")
    }

    private fun getOwner(uri: Uri): String? =
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getColumnIndexOrNull(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                ?.let { if (it >= 0) c.getString(it) else null } else null
        }

    // ---------------- utils ----------------

    private fun cacheUri(uri: Uri) =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString(keyModelUri, uri.toString()).apply()

    private fun loadCachedUri(): Uri? =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(keyModelUri, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun kickMediaScanner(relPath: String, displayName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val sub = relPath.removePrefix(Environment.DIRECTORY_DOWNLOADS).trimStart('/')
        val abs = File(base, if (sub.isEmpty()) displayName else "$sub/$displayName").absolutePath
        MediaScannerConnection.scanFile(context, arrayOf(abs), null, null)
    }

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun Cursor.getColumnIndexOrNull(name: String): Int? =
        try { getColumnIndex(name).takeIf { it >= 0 } } catch (_: Throwable) { null }

    private fun normalizeRelPath(path: String) = if (path.endsWith("/")) path else "$path/"

    private fun safeDelete(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { Log.w(TAG, "delete failed for $uri : ${it.message}") }
    }

    private fun getSize(uri: Uri): Long {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val pend = c.getColumnIndexOrNull(MediaStore.MediaColumns.IS_PENDING)
                    ?.let { if (it >= 0) c.getInt(it) else 0 } ?: 0
                if (pend == 1) return 0L
                val idx = c.getColumnIndexOrNull(MediaStore.MediaColumns.SIZE)
                if (idx != null && idx >= 0) c.getLong(idx).takeIf { it > 0 }?.let { return it }
            }
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?.use { if (it.length >= 0) it.length else 0L } ?: 0L
        } catch (_: Throwable) { 0L }
    }

    private fun copyUriToFile(src: Uri, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".part")
        try {
            context.contentResolver.openInputStream(src)?.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw IOException("openInputStream failed: $src")
            require(tmp.length() > 0) { "zero bytes after copy" }
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) throw IOException("renameTo failed: ${tmp.absolutePath}")
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }
}
