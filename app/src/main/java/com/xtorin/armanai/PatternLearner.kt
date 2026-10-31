package com.xtorin.armanai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Small, deterministic pattern learner. No neural network or model training.
 * Stores examples and retrieves the nearest matching example by token overlap.
 * All state is caller-owned JSON, suitable for local persistence.
 */
object PatternLearner {
    private fun tokens(text: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase(Locale.ROOT))
            .map { it.value }.filter { it.length > 1 }.toSet()

    fun learn(data: JSONArray, input: String, response: String): JSONArray {
        require(input.isNotBlank() && response.isNotBlank()) { "Examples cannot be empty." }
        val normalized = input.trim().take(500)
        val answer = response.trim().take(2000)
        for (i in 0 until data.length()) {
            val example = data.optJSONObject(i) ?: continue
            if (example.optString("input").equals(normalized, ignoreCase = true)) {
                example.put("response", answer)
                return data
            }
        }
        data.put(JSONObject().put("input", normalized).put("response", answer))
        if (data.length() > 500) data.remove(0)
        return data
    }

    fun predict(data: JSONArray, query: String, threshold: Double = 0.55): String? {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return null
        var bestScore = 0.0
        var bestAnswer: String? = null
        for (i in 0 until data.length()) {
            val example = data.optJSONObject(i) ?: continue
            val exampleTokens = tokens(example.optString("input"))
            val union = queryTokens union exampleTokens
            if (union.isEmpty()) continue
            val score = (queryTokens intersect exampleTokens).size.toDouble() / union.size
            if (score > bestScore) {
                bestScore = score
                bestAnswer = example.optString("response")
            }
        }
        return if (bestScore >= threshold) bestAnswer else null
    }
}
