package io.astrbot.android.runtime

/** Portable scopes contain user data only, never installed programs or Android permissions. */
data class BackupSelection(val astrbot: Boolean = true, val qq: Boolean = false) {
    val scopes: List<String> get() = buildList {
        if (astrbot) add("astrbot")
        if (qq) addAll(CURRENT_QQ_SCOPES)
    }
    // Bit 2 belonged to a retired component; keep bit 4 stable for saved selections.
    val mask get() = (if (astrbot) 1 else 0) or (if (qq) 4 else 0)
    companion object {
        val QQ_SCOPES = listOf("home/.config/QQ", "napcat/data", "desktop/qq-home/.config/QQ", "desktop/qq-home/.local/share/QQ")
        val CURRENT_QQ_SCOPES = listOf("desktop/qq-home/.config/QQ", "desktop/qq-home/.local/share/QQ")
        // Recognize historical data scopes when importing an old backup. No programs are restored.
        val ALL_SCOPES = listOf("astrbot", "napcat/config") + QQ_SCOPES
        fun fromMask(mask: Int): BackupSelection {
            require(mask in 1..7 && mask and 5 != 0)
            return BackupSelection(astrbot = mask and 1 != 0, qq = mask and 4 != 0)
        }
    }
}
