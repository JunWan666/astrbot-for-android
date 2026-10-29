package io.astrbot.android.debug

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object ShizukuInstaller {
    suspend fun prepare(context: Context): File = withContext(Dispatchers.IO) {
        val record = context.assets.open("optional/shizuku.json").bufferedReader().use { JSONObject(it.readText()) }
        val output = File(context.cacheDir,"optional-installers/shizuku.apk")
        output.parentFile?.mkdirs()
        context.assets.open("optional/shizuku.apk").use { input -> output.outputStream().use { input.copyTo(it) } }
        check(output.length() == record.getLong("bytes"))
        val digest = MessageDigest.getInstance("SHA-256")
        output.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            val read = input.read(buffer); if (read < 0) break; digest.update(buffer,0,read)
        } }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        check(hex(digest.digest()) == record.getString("sha256"))
        val info = context.packageManager.getPackageArchiveInfo(output.path,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        check(info?.packageName == record.getString("packageName"))
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        check(signatures?.size == 1 && hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray())) == record.getString("certificateSha256"))
        output
    }
}
