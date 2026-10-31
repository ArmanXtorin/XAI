package com.xtorin.armanai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Local, explicitly taught concept graph. Not a neural language model. */
object ConceptMemory {
    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").take(120)

    fun define(graph: JSONObject, term: String, meaning: String): JSONObject {
        val key = norm(term)
        require(key.isNotEmpty() && meaning.isNotBlank())
        val concepts = graph.optJSONObject("concepts") ?: JSONObject().also { graph.put("concepts", it) }
        concepts.put(key, meaning.trim().take(1500))
        return graph
    }

    fun relate(graph: JSONObject, subject: String, relation: String, target: String): JSONObject {
        val from = norm(subject)
        val kind = norm(relation)
        val to = norm(target)
        require(from.isNotEmpty() && kind.isNotEmpty() && to.isNotEmpty())
        val edges = graph.optJSONArray("relations") ?: JSONArray().also { graph.put("relations", it) }
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            if (edge.optString("from") == from && edge.optString("type") == kind && edge.optString("to") == to) return graph
        }
        edges.put(JSONObject().put("from", from).put("type", kind).put("to", to))
        if (edges.length() > 1000) edges.remove(0)
        return graph
    }

    fun lookup(graph: JSONObject, term: String): String? {
        val key = norm(term)
        if (key.isEmpty()) return null
        val facts = mutableListOf<String>()
        graph.optJSONObject("concepts")?.optString(key)?.takeIf { it.isNotBlank() }?.let {
            facts.add(key + ": " + it)
        }
        val edges = graph.optJSONArray("relations") ?: JSONArray()
        for (i in 0 until edges.length()) {
            val edge = edges.optJSONObject(i) ?: continue
            if (edge.optString("from") == key) {
                facts.add(key + " " + edge.optString("type") + " " + edge.optString("to"))
            }
        }
        return facts.take(20).joinToString("\n").ifBlank { null }
    }
}
