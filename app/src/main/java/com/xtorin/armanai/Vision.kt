package com.xtorin.armanai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opt-in image analysis. No photo is sent until the user confirms the upload dialog.
 * The Cloudflare Worker requires its own authentication and abuse controls.
 */
object Vision {
    private fun jpegDataUri(context: Context, uri: Uri): String {
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it,null,options)
        } ?: error("Image unavailable")
        require(options.outWidth>0 && options.outHeight>0) { "Unsupported image" }
        var sample=1
        while (options.outWidth/sample>1024 || options.outHeight/sample>1024) sample*=2
        val decodeOptions=BitmapFactory.Options().apply { inSampleSize=sample }
        val bitmap=context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it,null,decodeOptions)
        } ?: error("Unable to decode image")
        try {
            val bytes=ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG,78,bytes)
            val image=bytes.toByteArray()
            require(image.size<=700_000) { "Image too large after compression" }
            return "data:image/jpeg;base64,"+Base64.encodeToString(image,Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }

    fun analyze(context: Context, uri: Uri, chatEndpoint: String, key: String, question: String): String {
        require(chatEndpoint.startsWith("https://") && chatEndpoint.endsWith("/v1/chat/completions")) {
            "Configure the XAI Cloud HTTPS chat endpoint first"
        }
        val visionUrl=chatEndpoint.removeSuffix("/v1/chat/completions")+"/v1/vision/analyze"
        val url=URL(visionUrl)
        require(url.protocol=="https" && url.userInfo==null && url.host.isNotBlank()) { "Invalid endpoint" }
        val image=jpegDataUri(context,uri)
        val request=JSONObject().put("image",image).put("question",question.take(300)).toString()
        val connection=url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod="POST"
            connection.connectTimeout=15000
            connection.readTimeout=60000
            connection.instanceFollowRedirects=false
            connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json")
            if(key.isNotBlank()) connection.setRequestProperty("Authorization","Bearer $key")
            connection.outputStream.use { it.write(request.toByteArray(Charsets.UTF_8)) }
            val status=connection.responseCode
            if(status !in 200..299) return "Vision server returned HTTP $status. Check Cloudflare Access, license, and configuration."
            val response=JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return response.optString("answer").ifBlank { "No image description returned." }
        } finally { connection.disconnect() }
    }
}
