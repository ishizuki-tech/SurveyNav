package com.negi.survey.slm

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Utility to extract "follow-up questions" from raw text / JSON.
 *
 * - Automatically detects JSON fragments (JSONObject / JSONArray) inside raw text
 *   and supports multiple JSON fragments present in the same string.
 * - The JSON fragment extraction is robust to JSON string literals and escaped quotes.
 * - Prefers followup-like keys, also considers fields like question / text / title.
 * - Deduplicates while preserving order (LinkedHashSet).
 */
object FollowupExtractor {

    // -------------------- configuration --------------------
    private val FOLLOWUP_KEYS = setOf(
        "followup", "follow_up", "follow-ups", "followups",
        "follow_up_questions", "followUpQuestions", "followupQuestions",
        "next_questions", "nextQuestions",
        "suggested_questions", "suggestedQuestions",
        "questions", "prompts", "suggestions"
    )

    private val QUESTION_FIELD_CANDIDATES = listOf(
        "question", "text", "q", "content", "title", "prompt"
    )

    private val trailingQuestionRegex = Regex("[?？]+$")
    private val numberRegex = Regex("""\b(?:100|[1-9]?\d)\b""") // matches 0..100

    // -------------------- public API --------------------

    /**
     * Extract follow-up questions from raw text (which may contain one or more JSON fragments).
     * Returns up to `max` items (default: no limit).
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
     * Extract from arbitrary JSONObject / JSONArray / String-like structures.
     * If a List (multiple fragments) is provided, traverse each item.
     */
    @JvmStatic
    fun fromJsonAny(any: Any, max: Int = Int.MAX_VALUE): List<String> {
        val out = LinkedHashSet<String>()
        when (any) {
            is List<*> -> {
                for (it in any) {
                    if (it != null && out.size < max) collect(it, out, max)
                }
            }
            else -> collect(any, out, max)
        }
        return out.toList().take(max)
    }

    /**
     * Return the first follow-up question found in rawText, or null if none.
     */
    fun extractFollowupQuestion(rawText: String): String? {
        return runCatching { fromRaw(rawText, max = 1).firstOrNull() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Extract an integer score in the range 0..100 from text.
     *
     * - Prefer JSON: find the first JSON object and look for "overall_score" or "score".
     * - Fallback: find the last occurrence of a number between 0 and 100 in the text.
     */
    fun extractScore(text: String): Int? {
        // 1) JSON-prioritized extraction
        runCatching {
            val start = text.indexOf('{')
            if (start >= 0) {
                val js = text.substring(start).trim()
                val obj = JSONObject(js)
                val v = when {
                    obj.has("overall_score") -> obj.optDouble("overall_score", Double.NaN)
                    obj.has("score") -> obj.optDouble("score", Double.NaN)
                    else -> Double.NaN
                }
                if (!v.isNaN()) return clamp0to100(v.toInt())
            }
        }

        // 2) Text fallback — last 0..100 match
        val lastMatch = numberRegex.findAll(text).lastOrNull()?.groupValues?.get(0)?.toIntOrNull()
        return lastMatch?.let(::clamp0to100)
    }

    // -------------------- internal helpers --------------------

    private fun clamp0to100(x: Int): Int = max(0, min(100, x))

    /**
     * Depth-first traversal of node (JSONObject / JSONArray / String) and collect
     * candidate questions into `out` (a LinkedHashSet to preserve insertion order).
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
                        else -> { /* ignore other types */ }
                    }
                }
            }

            is JSONObject -> {
                // 1) Prefer processing followup-like keys first
                val keysIter = node.keys()
                while (keysIter.hasNext() && out.size < max) {
                    val key = keysIter.next()
                    val value = node.opt(key)
                    if (key in FOLLOWUP_KEYS) {
                        when (value) {
                            is String -> addIfMeaningful(value, out, max)
                            is JSONArray -> collect(value, out, max)
                            is JSONObject -> {
                                extractQuestionField(value)?.let { addIfMeaningful(it, out, max) }
                                collect(value, out, max)
                            }
                            else -> { /* ignore */ }
                        }
                    }
                }

                if (out.size >= max) return

                // 2) Otherwise, traverse fields and pick up "question" fields conservatively
                val allKeys = node.keys()
                while (allKeys.hasNext() && out.size < max) {
                    val k = allKeys.next()
                    val v = node.opt(k)
                    when (v) {
                        is JSONArray, is JSONObject -> collect(v, out, max)
                        is String -> {
                            if (k.equals("question", ignoreCase = true)) {
                                addIfMeaningful(v, out, max)
                            }
                        }
                        else -> { /* ignore */ }
                    }
                }
            }

            is String -> addIfMeaningful(node, out, max)
            else -> { /* ignore other types */ }
        }
    }

    /**
     * From a JSONObject, return the first non-blank value found in common
     * question-like fields (question, text, title, etc.), or null if none.
     */
    private fun extractQuestionField(obj: JSONObject): String? {
        for (f in QUESTION_FIELD_CANDIDATES) {
            val v = obj.opt(f)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return null
    }

    /**
     * Add non-empty strings to the output set. Normalize trailing ?/？ sequences
     * into a single ? or ？ as in the original behavior.
     */
    private fun addIfMeaningful(s: String, out: MutableSet<String>, max: Int) {
        if (out.size >= max) return
        val t = s.trim()
        if (t.isEmpty()) return

        val normalized = trailingQuestionRegex.replace(t) { match ->
            // If the trailing match contains a full-width question mark, normalize to full-width
            if (match.value.contains('？')) "？" else "?"
        }

        out.add(normalized)
    }

    /**
     * Extract JSON fragments present in the raw text.
     *
     * This function:
     *  - removes surrounding triple-backtick fences (if any)
     *  - scans the text and detects JSON object/array fragments by matching braces/brackets
     *    while correctly skipping over string literals and escaped quotes.
     *  - returns a list of parsed objects (JSONObject or JSONArray) in appearance order.
     */
    private fun extractJsonFragments(raw: String): List<Any> {
        val s0 = raw.trim().removeSurrounding("```", "```").trim()
        val fragments = mutableListOf<Any>()

        // Try quick parse for the whole string first
        parseAny(s0)?.let { fragments.add(it); return fragments }

        // Otherwise scan for multiple fragments
        val n = s0.length
        var i = 0
        while (i < n) {
            val ch = s0[i]
            if (ch == '{' || ch == '[') {
                val start = i
                val stack = ArrayDeque<Char>()
                stack.addLast(ch)
                var inString = false
                i++ // move to next char after opening
                while (i < n && stack.isNotEmpty()) {
                    val c = s0[i]
                    if (inString) {
                        if (c == '\\') {
                            // skip escaped char
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
                            '}' -> {
                                if (stack.isNotEmpty() && stack.last() == '{') stack.removeLast()
                                else { /* mismatch - still continue */ }
                            }
                            ']' -> {
                                if (stack.isNotEmpty() && stack.last() == '[') stack.removeLast()
                                else { /* mismatch - still continue */ }
                            }
                            else -> { /* nothing */ }
                        }
                    }
                    i++
                }
                // i is position after the closing bracket (or n)
                val endIdx = i // exclusive
                if (stack.isEmpty()) {
                    val frag = s0.substring(start, endIdx)
                    parseAny(frag)?.let { fragments.add(it) }
                    // continue scanning from endIdx (i already points to it)
                    continue
                } else {
                    // Unbalanced fragment: skip this opening and continue scanning from start+1
                    i = start + 1
                    continue
                }
            } else {
                i++
            }
        }

        return fragments
    }

    /**
     * Try to parse a single JSON text into JSONObject/JSONArray.
     */
    private fun parseAny(s: String): Any? = try {
        when {
            s.trim().startsWith("{") -> JSONObject(s)
            s.trim().startsWith("[") -> JSONArray(s)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}
