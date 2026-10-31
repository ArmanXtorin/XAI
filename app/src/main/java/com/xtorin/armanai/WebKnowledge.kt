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


    fun searchLinks(topic: String): String {
        val q = URLEncoder.encode(topic.take(200), "UTF-8")
        return listOf(
            "Google: https://www.google.com/search?q=$q",
            "Reddit: https://www.reddit.com/search/?q=$q",
            "GitHub: https://github.com/search?q=$q",
            "Instagram: https://www.google.com/search?q=site%3Ainstagram.com+$q",
            "Telegram: https://www.google.com/search?q=site%3At.me+$q"
        ).joinToString("\\n")
    }

    private fun getJson(address: String): JSONObject? {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 7000
        conn.readTimeout = 9000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "XtorinAI/0.3 research client")
        return try {
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }

    fun github(topic: String): Result? {
        val q = URLEncoder.encode(topic.take(100), "UTF-8")
        val data = getJson("https://api.github.com/search/repositories?q=$q&per_page=3") ?: return null
        val items = data.optJSONArray("items") ?: return null
        val lines = (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("full_name")
            if (name.isBlank()) null else name + ": " + item.optString("description").take(240) +
                " (" + item.optString("html_url") + ")"
        }
        if (lines.isEmpty()) return null
        return Result("GitHub repositories", lines.joinToString("\\n"), "https://github.com/search?q=$q")
    }

    fun stackExchange(topic: String): Result? {
        val q = URLEncoder.encode(topic.take(100), "UTF-8")
        val data = getJson("https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&q=$q&site=stackoverflow&pagesize=3") ?: return null
        val items = data.optJSONArray("items") ?: return null
        val lines = (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val title = android.text.Html.fromHtml(item.optString("title"), android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            if (title.isBlank()) null else title + " (" + item.optString("link") + ")"
        }
        if (lines.isEmpty()) return null
        return Result("Stack Overflow questions", lines.joinToString("\\n"), "https://stackoverflow.com/search?q=$q")
    }

    fun openLibrary(topic: String): Result? {
        val q = URLEncoder.encode(topic.take(100), "UTF-8")
        val data = getJson("https://openlibrary.org/search.json?q=$q&limit=3") ?: return null
        val items = data.optJSONArray("docs") ?: return null
        val lines = (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title")
            if (title.isBlank()) null else title + " (" + item.optInt("first_publish_year") + ")"
        }
        if (lines.isEmpty()) return null
        return Result("Open Library books", lines.joinToString("\\n"), "https://openlibrary.org/search?q=$q")
    }

    fun googleSearchUrl(query: String): String =
        "https://www.google.com/search?q=" + URLEncoder.encode(query.take(300), "UTF-8")
}