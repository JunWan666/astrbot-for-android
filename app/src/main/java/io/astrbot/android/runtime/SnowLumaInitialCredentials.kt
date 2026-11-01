package io.astrbot.android.runtime

/** Plaintext from this managed process only; never persisted or included in diagnostics. */
class SnowLumaInitialCredentials(val username: String, val password: String) {
    override fun toString(): String = "SnowLumaInitialCredentials(<redacted>)"
}

/** Waits for complete lines so a split read cannot capture a truncated password. */
internal class SnowLumaCredentialParser {
    private val pending = StringBuilder()
    private var oversized = false

    fun append(chunk: String): SnowLumaInitialCredentials? {
        var found: SnowLumaInitialCredentials? = null
        for (character in chunk) {
            if (character == '\n') {
                if (!oversized) parse(pending.toString())?.let { found = it }
                pending.clear(); oversized = false
            } else if (!oversized) {
                if (pending.length < 4096) pending.append(character)
                else { pending.clear(); oversized = true }
            }
        }
        return found
    }

    private fun parse(line: String): SnowLumaInitialCredentials? {
        val plain = ANSI.replace(line, "").trimEnd('\r')
        val match = CREDENTIALS.matchEntire(plain) ?: return null
        return SnowLumaInitialCredentials(match.groupValues[1], match.groupValues[2])
    }

    private companion object {
        val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        val CREDENTIALS = Regex(".*\\[WebUI]\\s+initial credentials: user=([A-Za-z0-9._@-]{1,128}) password=([^\\s]{1,256})\\s*")
    }
}
