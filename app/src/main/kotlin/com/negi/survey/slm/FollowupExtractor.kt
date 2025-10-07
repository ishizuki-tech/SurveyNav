// file: app/src/main/java/com/negi/survey/slm/FollowupExtractor.kt
package com.negi.survey.slm

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Utility for extracting follow-up questions (and simple scores) from raw text or JSON.
 *
 * Features:
 * - Detects and parses one or more JSON fragments (JSONObject / JSONArray) embedded in free-form text.
 * - Traverses objects/arrays to find likely question-bearing fields.
 * - Deduplicates while preserving encounter order (via LinkedHashSet).
 * - Provides a light-weight 0..100 score extractor.
 *
 * Notes:
 * - Behavior is conservative: strings must be non-blank to be considered, and trailing '?/？'
 *   are normalized to a single terminal question mark while preserving full/half width.
 * - JSON scanning skips over string literals and escaped quotes to avoid false positives.
 */
object FollowupExtractor {

    /* -------------------- Configuration -------------------- */

    /**
     * We normalize keys before matching:
     *  - lowercase
     *  - convert any run of [space/_/unicode-dash] to a single '-'
     */
    private fun normKey(k: String): String =
        k.lowercase().replace(Regex("""[\s_​\u2010-\u2015]+"""), "-") // spaces, underscore, hyphen-like → '-'

    /** Normalized followup-like keys we consider as primary containers. */
    private val FOLLOWUP_KEYS_NORM: Set<String> = setOf(
        "followup", "follow-ups", "followup-questions", "follow-up-questions",
        "followups", "follow-up", "follow-up-q", "next-questions",
        "suggested-questions", "follow_up_questions".let(::normKey), "followUpQuestions".let(::normKey),
        "questions" // keep last; very broad but useful
    ).map(::normKey).toSet()

    /** Field candidates inside an object that may carry question text. */
    private val QUESTION_FIELD_CANDIDATES: List<String> =
        listOf("question", "text", "q", "content", "title", "prompt", "message", "body", "value")

    /** Trailing question marks (ASCII or full-width) to be coalesced to exactly one. */
    private val TRAILING_QUESTION_REGEX = Regex("[?？]+$")

    /** Matches integers 0..100; last match in the text is used for fallback scoring. */
    private val NUMBER_0_TO_100_REGEX = Regex("""\b(?:100|[1-9]?\d)\b""")

    /* -------------------- Public API -------------------- */

    /**
     * Extract follow-up questions from free-form [raw] text, which may contain one or more
     * JSON fragments. The result is deduplicated in encounter order and capped to [max].
     */
    @JvmStatic
    fun fromRaw(raw: String, max: Int = Int.MAX_VALUE): List<String> {
        val fragments = extractJsonFragments(raw)
        if (fragments.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (frag in fragments) {
            if (out.size >= max) break
            collect(frag, out, max)
        }
        return out.toList().take(max)
    }

    /**
     * Extract follow-up questions from a JSON-like root node or a list of nodes.
     */
    @JvmStatic
    fun fromJsonAny(any: Any, max: Int = Int.MAX_VALUE): List<String> {
        val out = LinkedHashSet<String>()
        when (any) {
            is List<*> -> for (elem in any) if (elem != null && out.size < max) collect(elem, out, max)
            else -> collect(any, out, max)
        }
        return out.toList().take(max)
    }

    /** Convenience: return the first follow-up question found in [rawText], or null if none. */
    @JvmStatic
    fun extractFollowupQuestion(rawText: String): String? =
        runCatching { fromRaw(rawText, max = 3).firstOrNull() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Extract an integer score in the range 0..100 from [text].
     * Strategy: (1) JSON key "overall_score" or "score" (first object), (2) last integer 0..100 in text.
     */
    @JvmStatic
    fun extractScore(text: String): Int? {
        runCatching {
            val fragments = extractJsonFragments(text)
            val firstObj = fragments.firstOrNull { it is JSONObject } as? JSONObject
            if (firstObj != null) {
                val v = when {
                    firstObj.has("overall_score") -> firstObj.optDouble("overall_score", Double.NaN)
                    firstObj.has("score") -> firstObj.optDouble("score", Double.NaN)
                    else -> Double.NaN
                }
                if (!v.isNaN()) return clamp0to100(v.toInt())
            }
        }
        val lastMatch = NUMBER_0_TO_100_REGEX.findAll(text).lastOrNull()
            ?.groupValues?.getOrNull(0)?.toIntOrNull()
        return lastMatch?.let(::clamp0to100)
    }

    /* -------------------- Internal helpers -------------------- */

    private fun clamp0to100(x: Int): Int = max(0, min(100, x))

    /**
     * Depth-first traversal collecting candidate questions into [out].
     */
    private fun collect(node: Any?, out: MutableSet<String>, max: Int) {
        if (node == null || out.size >= max) return
        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= max) break
                    val v = node.opt(i)
                    when (v) {
                        is String -> addIfMeaningful(v, out, max)
                        is JSONObject -> {
                            extractQuestionField(v)?.let { addIfMeaningful(it, out, max) }
                            collect(v, out, max)
                        }
                        is JSONArray -> collect(v, out, max)
                    }
                }
            }
            is JSONObject -> {
                // (1) Preferentially process followup-like keys (normalized).
                val iter1 = node.keys()
                while (iter1.hasNext() && out.size < max) {
                    val key = iter1.next()
                    if (FOLLOWUP_KEYS_NORM.contains(normKey(key))) {
                        when (val value = node.opt(key)) {
                            is String -> addIfMeaningful(value, out, max)
                            is JSONArray -> collect(value, out, max)
                            is JSONObject -> {
                                extractQuestionField(value)?.let { addIfMeaningful(it, out, max) }
                                collect(value, out, max)
                            }
                        }
                    }
                }
                if (out.size >= max) return

                // (2) Otherwise traverse all fields; pick strings in *question-like* fields; recurse into objects/arrays.
                val iter2 = node.keys()
                while (iter2.hasNext() && out.size < max) {
                    val k = iter2.next()
                    val v = node.opt(k)
                    when (v) {
                        is JSONArray, is JSONObject -> collect(v, out, max)
                        is String -> {
                            val kn = normKey(k)
                            val looksLikeQuestionField = (kn == "question") || QUESTION_FIELD_CANDIDATES.any {
                                kn.contains(normKey(it))
                            }
                            if (looksLikeQuestionField) addIfMeaningful(v, out, max)
                        }
                    }
                }
            }
            is String -> addIfMeaningful(node, out, max)
        }
    }

    /** Return the first non-blank string value found in common question-like fields of [obj]. */
    private fun extractQuestionField(obj: JSONObject): String? {
        // Strong match first
        for (f in QUESTION_FIELD_CANDIDATES) {
            val v = obj.opt(f)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        // Weak match: any field whose normalized name contains "question"
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (normKey(k).contains("question")) {
                val v = obj.opt(k)
                if (v is String && v.isNotBlank()) return v.trim()
            }
        }
        return null
    }

    /** Add a normalized non-empty string to [out] if still under [max]. */
    private fun addIfMeaningful(s: String, out: MutableSet<String>, max: Int) {
        if (out.size >= max) return
        val t = s.trim()
        if (t.isEmpty()) return
        val normalized = TRAILING_QUESTION_REGEX.replace(t) { m ->
            if (m.value.contains('？')) "？" else "?"
        }
        out.add(normalized)
    }

    /**
     * Extract JSON fragments embedded in [raw].
     * - Removes ```...``` fences with optional language (e.g., ```json).
     * - Tries whole-string parse first; otherwise scans for balanced {...}/[...] fragments while skipping strings.
     */
    private fun extractJsonFragments(raw: String): List<Any> {
        val s0 = stripCodeFences(raw.trim())
        val fragments = mutableListOf<Any>()

        // Quick path: whole string is a single JSON value.
        parseAny(s0)?.let { fragments.add(it); return fragments }

        // Scan for multiple fragments with brace/bracket matching.
        val n = s0.length
        var i = 0
        while (i < n) {
            val ch = s0[i]
            if (ch == '{' || ch == '[') {
                val start = i
                val stack = ArrayDeque<Char>()
                stack.addLast(ch)
                var inString = false
                i++ // move past opener
                while (i < n && stack.isNotEmpty()) {
                    val c = s0[i]
                    if (inString) {
                        if (c == '\\') {
                            // Skip escaped char safely
                            i += if (i + 1 < n) 2 else 1
                            continue
                        } else if (c == '"') {
                            inString = false
                        }
                    } else {
                        when (c) {
                            '"' -> inString = true
                            '{' -> stack.addLast('{')
                            '[' -> stack.addLast('[')
                            '}' -> if (stack.isNotEmpty() && stack.last() == '{') stack.removeLast()
                            ']' -> if (stack.isNotEmpty() && stack.last() == '[') stack.removeLast()
                        }
                    }
                    i++
                }
                val endIdx = i
                if (stack.isEmpty()) {
                    val frag = s0.substring(start, endIdx)
                    parseAny(frag)?.let { fragments.add(it) }
                    continue
                } else {
                    // Unbalanced; skip this opener and continue.
                    i = start + 1
                }
            } else {
                i++
            }
        }
        return fragments
    }

    /** Remove ```...``` fences with optional language tag (```json, ```JSON etc.). */
    private fun stripCodeFences(s: String): String {
        val fenceRe = Regex("""^```[A-Za-z0-9_-]*\s*\n([\s\S]*?)\n```$""")
        val m = fenceRe.find(s)
        return m?.groupValues?.getOrNull(1)?.trim() ?: s.removeSurrounding("```", "```").trim()
    }

    /** Try to parse [s] into a JSONObject or JSONArray; returns null on failure. */
    private fun parseAny(s: String): Any? = try {
        val t = s.trim()
        when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}
