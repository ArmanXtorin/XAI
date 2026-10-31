package com.xtorin.armanai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Free public encyclopedia lookup. Network access required. */
object WebKnowledge {
    data class Result(val title: String, val summary: String, val source: String)

    fun wikipedia(topic: String): Result? {
        val term = topic.trim().take(160)
        if (term.isEmpty()) return null
        val encoded = URLEncoder.encode(term, "UTF-8").replace("+", "%20")
        val url = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 12000
        connection.setRequestProperty("User-Agent", "XtorinAI/0.3 (educational Android app)")
        try {
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optString("type") == "disambiguation") return null
            val summary = json.optString("extract").take(2400)
            if (summary.isBlank()) return null
            val source = json.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty()
            return Result(json.optString("title", term), summary, source)
        } finally { connection.disconnect() }
    }

    fun googleSearchUrl(query: String): String =
        "https://www.google.com/search?q=" + URLEncoder.encode(query.take(300), "UTF-8")
}