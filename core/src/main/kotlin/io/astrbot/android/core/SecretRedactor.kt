package io.astrbot.android.core

object SecretRedactor {
    // Remove terminal presentation before matching labels or credential values.
    // Match actual ESC/C1 introducers only; ordinary bracketed text is preserved.
    private val terminalOsc = Regex("(?:\u001B\\]|\u009D)[^\u0007\u001B\u009C]*(?:\u0007|\u001B\\\\|\u009C)")
    private val terminalCsi = Regex("(?:\u001B\\[|\u009B)[0-?]*[ -/]*[@-~]")
    private const val initialValue = """(?:"[^"\r\n]*"|'[^'\r\n]*'|\S+)"""
    private val labeled = Regex(
        "(?i)([\\\"']?(?:password|passwd|token|access_token|api_key|apikey|secret|authorization)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)"
    )
    private val bearer = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val initial = Regex("(?i)(WebUi Token:\\s*)$initialValue")
    private val initialPassword = Regex(
        """(?i)(\binitial[ \t]+(?:credentials[ \t]*:[^\r\n]*?\bpassword|password)[ \t]*[:=][ \t]*)($initialValue)"""
    )
    private val authHeader = Regex("(?im)^(\\s*Authorization\\s*:\\s*)[^\\r\\n]+")
    private val known = Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b")
    private val qqLoginUrl = Regex("https?://txz\\.qq\\.com/p\\?[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
    private val qrTerminalLine = Regex("^[ \\t\\u2580-\\u259F]{6,}\\r?$", RegexOption.MULTILINE)

    private fun stripTerminalFormatting(text: String): String = terminalCsi.replace(terminalOsc.replace(text, ""), "")

    fun initialLoginPassword(line: String): String? {
        val value = initialPassword.find(stripTerminalFormatting(line))?.groupValues?.get(2) ?: return null
        val password = when {
            value.length >= 2 && value.first() == value.last() && value.first() in "\"'" -> value.drop(1).dropLast(1)
            value.first() in "\"'" -> return null
            else -> value
        }
        return password.takeIf {
            it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl) && it !in setOf("[已隐藏]", "[redacted]")
        }
    }

    fun redact(text: String): String {
        var result = stripTerminalFormatting(text)
        result = initialPassword.replace(result) { it.groupValues[1] + "[已隐藏]" }
        result = qrTerminalLine.replace(result, "[登录二维码已隐藏]")
        result = qqLoginUrl.replace(result, "[登录二维码链接已隐藏]")
        result = initial.replace(result) { it.groupValues[1] + "[已隐藏]" }
        result = authHeader.replace(result) { it.groupValues[1] + "[已隐藏]" }
        result = bearer.replace(result) { it.groupValues[1] + "[已隐藏]" }
        result = labeled.replace(result) { it.groupValues[1] + "[已隐藏]" }
        return known.replace(result, "[已隐藏]")
    }
}
