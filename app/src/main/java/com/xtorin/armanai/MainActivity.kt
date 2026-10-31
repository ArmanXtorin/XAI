package com.xtorin.armanai

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var transcript: LinearLayout
    private lateinit var input: EditText
    private val prefs by lazy { getSharedPreferences("xtorinai_local", MODE_PRIVATE) }
    private val history = JSONArray()
    private val accent = Color.rgb(57, 211, 191)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 15, 25)
        window.navigationBarColor = Color.rgb(10, 15, 25)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(10, 15, 25)); setPadding(18, 20, 18, 12) }
        val title = TextView(this).apply { text = "◈  Xtorin AI"; textSize = 26f; setTextColor(accent); setTypeface(null, Typeface.BOLD) }
        val subtitle = TextView(this).apply { text = "MOBILE  •  LOCAL MODE  •  v0.1"; textSize = 11f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 18) }
        root.addView(title); root.addView(subtitle)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(transcript)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0) }
        input = EditText(this).apply { hint = "Message Xtorin AI..."; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); textSize = 15f; setSingleLine(false); minLines = 1; maxLines = 3; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val send = Button(this).apply { text = "SEND"; setTextColor(Color.BLACK); backgroundTintList = android.content.res.ColorStateList.valueOf(accent) }
        composer.addView(send)
        root.addView(composer)
        setContentView(root)
        val stored = prefs.getString("history", "[]") ?: "[]"
        try {
            val arr = JSONArray(stored)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                history.put(item)
                addBubble(item.optString("text"), item.optBoolean("user"))
            }
        } catch (_: Exception) { prefs.edit().remove("history").apply() }
        if (history.length() == 0) addBubble("Xtorin AI online. Offline commands ready. Try 'help'.", false)
        send.setOnClickListener {
            val message = input.text.toString().trim()
            if (message.isNotEmpty()) {
                input.setText("")
                record(message, true)
                record(reply(message), false)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun record(text: String, user: Boolean) {
        history.put(JSONObject().put("text", text).put("user", user))
        prefs.edit().putString("history", history.toString()).apply()
        addBubble(text, user)
    }

    private fun addBubble(text: String, user: Boolean) {
        val bubble = TextView(this).apply {
            this.text = (if (user) "YOU  •  " else "XTORIN AI  •  ") + text
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(16, 13, 16, 13)
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(if (user) Color.rgb(23, 83, 86) else Color.rgb(29, 39, 57))
            }
        }
        val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        transcript.addView(bubble, lp)
    }

    private fun reply(message: String): String {
        val clean = message.trim()
        val lower = clean.lowercase(Locale.ROOT)
        return when {
            lower == "help" -> "Commands: help, time, date, remember KEY = VALUE, recall KEY, memories, clear memory KEY. Chat is stored on this device."
            lower == "time" -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            lower == "date" -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            lower.startsWith("remember ") -> {
                val data = clean.substring(9).split("=", limit = 2)
                if (data.size != 2 || data[0].isBlank() || data[1].isBlank()) "Use: remember KEY = VALUE"
                else {
                    prefs.edit().putString("memory_" + data[0].trim().lowercase(Locale.ROOT), data[1].trim()).apply()
                    "Remembered: ${data[0].trim()}"
                }
            }
            lower.startsWith("recall ") -> prefs.getString("memory_" + clean.substring(7).trim().lowercase(Locale.ROOT), null) ?: "I don't have that memory yet."
            lower == "memories" -> prefs.all.keys.filter { it.startsWith("memory_") }.sorted().joinToString("\n") { it.removePrefix("memory_") + ": " + prefs.getString(it, "") }.ifBlank { "No saved memories." }
            lower.startsWith("clear memory ") -> {
                prefs.edit().remove("memory_" + clean.substring(13).trim().lowercase(Locale.ROOT)).apply()
                "Memory entry cleared."
            }
            lower in listOf("hi", "hello", "hey") -> "Hello. Xtorin AI Mobile is operational."
            else -> "I'm running in offline command mode, not a language model yet. Type 'help' for supported commands."
        }
    }
}
