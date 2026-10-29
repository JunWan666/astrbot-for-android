package io.astrbot.android.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Fixed operations only. Neither the Binder interface nor the guest accepts a shell command. */
internal object DeviceCommands {
    fun readOutput(input: java.io.InputStream): ByteArray = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val count = it.read(buffer); if (count < 0) break
            check(output.size() + count <= 8 * 1024 * 1024) { "Device output exceeds size limit" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    fun discard(input: java.io.InputStream) = input.use { val buffer = ByteArray(4096); while (it.read(buffer) >= 0) { /* drain */ } }
    val keys = mapOf("back" to 4, "home" to 3, "recents" to 187, "enter" to 66, "tab" to 61,
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "delete" to 67)

    fun run(operation: String, args: JSONObject, execute: (Array<String>) -> ByteArray): JSONObject {
        fun number(name: String, range: IntRange): String {
            val value = args.getInt(name)
            require(value in range) { "Invalid $name" }
            return value.toString()
        }
        val command = when (operation) {
            "tree" -> arrayOf("/system/bin/uiautomator", "dump", "--compressed", "/dev/tty")
            "screenshot" -> arrayOf("/system/bin/screencap", "-p")
            "tap" -> arrayOf("/system/bin/input", "tap", number("x", 0..20000), number("y", 0..20000))
            "swipe" -> arrayOf("/system/bin/input", "swipe", number("x1", 0..20000), number("y1", 0..20000),
                number("x2", 0..20000), number("y2", 0..20000), number("duration", 50..2000))
            "key" -> arrayOf("/system/bin/input", "keyevent", requireNotNull(keys[args.getString("key")]) { "Unsupported key" }.toString())
            "input" -> {
                val text = args.getString("text")
                require(text.length in 1..2000 && text.all { it.code in 32..126 } && !text.contains("%s")) {
                    "Enable the optional accessibility service for Unicode or literal %s input"
                }
                arrayOf("/system/bin/input", "text", text.replace(" ", "%s"))
            }
            else -> error("Unsupported operation")
        }
        val bytes = execute(command)
        return when (operation) {
            "screenshot" -> {
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Screenshot unavailable" }
                try { image(bitmap) } finally { bitmap.recycle() }
            }
            "tree" -> {
                val output = bytes.toString(Charsets.UTF_8)
                val start = output.indexOf("<?xml")
                val end = output.indexOf("</hierarchy>")
                check(start >= 0 && end > start) { "Screen tree unavailable" }
                val xml = output.substring(start, end + "</hierarchy>".length)
                check(xml.length <= 160000) { "Screen tree exceeds size limit" }
                JSONObject().put("format", "xml").put("tree", xml)
            }
            else -> JSONObject().put("performed", true)
        }
    }

    fun image(bitmap: Bitmap): JSONObject {
        val ratio = minOf(1f, 1080f / maxOf(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()), true)
        try {
            var quality = 75
            val buffer = ByteArrayOutputStream()
            do {
                buffer.reset(); scaled.compress(Bitmap.CompressFormat.JPEG, quality, buffer); quality -= 15
            } while (buffer.size() > 320000 && quality >= 30)
            check(buffer.size() <= 380000) { "Screenshot exceeds size limit" }
            return JSONObject().put("format", "jpeg").put("width", scaled.width).put("height", scaled.height)
                .put("screenWidth", bitmap.width).put("screenHeight", bitmap.height)
                .put("data", Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP))
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }
}
