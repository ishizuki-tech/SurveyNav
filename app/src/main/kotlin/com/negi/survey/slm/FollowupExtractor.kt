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

    /** Keys that likely contain follow-up questions or a list of them (case-sensitive by design). */
    private val FOLLOWUP_KEYS: Set<String> = setOf(
        "followup", "follow_up", "follow-ups", "followups",
        "follow_up_questions", "followUpQuestions", "followupQuestions",
        "next_questions", "nextQuestions",
        "suggested_questions", "suggestedQuestions",
        "questions", "prompts", "suggestions"
    )

    /** Field candidates inside an object that may carry question text. */
    private val QUESTION_FIELD_CANDIDATES: List<String> =
        listOf("question", "text", "q", "content", "title", "prompt")

    /** Trailing question marks (ASCII or full-width) to be coalesced to exactly one. */
    private val TRAILING_QUESTION_REGEX = Regex("[?？]+$")

    /** Matches integers 0..100; last match in the text is used for fallback scoring. */
    private val NUMBER_0_TO_100_REGEX = Regex("""\b(?:100|[1-9]?\d)\b""")

    /* -------------------- Public API -------------------- */

    /**
     * Extract follow-up questions from free-form [raw] text, which may contain one or more
     * JSON fragments. The result is deduplicated in encounter order and capped to [max].
     *
     * @param raw Source text that may embed JSON objects/arrays.
     * @param max Max number of questions to return (default: [Int.MAX_VALUE]).
     * @return A list of unique candidate questions, in encounter order.
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
     *
     * @param any Root node (JSONObject / JSONArray / String). If it's a [List], every element is traversed.
     * @param max Max number of questions to return.
     * @return A list of unique candidate questions, in encounter order.
     */
    @JvmStatic
    fun fromJsonAny(any: Any, max: Int = Int.MAX_VALUE): List<String> {
        val out = LinkedHashSet<String>()
        when (any) {
            is List<*> -> {
                for (elem in any) {
                    if (elem != null && out.size < max) collect(elem, out, max)
                }
            }
            else -> collect(any, out, max)
        }
        return out.toList().take(max)
    }

    /**
     * Convenience: return the first follow-up question found in [rawText], or null if none.
     *
     * @param rawText Source text (may embed JSON).
     * @return First extracted question or null.
     */
    @JvmStatic
    fun extractFollowupQuestion(rawText: String): String? =
        runCatching { fromRaw(rawText, max = 1).firstOrNull() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Extract an integer score in the range 0..100 from [text].
     *
     * Strategy:
     * 1) Prefer JSON: parse the first JSON fragment encountered and look up "overall_score" or "score".
     * 2) Fallback: use the last integer token 0..100 appearing in the free-form text.
     *
     * @param text Text possibly containing a JSON object or a plain numeric mention.
     * @return Clamped score 0..100, or null if none found.
     */
    @JvmStatic
    fun extractScore(text: String): Int? {
        // 1) JSON-first: try to parse the first JSON fragment (object preferred).
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

        // 2) Fallback: take the last 0..100 match found in raw text.
        val lastMatch = NUMBER_0_TO_100_REGEX.findAll(text).lastOrNull()
            ?.groupValues?.getOrNull(0)?.toIntOrNull()
        return lastMatch?.let(::clamp0to100)
    }

    /* -------------------- Internal helpers -------------------- */

    /** Clamp the given integer into [0, 100]. */
    private fun clamp0to100(x: Int): Int = max(0, min(100, x))

    /**
     * Depth-first traversal collecting candidate questions into [out].
     *
     * Supported node types:
     * - [JSONArray]: iterates elements; for strings adds directly; for objects tries known fields then recurses.
     * - [JSONObject]: prioritizes keys in [FOLLOWUP_KEYS], then conservatively scans other fields.
     * - [String]: added if meaningful after normalization.
     *
     * @param node Root node to traverse.
     * @param out Deduplication sink preserving insertion order.
     * @param max Stop collecting when size reaches this bound.
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
                        else -> Unit // ignore other types
                    }
                }
            }

            is JSONObject -> {
                // (1) Preferentially process followup-like keys.
                val keysIter = node.keys()
                while (keysIter.hasNext() && out.size < max) {
                    val key = keysIter.next()
                    if (key in FOLLOWUP_KEYS) {
                        when (val value = node.opt(key)) {
                            is String -> addIfMeaningful(value, out, max)
                            is JSONArray -> collect(value, out, max)
                            is JSONObject -> {
                                extractQuestionField(value)?.let { addIfMeaningful(it, out, max) }
                                collect(value, out, max)
                            }
                            else -> Unit
                        }
                    }
                }

                if (out.size >= max) return

                // (2) Otherwise traverse remaining fields and pick up "question" strings conservatively.
                val allKeys = node.keys()
                while (allKeys.hasNext() && out.size < max) {
                    val k = allKeys.next()
                    val v = node.opt(k)
                    when (v) {
                        is JSONArray, is JSONObject -> collect(v, out, max)
                        is String -> if (k.equals("question", ignoreCase = true)) {
                            addIfMeaningful(v, out, max)
                        }
                        else -> Unit
                    }
                }
            }

            is String -> addIfMeaningful(node, out, max)

            else -> Unit // ignore other types
        }
    }

    /**
     * Return the first non-blank string value found in common question-like fields of [obj].
     *
     * @param obj Source JSON object.
     * @return The first matching field value or null.
     */
    private fun extractQuestionField(obj: JSONObject): String? {
        for (f in QUESTION_FIELD_CANDIDATES) {
            val v = obj.opt(f)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return null
    }

    /**
     * Add a normalized non-empty string to [out] if still under [max].
     *
     * Normalization:
     * - Trim whitespace.
     * - Coalesce any trailing run of '?/？' into a single character, preserving full-width if present.
     */
    private fun addIfMeaningful(s: String, out: MutableSet<String>, max: Int) {
        if (out.size >= max) return
        val t = s.trim()
        if (t.isEmpty()) return

        val normalized = TRAILING_QUESTION_REGEX.replace(t) { match ->
            if (match.value.contains('？')) "？" else "?"
        }

        out.add(normalized)
        // Explanation: LinkedHashSet preserves order and removes duplicates.
    }

    /**
     * Extract JSON fragments embedded in [raw].
     *
     * Steps:
     * - Remove surrounding triple backtick fences if present.
     * - Try to parse the entire string as a single JSON value first.
     * - Otherwise, scan for multiple balanced object/array fragments while skipping
     *   string literals and escape sequences, and parse each fragment independently.
     *
     * @param raw Free-form text potentially containing JSON.
     * @return Parsed fragments (each either [JSONObject] or [JSONArray]) in appearance order.
     */
    private fun extractJsonFragments(raw: String): List<Any> {
        val s0 = raw.trim().removeSurrounding("```", "```").trim()
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
                i++ // move to the char after the opener
                while (i < n && stack.isNotEmpty()) {
                    val c = s0[i]
                    if (inString) {
                        if (c == '\\') {
                            // Skip escaped char; safe because we only need structure, not content.
                            i += 2
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
                // i now points one past the last consumed char (possibly n).
                val endIdx = i
                if (stack.isEmpty()) {
                    val frag = s0.substring(start, endIdx)
                    parseAny(frag)?.let { fragments.add(it) }
                    // Continue scanning from endIdx (i already updated).
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

    /**
     * Try to parse [s] into a [JSONObject] or [JSONArray]; returns null on failure.
     */
    private fun parseAny(s: String): Any? = try {
        val trimmed = s.trim()
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}
