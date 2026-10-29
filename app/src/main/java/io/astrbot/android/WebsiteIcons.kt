package io.astrbot.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Fetch only public site icons, without sharing WebView cookies or service credentials. */
internal object WebsiteIcons {
    private val slots = Semaphore(3)
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).followSslRedirects(false).build()
    private fun cache(context: Context, origin: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "website-icons/$key.png")
    }
    private fun bytes(url: String, limit: Int): ByteArray? = runCatching {
        WebsiteBookmarks.normalize(url)
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.byteStream()?.use stream@ { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) return@stream null
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
        }
    }.getOrNull()
    suspend fun load(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) { slots.withPermit {
        try {
        val uri = URI(WebsiteBookmarks.normalize(url))
        val origin = "${uri.scheme}://${uri.rawAuthority}"
        val file = cache(context, origin)
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < TimeUnit.DAYS.toMillis(7))
            return@withPermit BitmapFactory.decodeFile(file.path)
        val urls = mutableListOf<String>()
        val html = bytes("$origin/", 256 * 1024)?.toString(Charsets.UTF_8).orEmpty()
        Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { link ->
            val attributes = Regex("([\\w-]+)\\s*=\\s*[\"']([^\"']*)[\"']").findAll(link.value)
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            if (attributes["rel"].orEmpty().lowercase().split(' ').any { it == "icon" || it == "apple-touch-icon" }) {
                attributes["href"]?.let { href -> runCatching { URI("$origin/").resolve(href).toString() }.getOrNull()?.let(urls::add) }
            }
        }
        urls += "$origin/favicon.ico"
        for (iconUrl in urls.distinct().take(5)) {
            val data = bytes(iconUrl, 512 * 1024) ?: continue
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) continue
            val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth,bounds.outHeight) / 96).coerceAtLeast(1) }
            val decoded = BitmapFactory.decodeByteArray(data,0,data.size,options) ?: continue
            val bitmap = Bitmap.createScaledBitmap(decoded,64,64,true)
            file.parentFile?.mkdirs()
            runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
            return@withPermit bitmap
        }
        // Cache failures briefly so ordinary page changes do not repeatedly fetch an absent icon.
        file.parentFile?.mkdirs(); file.writeBytes(byteArrayOf())
        file.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7) + TimeUnit.HOURS.toMillis(1))
        null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    } }
}
