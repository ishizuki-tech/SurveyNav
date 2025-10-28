// file: app/src/main/kotlin/com/negi/surveyslm/screens/DownloadScreen.kt
package com.negi.survey.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DisposableEffectScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.negi.survey.vm.AppViewModel

import kotlin.getOrDefault
import kotlin.jvm.java
import kotlin.let
import kotlin.math.max
import kotlin.math.round
import kotlin.onFailure
import kotlin.ranges.coerceAtLeast
import kotlin.ranges.coerceIn
import kotlin.runCatching
import kotlin.text.format
import kotlin.text.ifBlank

/* ──────────────────────────────────────────────────────────────────────────────
 * Wi-Fi watcher
 * - Observes whether the current default network is Wi-Fi and has INTERNET.
 * - On API < 24, falls back to a one-shot check (no callbacks available).
 * - All register/unregister calls are wrapped to avoid OEM-specific crashes.
 * ────────────────────────────────────────────────────────────────────────────── */
@Composable
private fun rememberWifiConnected(): State<Boolean> {
    val context = LocalContext.current
    val cm = remember {
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    }
    val wifi = remember { mutableStateOf(isWifiNow(cm)) }

    DisposableEffect(cm) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                wifi.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }

            override fun onLost(network: Network) {
                wifi.value = isWifiNow(cm)
            }

            override fun onUnavailable() {
                wifi.value = false
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onFailure { wifi.value = isWifiNow(cm) }

        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return wifi
}

/** Returns true if the active network is Wi-Fi with INTERNET capability. */
private fun isWifiNow(cm: ConnectivityManager): Boolean {
    val n = cm.activeNetwork ?: return false
    val c = cm.getNetworkCapabilities(n) ?: return false
    return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/* ──────────────────────────────────────────────────────────────────────────────
 * DownloadScreen (gate)
 * Flow:
 *  1) publishDoneIfLocalPresent -> Done -> navigate once
 *  2) verifyModelReady (HEAD only) -> if not ready, show Wi-Fi guarded dialog
 *  3) If user confirms, start download (Wi-Fi only; ViewModel enforces policy)
 *  4) On Done, call onNext exactly once
 * ────────────────────────────────────────────────────────────────────────────── */
@Composable
fun DownloadScreen(vm: AppViewModel, onNext: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val isWifi by rememberWifiConnected()

    // One-shot navigation guard
    var navigated by rememberSaveable { mutableStateOf(false) }
    val isDone = state is AppViewModel.DlState.Done
    LaunchedEffect(isDone, navigated) {
        if (isDone && !navigated) {
            navigated = true
            onNext()
        }
    }

    // Initial verification gate
    var checking by rememberSaveable { mutableStateOf(true) }
    var showDownloadWarning by rememberSaveable { mutableStateOf(false) }

    // Verify once on enter
    LaunchedEffect(Unit) {
        // 1) Local fast path (no network)
        if (vm.publishDoneIfLocalPresent(context.applicationContext)) {
            checking = false
            return@LaunchedEffect
        }
        // 2) HEAD-only verification (no download)
        val ready = runCatching { vm.verifyModelReady(context.applicationContext) }
            .getOrDefault(false)
        showDownloadWarning = !ready
        checking = false
    }

    // Smoothed speed/ETA while downloading (exponential smoothing)
    var lastTickNs by remember { mutableLongStateOf(0L) }
    var lastTickBytes by remember { mutableLongStateOf(0L) }
    var speedBps by remember { mutableDoubleStateOf(0.0) }
    val downloadingState = state as? AppViewModel.DlState.Downloading
    LaunchedEffect(downloadingState?.downloaded) {
        if (downloadingState != null) {
            val now = System.nanoTime()
            val downloadedNow = downloadingState.downloaded
            if (lastTickNs != 0L && downloadedNow >= lastTickBytes) {
                val dtSec = (now - lastTickNs) / 1e9
                if (dtSec > 1e-6) {
                    val delta = (downloadedNow - lastTickBytes).toDouble()
                    val inst = delta / dtSec
                    val alpha = 0.25
                    speedBps =
                        if (speedBps <= 0.0) inst else (alpha * inst + (1 - alpha) * speedBps)
                }
            }
            lastTickNs = now
            lastTickBytes = downloadedNow
        } else {
            lastTickNs = 0L
            lastTickBytes = 0L
            speedBps = 0.0
        }
    }

    // Initial checking UI
    if (checking) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Checking model…")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        return
    }

    // Wi-Fi guarded dialog (shown only when verification says "not ready")
    if (showDownloadWarning) {
        DownloadWarningDialogWifiGuarded(
            isWifi = isWifi,
            onOpenSettings = {
                // Prefer Internet Connectivity panel on Q+, fallback to Wi-Fi settings
                val panel = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(panel) }
                    .onFailure { runCatching { context.startActivity(wifi) } }
            },
            onConfirm = {
                val started = vm.ensureModelDownloadedWifiOnly(context.applicationContext)
                if (started) showDownloadWarning = false
            },
            onCancel = { showDownloadWarning = false }
        )
    }

    // Main UI
    when (val s = state) {
        AppViewModel.DlState.Idle -> {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "On-device model is required",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The model file is not present yet. Downloads are permitted on Wi-Fi only.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { showDownloadWarning = true }) {
                            Text("Download model")
                        }
                    }
                }
            }
        }

        is AppViewModel.DlState.Downloading -> {
            val downloaded = s.downloaded
            val total = s.total
            val fraction: Float? = total?.let { t ->
                if (t > 0) (downloaded.toDouble() / t.toDouble()).toFloat().coerceIn(0f, 1f) else null
            }
            var showDebug by rememberSaveable { mutableStateOf(false) }

            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Preparing model (Wi-Fi only)…")
                        Spacer(Modifier.height(12.dp))

                        if (fraction != null) {
                            // Use value overload for better compatibility with older Material3 versions
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                                color = ProgressIndicatorDefaults.linearColor,
                                trackColor = ProgressIndicatorDefaults.linearTrackColor,
                                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("${(fraction * 100).toInt()}%   (${downloaded.toMiB()} / ${total.toMiBOrUnknown()})")
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(formatEta(downloaded, total, speedBps))
                                Text(speedBps.toMBps())
                            }
                        } else {
                            // Indeterminate when Content-Length is unknown
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${downloaded.toMiB()} downloaded")
                                Text(speedBps.toMBps())
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = {
                                vm.cancelDownload()
                            }) { Text("Cancel") }
                            TextButton(onClick = {
                                vm.ensureModelDownloadedWifiOnly(
                                    context.applicationContext,
                                    forceRedownload = true
                                )
                            }) { Text("Force re-download") }
                            TextButton(onClick = { showDebug = !showDebug }) {
                                Text(if (showDebug) "Hide debug" else "Show debug")
                            }
                        }

                        // Optional: place a debug card here if desired
                        // if (showDebug) { ProgressDebugCard(...) }
                    }
                }
            }
        }

        is AppViewModel.DlState.Error -> {
            val msg = when (s.message.ifBlank { "unknown" }) {
                "wifi_required" -> "Wi-Fi connection is required to start the download."
                else -> s.message
            }
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Initialization failed: $msg")
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { showDownloadWarning = true }) { Text("Open dialog") }
                            OutlinedButton(onClick = {
                                vm.ensureModelDownloadedWifiOnly(
                                    context.applicationContext,
                                    forceRedownload = true
                                )
                            }) { Text("Start over (Wi-Fi)") }
                        }
                    }
                }
            }
        }

        AppViewModel.DlState.Canceled -> {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Initialization canceled")
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { showDownloadWarning = true }) { Text("Resume") }
                            OutlinedButton(onClick = {
                                vm.ensureModelDownloadedWifiOnly(
                                    context.applicationContext,
                                    forceRedownload = true
                                )
                            }) { Text("Start over (Wi-Fi)") }
                        }
                    }
                }
            }
        }

        is AppViewModel.DlState.Done -> {
            // Likely shown briefly; navigation handled by LaunchedEffect above
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Model ready. Launching…")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────────────
 * Wi-Fi-guarded dialog
 * ────────────────────────────────────────────────────────────────────────────── */
@Composable
private fun DownloadWarningDialogWifiGuarded(
    isWifi: Boolean,
    onOpenSettings: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Download required") },
        text = {
            Text(
                if (isWifi)
                    "The on-device model needs to be downloaded. The download will proceed over Wi-Fi."
                else
                    "The on-device model needs to be downloaded, but Wi-Fi is required. " +
                            "Please connect to a Wi-Fi network and try again."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = isWifi) { Text("Download (Wi-Fi)") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onOpenSettings) { Text("Open settings") }
            }
        }
    )
}

/* ──────────────────────────────────────────────────────────────────────────────
 * Small UI helpers (top-level only; no extension with same JVM signature)
 * ────────────────────────────────────────────────────────────────────────────── */
@SuppressLint("DefaultLocale")
private fun Long.toMiB(): String = String.format("%.1f MiB", this / (1024.0 * 1024.0))

private fun Long?.toMiBOrUnknown(): String = this?.toMiB() ?: "unknown"

@SuppressLint("DefaultLocale")
private fun Double.toMBps(): String {
    if (this <= 0.0) return "0.0 MB/s"
    val mbps = this / (1024.0 * 1024.0)
    val v = round(mbps * 10.0) / 10.0
    return String.format("%.1f MB/s", v)
}

/** Formats a human-friendly ETA string. */
private fun formatEta(downloaded: Long, total: Long?, bps: Double): String {
    if (total == null || total <= 0 || bps <= 1e-6) return "ETA: —"
    val remain = max(0L, total - downloaded)
    val sec = (remain / bps).toLong().coerceAtLeast(0)
    val h = (sec / 3600)
    val m = ((sec % 3600) / 60)
    val s = (sec % 60)
    return if (h > 0) "ETA: ${h}h ${m}m ${s}s" else "ETA: ${m}m ${s}s"
}
