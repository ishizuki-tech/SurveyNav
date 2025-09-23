package com.negi.surveynav.slm

import org.json.JSONArray
import org.json.JSONObject
import kotlin.collections.filter
import kotlin.collections.iterator
import kotlin.collections.minOrNull
import kotlin.collections.take
import kotlin.collections.toList
import kotlin.let
import kotlin.math.max
import kotlin.math.min
import kotlin.ranges.until
import kotlin.text.endsWith
import kotlin.text.equals
import kotlin.text.indexOf
import kotlin.text.isEmpty
import kotlin.text.isNotBlank
import kotlin.text.removeSurrounding
import kotlin.text.replace
import kotlin.text.startsWith
import kotlin.text.substring
import kotlin.text.trim

object FollowupExtractor {

    /** エントリポイント：生テキストから抽出（JSON断片を自動検出） */
    @JvmStatic
    fun fromRaw(raw: String, max: Int = Int.MAX_VALUE): List<String> {
        val json = toJsonAny(raw) ?: return emptyList()
        return fromJsonAny(json, max)
    }

    /** 任意の JSONObject / JSONArray から抽出 */
    @JvmStatic
    fun fromJsonAny(any: Any, max: Int = Int.MAX_VALUE): List<String> {
        val out = LinkedHashSet<String>()  // 重複排除・順序維持
        collect(any, out, max)
        return out.toList().take(max)
    }

    // -------------------- 内部ヘルパ --------------------

    private val followupKeys = setOf(
        // よくあるキー候補
        "followup", "follow_up", "follow-ups", "followups",
        "follow_up_questions", "followUpQuestions", "followupQuestions",
        "next_questions", "nextQuestions",
        "suggested_questions", "suggestedQuestions",
        // ゆるめのフォールバック
        "questions", "prompts", "suggestions"
    )

    private val questionFieldCandidates = listOf(
        "question", "text", "q", "content", "title", "prompt"
    )

    private fun collect(node: Any, out: MutableSet<String>, max: Int) {
        if (out.size >= max) return

        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= max) break
                    val v = node.opt(i)
                    when (v) {
                        is String -> addIfMeaningful(v, out, max)
                        is JSONObject -> {
                            // まず {question:"..."} 形式を試す
                            extractQuestionField(v)?.let { addIfMeaningful(it, out, max) }
                            // ネストにも followups があるかもしれないので再帰
                            collect(v, out, max)
                        }
                        is JSONArray -> collect(v, out, max)
                        else -> { /* ignore */ }
                    }
                }
            }
            is JSONObject -> {
                // 1) まず "followUp系" キーを優先チェック
                for (k in node.keys()) {
                    if (out.size >= max) break
                    val key = k.toString()
                    val value = node.opt(key)
                    if (key in followupKeys) {
                        when (value) {
                            is JSONArray -> collect(value, out, max)
                            is JSONObject -> {
                                // 単一オブジェクトでも question フィールドを拾う
                                extractQuestionField(value)?.let { addIfMeaningful(it, out, max) }
                                collect(value, out, max)
                            }
                            is String -> addIfMeaningful(value, out, max)
                        }
                    }
                }
                // 2) 上で見つからなければ全キーを再帰探索
                for (k in node.keys()) {
                    if (out.size >= max) break
                    val v = node.opt(k)
                    when (v) {
                        is JSONArray, is JSONObject -> collect(v, out, max)
                        is String -> {
                            // "question" というキー名などから推測して拾う（過剰取得を避けるため控えめに）
                            if (k.equals("question", true)) addIfMeaningful(v, out, max)
                        }
                    }
                }
            }
            is String -> addIfMeaningful(node, out, max)
            else -> { /* ignore */ }
        }
    }

    private fun extractQuestionField(obj: JSONObject): String? {
        for (f in questionFieldCandidates) {
            val v = obj.opt(f)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return null
    }

    private fun addIfMeaningful(s: String, out: MutableSet<String>, max: Int) {
        if (out.size >= max) return
        val t = s.trim()
        if (t.isEmpty()) return

        // 末尾が ? / ？ の場合は複数を1つに正規化。なければそのまま（自動付与しない）
        val fixed = when {
            t.endsWith("？") -> t.replace(Regex("[?？]+$"), "？")
            t.endsWith("?")  -> t.replace(Regex("[?？]+$"), "?")
            else             -> t
        }
        out.add(fixed)
    }

    private fun toJsonAny(raw: String): Any? {
        val s0 = raw.trim().removeSurrounding("```", "```").trim()
        parseAny(s0)?.let { return it }

        val brace = s0.indexOf('{')
        val bracket = s0.indexOf('[')
        val start = listOf(brace, bracket).filter { it >= 0 }.minOrNull() ?: return null
        val s1 = s0.substring(start).trim()
        return parseAny(s1)
    }

    private fun parseAny(s: String): Any? = try {
        when {
            s.startsWith("{") -> JSONObject(s)
            s.startsWith("[") -> JSONArray(s)
            else -> null
        }
    } catch (_: Throwable) { null }

    fun extractFollowupQuestion(rawText: String): String? {
        // 1) JSON/テキストから follow-up を抽出（最初の1件）
        val fromJson = runCatching { FollowupExtractor.fromRaw(rawText, max = 1).firstOrNull() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (fromJson != null) return fromJson

        return null
    }
    /** 0..100 にクランプ */
    private fun clamp0to100(x: Int): Int = max(0, min(100, x))

    /**
     * スコア抽出（JSON優先 → テキストfallback）
     *  - {"overall_score":87} / {"score":87}
     *  - テキストは 0..100 の最後に出現した数値
     */
    fun extractScore(text: String): Int? {
        // JSON優先
        try {
            val start = text.indexOf('{')
            if (start >= 0) {
                val js = text.substring(start)
                val obj = JSONObject(js)
                val v = when {
                    obj.has("overall_score") -> obj.optDouble("overall_score", Double.NaN)
                    obj.has("score")         -> obj.optDouble("score", Double.NaN)
                    else -> Double.NaN
                }
                if (!v.isNaN()) return clamp0to100(v.toInt())
            }
        } catch (_: Throwable) { /* ignore */ }

        // テキストfallback（最後の 0..100）
        val regex = Regex("""\b(100|[1-9]?\d)\b""")
        val last = regex.findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        return last?.let(::clamp0to100)
    }
}
