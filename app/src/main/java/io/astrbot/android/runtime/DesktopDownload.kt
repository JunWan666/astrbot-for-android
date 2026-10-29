package io.astrbot.android.runtime

import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A partial archive is reusable only for the exact pinned source and expected digest. */
internal object DesktopDownload {
    suspend fun download(client: OkHttpClient, url: String, archive: File, size: Long) = coroutineScope {
        var lastTime = System.nanoTime()
        var lastBytes = archive.length()
        DesktopInstallStore.progress(lastBytes,size,0)
        while (archive.length() < size) {
            DesktopInstallStore.checkpoint()
            var offset = archive.length()
            val request = Request.Builder().url(url).apply { if (offset > 0) header("Range","bytes=$offset-") }.build()
            val call = client.newCall(request)
            DesktopInstallStore.attach(call)
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (response.code == 200 && offset > 0) {
                        archive.outputStream().close(); offset = 0; lastBytes = 0; lastTime = System.nanoTime()
                    }
                    checkLocalized(response.code == 200 && offset == 0L || response.code == 206) { AppText.get(R.string.desktop_download_failed) }
                    if (response.code == 206) {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                        checkLocalized(range != null && range.groupValues[1].toLong() == offset && range.groupValues[3].toLong() == size) {
                            AppText.get(R.string.desktop_integrity_failed)
                        }
                    }
                    requireNotNull(response.body).byteStream().use { input ->
                        java.io.FileOutputStream(archive,true).use { output ->
                            val buffer = ByteArray(65536)
                            var count = offset
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (DesktopInstallStore.interrupted()) break
                                val read = input.read(buffer)
                                if (read < 0) break
                                checkLocalized(count + read <= size) { AppText.get(R.string.desktop_integrity_failed) }
                                output.write(buffer,0,read); count += read
                                val now = System.nanoTime()
                                if (now - lastTime >= TimeUnit.MILLISECONDS.toNanos(250) || count == size) {
                                    val speed = ((count - lastBytes).coerceAtLeast(0) * 1_000_000_000.0 / (now-lastTime).coerceAtLeast(1)).toLong()
                                    DesktopInstallStore.progress(count,size,speed); lastTime = now; lastBytes = count
                                }
                            }
                        }
                    }
                }
            } catch (failure: IOException) {
                if (!DesktopInstallStore.interrupted()) throw failure
            } finally { DesktopInstallStore.attach(null); cancellation.cancelAndJoin() }
            if (!DesktopInstallStore.interrupted() && archive.length() < size) throw IOException("Incomplete archive")
        }
        DesktopInstallStore.checkpoint()
        DesktopInstallStore.progress(archive.length(),size,0)
    }
}
