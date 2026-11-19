package io.astrbot.android.runtime

/** Supported upstream SemVer tags, including explicitly selected preview releases. */
internal object ComponentVersion {
    private val pattern = Regex("([0-9]{1,5})\\.([0-9]{1,5})\\.([0-9]{1,5})(?:-((?:alpha|beta|rc)(?:\\.[0-9]{1,5})?))?")
    fun supported(value: String) = pattern.matches(value)
    fun preview(value: String) = pattern.matchEntire(value)?.groupValues?.get(4)?.isNotEmpty() == true
    fun compare(a: String, b: String): Int {
        val left = requireNotNull(pattern.matchEntire(a)).groupValues
        val right = requireNotNull(pattern.matchEntire(b)).groupValues
        for (i in 1..3) left[i].toInt().compareTo(right[i].toInt()).takeIf { it != 0 }?.let { return it }
        val x = left[4]; val y = right[4]
        if (x == y) return 0
        if (x.isEmpty()) return 1
        if (y.isEmpty()) return -1
        val xp = x.split('.'); val yp = y.split('.')
        val rank = listOf("alpha", "beta", "rc")
        rank.indexOf(xp[0]).compareTo(rank.indexOf(yp[0])).takeIf { it != 0 }?.let { return it }
        xp.size.compareTo(yp.size).takeIf { it != 0 }?.let { return it }
        return if (xp.size == 1) 0 else xp[1].toInt().compareTo(yp[1].toInt())
    }
}
