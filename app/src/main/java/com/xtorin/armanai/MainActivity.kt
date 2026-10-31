package com.xtorin.armanai

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val prefs by lazy { getSharedPreferences("xtorinai_local", MODE_PRIVATE) }
    private val bg = Color.rgb(10,15,25)
    private val panel = Color.rgb(23,32,48)
    private val accent = Color.rgb(57,211,191)
    private lateinit var root: LinearLayout
    private lateinit var transcript: LinearLayout
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private var chatId = "default"
    private var tts: TextToSpeech? = null
    private val voiceCode = 71
    private val fileCode = 72
    private var pendingChatId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        chatId = prefs.getString("active_chat", "default") ?: "default"
        tts = TextToSpeech(this, this)
        showChat()
    }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }

    private fun shape(color: Int): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = 22f }
    private fun text(value: String, size: Float = 15f, color: Int = Color.WHITE): TextView =
        TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(12,12,12,12) }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false
        setTextColor(Color.WHITE); background = shape(panel)
        setOnClickListener { action() }
    }
    private fun screen(title: String): LinearLayout {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(14,14,14,14) }
        setContentView(root)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("☰") { showMenu() })
        header.addView(text(title,20f,accent), LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("＋") { newChat() })
        root.addView(header)
        return root
    }
    private fun messages(): JSONArray = try { JSONArray(prefs.getString("chat_$chatId", "[]")) } catch (_:Exception) { JSONArray() }
    private fun store(arr: JSONArray) { prefs.edit().putString("chat_$chatId",arr.toString()).apply() }
    private fun addMessage(value: String, user: Boolean) {
        val arr = messages()
        arr.put(JSONObject().put("text",value).put("user",user))
        store(arr)
        bubble(value,user)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun bubble(value: String, user: Boolean) {
        val bubble = text((if(user) "YOU  •  " else "XTORIN AI  •  ") + value,15f).apply {
            background = shape(if(user) Color.rgb(20,83,84) else panel)
            setPadding(18,16,18,16)
            setOnLongClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Xtorin AI",value))
                Toast.makeText(this@MainActivity,"Copied",Toast.LENGTH_SHORT).show()
                true
            }
        }
        transcript.addView(bubble, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=10 })
    }
    private fun showChat() {
        screen("◈ Xtorin AI")
        root.addView(text("LOCAL ASSISTANT  •  v0.2",11f,Color.LTGRAY))
        scroll = ScrollView(this).apply { isFillViewport = true }
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,12,0,12) }
        scroll.addView(transcript)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val arr = messages()
        for(i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            bubble(obj.optString("text"),obj.optBoolean("user"))
        }
        if(arr.length()==0) bubble("Welcome to Xtorin AI. Offline commands are ready. Type help to begin.",false)
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        composer.addView(button("📎") { selectFile() })
        composer.addView(button("🎤") { startVoice() })
        input = EditText(this).apply {
            hint = "Message or command…"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            textSize = 14f; minLines = 1; maxLines = 4
        }
        composer.addView(input,LinearLayout.LayoutParams(0,-2,1f))
        composer.addView(button("➤") { send() })
        root.addView(composer)
    }
    private fun send() {
        val value = input.text.toString().trim()
        if(value.isEmpty()) return
        input.setText("")
        addMessage(value,true)
        val endpoint = prefs.getString("ai_endpoint","").orEmpty().trim()
        val model = prefs.getString("ai_model","").orEmpty().trim()
        val command = value.lowercase(Locale.ROOT)
        val offline = command in listOf("help","time","date","memories","projects","history","settings","new chat","voice") ||
            listOf("remember ","recall ","clear memory ","teach ","define ","relate ").any { command.startsWith(it) }
        if(command.startsWith("/research ") || command.startsWith("/learn ")) {
            val learning = command.startsWith("/learn ")
            val topic = value.substringAfter(" ").trim()
            if(topic.isEmpty()) { addMessage("Use /research TOPIC or /learn TOPIC",false); return }
            val requestChatId = chatId
            addMessage("Researching Wikipedia…",false)
            Thread {
                val result = try { WebKnowledge.wikipedia(topic) } catch (_:Exception) { null }
                val answer = if(result == null)
                    "No Wikipedia summary found. Search manually: " + WebKnowledge.googleSearchUrl(topic)
                else {
                    if(learning) {
                        synchronized(prefs) {
                            val graph = conceptGraph()
                            ConceptMemory.define(graph,topic,result.summary + "\\nSource: " + result.source)
                            prefs.edit().putString("concept_graph",graph.toString()).apply()
                        }
                    }
                    (if(learning) "Saved locally.\\n" else "") +
                        result.title + ": " + result.summary + "\\nSource: " + result.source +
                        "\\nMore sources (search links, not automatically read):" +
                        "\\nGoogle: " + WebKnowledge.googleSearchUrl(topic) +
                        "\\nReddit: https://www.reddit.com/search/?q=" + java.net.URLEncoder.encode(topic,"UTF-8") +
                        "\\nGitHub: https://github.com/search?q=" + java.net.URLEncoder.encode(topic,"UTF-8")
                }
                runOnUiThread {
                    val arr = try { JSONArray(prefs.getString("chat_$requestChatId","[]")) } catch (_:Exception) { JSONArray() }
                    if(arr.length()>0 && arr.optJSONObject(arr.length()-1)?.optString("text")=="Researching Wikipedia…") arr.remove(arr.length()-1)
                    arr.put(JSONObject().put("text",answer).put("user",false))
                    prefs.edit().putString("chat_$requestChatId",arr.toString()).apply()
                    if(chatId==requestChatId) showChat()
                }
            }.start()
            return
        }
        if(endpoint.isNotEmpty() && model.isNotEmpty() && !offline) {
            val requestChatId = chatId
            val snapshot = JSONArray(messages().toString())
            val memory = allMemories()
            val key = prefs.getString("ai_key","").orEmpty()
            val kids = prefs.getBoolean("kids_mode",false)
            addMessage("Thinking…",false)
            Thread {
                val answer = try { Brain.ask(endpoint,key,model,snapshot,memory,kids) }
                    catch(e:Exception) { "AI connection failed: ${e.javaClass.simpleName}. Check your provider settings and internet." }
                runOnUiThread {
                    if(chatId != requestChatId) {
                        val other = try { JSONArray(prefs.getString("chat_$requestChatId","[]")) } catch (_:Exception) { JSONArray() }
                        if(other.length()>0 && other.optJSONObject(other.length()-1)?.optString("text")=="Thinking…") other.remove(other.length()-1)
                        other.put(JSONObject().put("text",answer).put("user",false))
                        prefs.edit().putString("chat_$requestChatId",other.toString()).apply()
                        return@runOnUiThread
                    }
                    val arr = messages()
                    if(arr.length()>0 && arr.optJSONObject(arr.length()-1)?.optString("text")=="Thinking…") {
                        arr.remove(arr.length()-1); store(arr)
                    }
                    showChat()
                    addMessage(answer,false)
                    if(prefs.getBoolean("speak",false)) tts?.speak(answer,TextToSpeech.QUEUE_FLUSH,null,"xtorin")
                }
            }.start()
        } else {
            val answer = reply(value)
            addMessage(answer,false)
            if(prefs.getBoolean("speak",false)) tts?.speak(answer,TextToSpeech.QUEUE_FLUSH,null,"xtorin")
        }
    }
    private fun newChat() {
        chatId = UUID.randomUUID().toString()
        prefs.edit().putString("active_chat",chatId).apply()
        val ids = JSONArray(prefs.getString("chat_ids","[]"))
        ids.put(chatId)
        prefs.edit().putString("chat_ids",ids.toString()).apply()
        showChat()
    }
    private fun showMenu() {
        screen("Navigation")
        val container = ScrollView(this)
        val items = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        container.addView(items); root.addView(container)
        fun entry(name:String, action:()->Unit) { items.addView(button(name,action),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=7 }) }
        entry("＋ New chat") { newChat() }
        entry("💬 Current chat") { showChat() }
        entry("🕘 Previous chats") { showHistory() }
        entry("📁 Projects") { showProjects() }
        entry("🖼 Media library") { showMedia() }
        entry("⌨ XCode") { showCode() }
        entry("🧠 Expert mode") { info("Expert mode",if(prefs.getString("ai_endpoint","").isNullOrBlank()) "Configure a compatible AI provider in Settings." else "Your configured AI model is available through Chat.") }
        entry("🎨 Image generation") { info("Image generation","Requires an image generation provider. No image is generated offline.") }
        entry("🖥 Windows connection") { showWindows() }
        entry("⚙ Settings") { showSettings() }
        entry("ℹ About") { info("Xtorin AI v0.3","Offline commands, optional AI provider, and local chats. Windows companion and image generation still require integrations.") }
    }
    private fun showHistory() {
        screen("Previous chats")
        val ids = JSONArray(prefs.getString("chat_ids","[]"))
        val all = mutableListOf("default")
        for(i in 0 until ids.length()) { val id=ids.optString(i); if(id.isNotBlank() && id !in all) all.add(id) }
        val list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val sc = ScrollView(this); sc.addView(list); root.addView(sc)
        all.reversed().forEachIndexed { index,id ->
            val arr = try { JSONArray(prefs.getString("chat_$id","[]")) } catch (_:Exception) { JSONArray() }
            val label = if(arr.length()>0) arr.optJSONObject(0)?.optString("text")?.take(45) ?: "Chat" else "Empty chat"
            list.addView(button("Chat ${all.size-index}: $label") { chatId=id; prefs.edit().putString("active_chat",id).apply(); showChat() })
        }
    }
    private fun showProjects() {
        screen("Projects")
        root.addView(text("Local project notes. Stored on this device.",13f,Color.LTGRAY))
        val list = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val sc=ScrollView(this); sc.addView(list); root.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
        val field=EditText(this).apply { hint="New project name"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        root.addView(field)
        fun render() {
            list.removeAllViews()
            val arr=JSONArray(prefs.getString("projects","[]"))
            for(i in 0 until arr.length()) {
                val item=arr.optString(i)
                list.addView(button("📁 $item") { editNote("project_$item",item) })
            }
        }
        root.addView(button("Create project") {
            val name=field.text.toString().trim()
            if(name.isNotEmpty()) {
                val arr=JSONArray(prefs.getString("projects","[]")); var exists=false
                for(i in 0 until arr.length()) if(arr.optString(i)==name) exists=true
                if(!exists) { arr.put(name); prefs.edit().putString("projects",arr.toString()).apply() }
                field.setText(""); render()
            }
        })
        render()
    }
    private fun editNote(key:String,title:String) {
        screen(title)
        val edit=EditText(this).apply { setTextColor(Color.WHITE); gravity=Gravity.TOP; minLines=12; setText(prefs.getString(key,"")) }
        root.addView(edit,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("Save locally") { prefs.edit().putString(key,edit.text.toString()).apply(); Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show() })
    }
    private fun showCode() { editNote("code_draft","XCode • Local scratchpad") }
    private fun showMedia() {
        screen("Media library")
        root.addView(text("Use the attachment button in chat to select a file. Android's file picker keeps access under your control."))
        root.addView(button("Choose file") { selectFile() })
        root.addView(text("Selected files are referenced by name in chat. They are not analyzed by an AI model.",13f,Color.LTGRAY))
    }
    private fun showSettings() {
        screen("Settings")
        val sc=ScrollView(this); val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        sc.addView(list); root.addView(sc)
        fun toggle(label:String,key:String,initial:Boolean=false) {
            val check=CheckBox(this).apply { text=label; setTextColor(Color.WHITE); isChecked=prefs.getBoolean(key,initial) }
            check.setOnCheckedChangeListener { _,checked -> prefs.edit().putBoolean(key,checked).apply() }
            list.addView(check)
        }
        list.addView(text("AI Brain • OpenAI-compatible provider",18f,accent))
        list.addView(text("Enter an HTTPS chat-completions URL, model ID and optional provider API key. Messages and saved memories will be sent to that provider.",12f,Color.LTGRAY))
        fun config(label:String,key:String,secret:Boolean=false) {
            val field=EditText(this).apply {
                hint=label; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
                setSingleLine(true)
                setText(prefs.getString(key,""))
                if(secret) inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            list.addView(field)
            field.setOnFocusChangeListener { _,focused -> if(!focused) prefs.edit().putString(key,field.text.toString().trim()).apply() }
            list.addView(button("Save $label") { prefs.edit().putString(key,field.text.toString().trim()).apply(); Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show() })
        }
        config("HTTPS endpoint (…/chat/completions)","ai_endpoint")
        config("Model ID","ai_model")
        config("API key (optional)","ai_key",true)
        list.addView(text("AI response customization",18f,accent))
        toggle("Speak responses aloud","speak")
        toggle("Concise response preference","concise",true)
        list.addView(text("Safety & access",18f,accent))
        list.addView(button("Parent controls • PIN-protected") { showParentControls() })
        list.addView(text("Kids mode can be managed in Parent controls. A local PIN is not a substitute for a supervised child account.",12f,Color.LTGRAY))
        list.addView(text("Core safeguards are not disabled by this setting.",12f,Color.LTGRAY))
        list.addView(text("Safe mode is a local preference, not a security filter or model safeguard.",12f,Color.LTGRAY))
        list.addView(text("Memory",18f,accent))
        list.addView(button("View saved memories") { info("Saved memories",allMemories()) })
        list.addView(button("Clear saved memories") {
            android.app.AlertDialog.Builder(this).setTitle("Clear memories?").setMessage("This removes locally saved memory entries.")
                .setNegativeButton("Cancel",null).setPositiveButton("Clear") { _,_ ->
                    val editor=prefs.edit()
                    prefs.all.keys.filter { it.startsWith("memory_") }.forEach { editor.remove(it) }
                    editor.apply()
                }.show()
        })
        list.addView(text("Account security",18f,accent))
        list.addView(text("Offline-only: no cloud account or login exists yet.",13f,Color.LTGRAY))
        list.addView(text("Accessibility",18f,accent))
        list.addView(text("Voice recognition uses an Android speech service and may require network access.",12f,Color.LTGRAY))
        list.addView(button("About app") { info("About Xtorin AI","Version 0.3 • Kotlin • Offline-first with optional HTTPS AI") })
    }
    private fun showWindows() {
        screen("Windows connection")
        root.addView(text("Desktop companion not yet available. Pairing requires a verified Windows app, authenticated connection and explicit device permission."))
        root.addView(text("Never expose a Windows control port directly to the internet.",13f,Color.LTGRAY))
    }
    private fun pinHash(pin:String, salt:String):String {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest((salt+":"+pin).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun showParentControls() {
        val stored=prefs.getString("parent_pin_hash",null)
        val field=EditText(this).apply {
            hint=if(stored==null) "Create a parent PIN (at least 6 digits)" else "Enter parent PIN"
            inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this).setTitle("Parent controls")
            .setMessage(if(stored==null) "Create a PIN to protect Kids Mode settings." else "Enter your parent PIN.")
            .setView(field).setNegativeButton("Cancel",null)
            .setPositiveButton("Continue") { _,_ ->
                val pin=field.text.toString()
                if(stored==null) {
                    if(pin.length>=6 && pin.all { it.isDigit() }) {
                        val salt=UUID.randomUUID().toString()
                        prefs.edit().putString("parent_pin_salt",salt).putString("parent_pin_hash",pinHash(pin,salt)).apply()
                        parentOptions()
                    } else info("Invalid PIN","Use at least six digits.")
                } else if(pinHash(pin,prefs.getString("parent_pin_salt","").orEmpty())==stored) parentOptions()
                else info("Access denied","Incorrect parent PIN.")
            }.show()
    }
    private fun parentOptions() {
        val options=arrayOf("Standard mode","Kids mode")
        val selected=if(prefs.getBoolean("kids_mode",false)) 1 else 0
        android.app.AlertDialog.Builder(this).setTitle("Parent controls")
            .setSingleChoiceItems(options,selected) { dialog,which ->
                prefs.edit().putBoolean("kids_mode",which==1).apply()
                dialog.dismiss()
                info("Mode updated",options[which]+" selected. Model providers may enforce additional protections.")
            }.setNegativeButton("Cancel",null).show()
    }
    private fun info(title:String,body:String) { android.app.AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show() }
    private fun startVoice() {
        try {
            val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT,"Speak to Xtorin AI")
            }
            startActivityForResult(intent,voiceCode)
        } catch (_:Exception) { info("Voice unavailable","No speech recognition service is available on this device.") }
    }
    private fun selectFile() {
        try {
            val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
            startActivityForResult(intent,fileCode)
        } catch (_:Exception) { info("Files unavailable","No compatible document picker found.") }
    }
    @Deprecated("Legacy Android activity result API")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK) return
        if(requestCode==voiceCode) {
            val words=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if(!words.isNullOrEmpty()) { showChat(); input.setText(words[0]) }
        } else if(requestCode==fileCode) {
            val uri=data?.data ?: return
            var name="Attachment"
            try {
                contentResolver.query(uri,null,null,null,null)?.use { c ->
                    val col=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if(col>=0 && c.moveToFirst()) name=c.getString(col)
                }
            } catch (_:Exception) {}
            showChat()
            addMessage("📎 Attached: $name",true)
            addMessage("File selected. Content analysis is not available in offline command mode.",false)
        }
    }
    private fun allMemories():String = prefs.all.keys.filter { it.startsWith("memory_") }.sorted()
        .joinToString("\n") { it.removePrefix("memory_")+": "+prefs.getString(it,"") }.ifBlank { "No saved memories." }
    private fun learnedExamples():JSONArray = try {
        JSONArray(prefs.getString("learned_examples","[]"))
    } catch (_:Exception) { JSONArray() }
    private fun conceptGraph():JSONObject = try {
        JSONObject(prefs.getString("concept_graph","{}"))
    } catch (_:Exception) { JSONObject() }
    private fun reply(message:String):String {
        val clean=message.trim(); val lower=clean.lowercase(Locale.ROOT)
        return when {
            lower=="help" -> "Commands: /research TOPIC, /learn TOPIC, help, time, date, remember KEY = VALUE, recall KEY, teach QUESTION => ANSWER, define WORD = MEANING, relate SUBJECT | RELATION | OBJECT, memories, projects, history, settings, new chat, voice."
            lower.startsWith("teach ") -> {
                val parts=clean.substring(6).split("=>",limit=2)
                if(parts.size!=2 || parts.any { it.isBlank() }) "Use: teach QUESTION => ANSWER"
                else {
                    val updated=PatternLearner.learn(learnedExamples(),parts[0],parts[1])
                    prefs.edit().putString("learned_examples",updated.toString()).apply()
                    "Got it. I'll remember how to respond to that."
                }
            }
            lower.startsWith("define ") -> {
                val parts=clean.substring(7).split("=",limit=2)
                if(parts.size!=2 || parts.any { it.isBlank() }) "Use: define WORD = MEANING"
                else {
                    val updated=ConceptMemory.define(conceptGraph(),parts[0],parts[1])
                    prefs.edit().putString("concept_graph",updated.toString()).apply()
                    "I've saved that definition."
                }
            }
            lower.startsWith("relate ") -> {
                val parts=clean.substring(7).split("|",limit=3)
                if(parts.size!=3 || parts.any { it.isBlank() }) "Use: relate SUBJECT | RELATION | OBJECT"
                else {
                    val updated=ConceptMemory.relate(conceptGraph(),parts[0],parts[1],parts[2])
                    prefs.edit().putString("concept_graph",updated.toString()).apply()
                    "I've linked those concepts."
                }
            }
            lower=="time" -> SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date())
            lower=="date" -> SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date())
            lower.startsWith("remember ") -> {
                val parts=clean.substring(9).split("=",limit=2)
                if(parts.size!=2 || parts[0].isBlank() || parts[1].isBlank()) "Use: remember KEY = VALUE"
                else { prefs.edit().putString("memory_"+parts[0].trim().lowercase(Locale.ROOT),parts[1].trim()).apply(); "Remembered: ${parts[0].trim()}" }
            }
            lower.startsWith("recall ") -> prefs.getString("memory_"+clean.substring(7).trim().lowercase(Locale.ROOT),null) ?: "I don't have that memory yet."
            lower=="memories" -> allMemories()
            lower.startsWith("clear memory ") -> { prefs.edit().remove("memory_"+clean.substring(13).trim().lowercase(Locale.ROOT)).apply(); "Memory cleared." }
            lower=="projects" -> { showProjects(); "Opening projects." }
            lower=="history" -> { showHistory(); "Opening history." }
            lower=="settings" -> { showSettings(); "Opening settings." }
            lower=="new chat" -> { newChat(); "New chat created." }
            lower=="voice" -> { startVoice(); "Starting speech recognition." }
            lower in listOf("hi","hello","hey") -> "Hello. Xtorin AI Mobile is operational."
            else -> ConversationEngine.respond(clean,messages(),learnedExamples(),conceptGraph())
        }
    }
}
