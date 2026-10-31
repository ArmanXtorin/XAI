package com.xtorin.armanai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Conversational response layer for the offline prototype.
 * Uses dialogue context, known concepts and taught examples.
 * Not a generative language model: natural, open-ended dialogue needs an LLM.
 */
object ConversationEngine {
    fun respond(
        input: String,
        history: JSONArray,
        examples: JSONArray,
        concepts: JSONObject
    ): String {
        val text = input.trim()
        val lower = text.lowercase(Locale.ROOT)
        if (text.isEmpty()) return "I'm listening."
        PatternLearner.predict(examples, text, 0.72)?.let { return it }

        val topic = when {
            lower.startsWith("what is ") -> text.substring(8).trimEnd('?', '.', ' ')
            lower.startsWith("who is ") -> text.substring(7).trimEnd('?', '.', ' ')
            lower.startsWith("tell me about ") -> text.substring(14).trimEnd('?', '.', ' ')
            lower.startsWith("explain ") -> text.substring(8).trimEnd('?', '.', ' ')
            lower.startsWith("define ") -> text.substring(7).trimEnd('?', '.', ' ')
            lower.startsWith("what are ") -> text.substring(9).trimEnd('?', '.', ' ')
            else -> ""
        }
        if (topic.isNotEmpty()) {
            ConceptMemory.lookup(concepts, topic)?.let {
                return "Here's what I know about " + topic + ": " + it
            }
        }

        // Retrieve an exact stored topic, then try distinctive phrases in the question.
        val words = lower.replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+"))
            .filter { it.length > 3 && it !in setOf("what","where","when","does","about","tell","explain","please","know") }
        val candidates = (3 downTo 1).flatMap { n -> words.windowed(n).map { it.joinToString(" ") } }
        for (candidate in candidates) {
            ConceptMemory.lookup(concepts,candidate)?.let { return "From saved knowledge:\n" + it }
        }
        val recent = (history.length() - 1 downTo 0)
            .mapNotNull { history.optJSONObject(it) }
            .firstOrNull { it.optBoolean("user") && it.optString("text") != text }
            ?.optString("text").orEmpty()

        return when {
            lower in setOf("hi", "hello", "hey", "hello there") ->
                "Hey. What's on your mind?"
            lower in setOf("thanks", "thank you", "thx") ->
                "You're welcome."
            lower in setOf("bye", "goodbye", "see you") ->
                "See you later."
            lower.contains("how are you") ->
                "I'm running normally. What are we working on?"
            lower.endsWith("?") && recent.isNotBlank() ->
                "I don't know the answer yet. Is this connected to what you said earlier about " +
                    recent.take(70).trimEnd('.', '?') + "?"
            lower.endsWith("?") ->
                "I don't know that yet. I can remember an explanation you teach me, or use a connected language model."
            else ->
                "I follow what you're saying, but I don't have enough understanding to respond properly yet. Tell me more."
        }
    }
}
