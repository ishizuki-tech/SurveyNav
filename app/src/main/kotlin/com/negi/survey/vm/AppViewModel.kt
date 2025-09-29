package com.negi.survey.vm

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Represents the current download state for the model.
 */
sealed class DlState {
    data object Idle : DlState()
    data class Downloading(val downloaded: Long, val total: Long?) : DlState()
    data class Done(val file: File) : DlState()
    data class Error(val message: String) : DlState()
}

/**
 * ViewModel responsible for managing the download and persistence of the SLM model file.
 */
class AppViewModel(
    private val modelUrl: String = DEFAULT_MODEL_URL,
    private val client: OkHttpClient = defaultClient(BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() })
) : ViewModel() {

    /**
     * Interceptor that adds headers (User-Agent, Authorization, etc.) to requests.
     */
    class HfAuthInterceptor(private val token: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val host = req.url.host
            val b = req.newBuilder()
                .header("User-Agent", "SurveyNav/1.0 (Android)")
                .header("Accept", "application/octet-stream")
            if (host.endsWith("huggingface.co") && token.isNotBlank()) {
                b.header("Authorization", "Bearer $token")
            }
            return chain.proceed(b.build())
        }
    }

    private val _state = MutableStateFlow<DlState>(DlState.Idle)
    val state: StateFlow<DlState> = _state

    /**
     * Ensures that the model is downloaded once. If already downloading or done, skips execution.
     */
    fun ensureModelDownloaded(appContext: Context) {
        if (_state.value is DlState.Downloading || _state.value is DlState.Done) return

        viewModelScope.launch {
            val fileName = modelUrl.substringAfterLast('/').ifBlank { "model.litertlm" }
            val dstFile = File(appContext.filesDir, fileName)

            // Skip download if the file already exists
            if (dstFile.exists() && dstFile.length() > 0) {
                _state.value = DlState.Done(dstFile)
                return@launch
            }

            _state.value = DlState.Downloading(0, null)

            try {
                downloadToFile(modelUrl, dstFile) { got, total ->
                    _state.value = DlState.Downloading(got, total)
                }
                _state.value = DlState.Done(dstFile)
            } catch (e: Exception) {
                _state.value = DlState.Error(e.message ?: "download failed")
            }
        }
    }

    /**
     * Downloads a file to disk with optional progress tracking.
     */
    private suspend fun downloadToFile(
        url: String,
        dst: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit
    ) = withContext(Dispatchers.IO) {
        dst.parentFile?.mkdirs()
        val req = Request.Builder().url(url).build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val code = resp.code
                val msg = resp.body?.string()?.take(200)
                throw IOException("HTTP $code ${msg ?: ""}".trim())
            }

            val body = resp.body ?: throw IOException("empty body")
            val total = body.contentLength().takeIf { it >= 0 }

            body.byteStream().use { input ->
                val tmp = File(dst.parentFile, dst.name + ".part")
                tmp.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
                if (dst.exists()) dst.delete()
                tmp.renameTo(dst)
            }
        }
    }

    companion object {

        /**
         * ViewModel factory to be used with Compose viewModel() helper.
         */
        fun factory() = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AppViewModel() as T
            }
        }

        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm"

        /**
         * Provides a default OkHttpClient with proper headers and timeouts.
         */
        fun defaultClient(hfToken: String?): OkHttpClient =
            OkHttpClient.Builder()
                .addInterceptor(HfAuthInterceptor(hfToken.orEmpty()))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
    }
}

/**
 * UI component that gates access to the main content until model download completes.
 * Shows progress UI or error with retry. Delegates to [content] on success.
 */
@Composable
fun DownloadGate(
    state: DlState,
    onRetry: () -> Unit,
    content: @Composable (modelFile: File) -> Unit
) {
    when (state) {
        is DlState.Idle, is DlState.Downloading -> {
            val (got, total) = when (state) {
                is DlState.Downloading -> state.downloaded to state.total
                else -> 0L to null
            }
            val pct: Int? = total?.let { if (it > 0) ((got * 100.0 / it).toInt()) else null }

            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Downloading the target SLM…")
                Spacer(Modifier.height(12.dp))
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { (pct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$pct%  ($got / ${total} bytes)")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("$got bytes")
                }
            }
        }

        is DlState.Error -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Failed Download... : ${state.message}")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("Retry.") }
            }
        }

        is DlState.Done -> {
            content(state.file)
        }
    }
}