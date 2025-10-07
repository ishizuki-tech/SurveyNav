// file: app/src/androidTest/java/com/negi/survey/vm/PrettyLog.kt
package com.negi.survey

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.max
import kotlin.math.min

@Suppress("unused")
object PrettyLog {
    private const val TAG_FALLBACK = "PrettyLog"
    private val args = runCatching { InstrumentationRegistry.getArguments() }.getOrNull()

    // Android Studio Logcat では ANSI は基本無効。必要なら "adb logcat -v color" で。
    private val ENABLE_ANSI: Boolean by lazy {
        when (args?.getString("ANSI")?.lowercase()?.trim()) {
            "1","true","yes","on" -> true
            "0","false","no","off" -> false
            else -> {
                val term = System.getenv("TERM") ?: ""
                term.contains("xterm") || term.contains("ansi")
            }
        }
    }

    enum class Swatch(val emoji: String, val ansi: String) {
        RED("🟥", "\u001B[31m"),
        GREEN("🟩", "\u001B[32m"),
        YELLOW("🟨", "\u001B[33m"),
        BLUE("🟦", "\u001B[34m"),
        MAGENTA("🟪", "\u001B[35m"),
        CYAN("🟦", "\u001B[36m"),
        WHITE("⬜", "\u001B[37m"),
        GRAY("⬜", "\u001B[90m")
    }
    private const val ANSI_RESET = "\u001B[0m"
    private const val ANSI_BOLD  = "\u001B[1m"

    private fun tint(s: String, sw: Swatch, bold: Boolean = false): String {
        if (!ENABLE_ANSI) return "${sw.emoji} $s"
        val b = if (bold) ANSI_BOLD else ""
        return "${sw.ansi}$b$s$ANSI_RESET"
    }

    fun heading(tag: String?, title: String, sw: Swatch = Swatch.CYAN) {
        Log.i(tag ?: TAG_FALLBACK, tint(title, sw, bold = true))
    }

    fun kv(
        tag: String?, title: String, kv: Map<String, Any?>,
        sw: Swatch = Swatch.BLUE, wrapAt: Int = 120
    ) {
        val keyWidth = max(4, kv.keys.maxOfOrNull { it.length } ?: 4)
        val lines = buildList {
            add(tint("== $title ==", sw, bold = true))
            kv.forEach { (k, v) ->
                val raw = (v?.toString() ?: "null").replace("\n", " ")
                add(String.format("  %-"+ keyWidth +"s : %s", k, wrap(raw, wrapAt - keyWidth - 5)))
            }
        }
        lines.forEach { Log.i(tag ?: TAG_FALLBACK, it) }
    }

    fun block(tag: String?, title: String, body: String, sw: Swatch = Swatch.MAGENTA, wrapAt: Int = 120) {
        val t = " ${title.trim()} "
        val bar = "─".repeat(min(max(t.length, 16), wrapAt))
        Log.i(tag ?: TAG_FALLBACK, tint("┌$bar┐", sw, bold = true))
        Log.i(tag ?: TAG_FALLBACK, tint("│$t│", sw, bold = true))
        Log.i(tag ?: TAG_FALLBACK, tint("├$bar┤", sw, bold = true))
        wrap(body, wrapAt).lines().forEach { line ->
            Log.i(tag ?: TAG_FALLBACK, "│ $line")
        }
        Log.i(tag ?: TAG_FALLBACK, tint("└$bar┘", sw, bold = true))
    }

    fun alert(tag: String?, message: String, sw: Swatch = Swatch.YELLOW) {
        Log.w(tag ?: TAG_FALLBACK, tint("⚠ $message", sw, bold = true))
    }
    fun fail(tag: String?, message: String) {
        Log.e(tag ?: TAG_FALLBACK, tint("✖ $message", Swatch.RED, bold = true))
    }
    fun ok(tag: String?, message: String) {
        Log.i(tag ?: TAG_FALLBACK, tint("✔ $message", Swatch.GREEN, bold = true))
    }

    private fun wrap(s: String, width: Int): String {
        if (width <= 10 || s.length <= width) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val j = min(i + width, s.length)
            out.append(s.substring(i, j))
            if (j < s.length) out.append('\n')
            i = j
        }
        return out.toString()
    }
}
