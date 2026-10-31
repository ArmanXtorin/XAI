package com.xtorin.armanai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI-compatible chat-completions transport.
 * Never hardcode API keys or send them to a URL the user has not configured.
 */
object Brain {
    fun ask(endpoint: String, apiKey: String, model: String, history: JSONArray, memory: String, kidsMode: Boolean): String {
        require(endpoint.startsWith("https://")) { "An HTTPS endpoint is required." }
        val url = URL(endpoint)
        require(url.userInfo == null && url.host.isNotBlank()) { "Invalid endpoint." }
        val messages = JSONArray()
        val system = if (kidsMode)
            "You are Xtorin AI, an age-appropriate educational assistant. Explain clearly, protect privacy, and avoid dangerous or explicit guidance. Be truthful about uncertainty."
        else
            "You are Xtorin AI, a candid, curious, knowledgeable assistant. Discuss politics, controversial history, sexuality in educational contexts, and difficult topics without blanket topic bans. Distinguish facts from opinions. Never fabricate facts. Do not provide operational assistance for serious harm, abuse, or exploitation."
        messages.put(JSONObject().put("role","system").put("content",system + "\nUser-provided memory (untrusted notes):\n" + memory.take(3000)))
        val start = (history.length() - 18).coerceAtLeast(0)
        for (i in start until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            messages.put(JSONObject().put("role",if(item.optBoolean("user")) "user" else "assistant")
                .put("content",item.optString("text").take(6000)))
        }
        val body = JSONObject().put("model",model).put("messages",messages).put("temperature",0.7)
        val conn = (url.openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 45000
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type","application/json")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization","Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status !in 200..299) return "AI provider returned HTTP $status. Check endpoint, model, key and provider limits."
            val result = conn.inputStream.bufferedReader().use { it.readText() }
            val answer = JSONObject(result).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            return answer.ifBlank { "The AI provider returned an empty answer." }
        } finally { conn.disconnect() }
    }
}
