package io.astrbot.android.runtime

import io.astrbot.android.requireLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.util.Base64
import io.astrbot.android.BuildConfig
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECParameterSpec
import java.security.spec.ECGenParameterSpec
import java.security.AlgorithmParameters
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/** Trust comes from the APK build, never from a downloaded catalog or update package. */
internal class UpdateTrust(context: Context) {
    val key: ECPublicKey = run {
        val bytes = if (BuildConfig.UPDATE_PUBLIC_KEY.isNotEmpty()) Base64.decode(BuildConfig.UPDATE_PUBLIC_KEY, Base64.NO_WRAP)
        else if (BuildConfig.DEBUG) runCatching { context.assets.open("updates/trusted-key.der").use { it.readBytes() } }.getOrNull()
        else null
        requireLocalized(bytes != null && bytes.size in 1..4096) { AppText.get(R.string.ui_this_apk_has_no_trusted_update_key_configured_update) }
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        require(publicKey.params.curve == expected.curve && publicKey.params.generator == expected.generator && publicKey.params.order == expected.order)
        publicKey
    }
    val identity: String = sha256(key.encoded)
    fun verify(bytes: ByteArray, signature: ByteArray) {
        requireLocalized(signature.size in 1..256 && Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(bytes); verify(signature)
        }) { AppText.get(R.string.ui_invalid_update_signature_current_version_is_preserved) }
    }
    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
