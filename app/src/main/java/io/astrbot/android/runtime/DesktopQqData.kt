package io.astrbot.android.runtime

import java.io.File
import java.nio.file.Files
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.requireLocalized

/** Maps archive names to the independent Linux QQ home, which is outside /persistent. */
internal object DesktopQqData {
    val relativeScopes = listOf(".config/QQ", ".local/share/QQ")
    const val PREFIX = "desktop/qq-home/"
    fun home(files: File) = File(files, "manager/extensions/desktop/qq-home")
    fun roots(home: File): Map<String, File> = relativeScopes.associate { "$PREFIX$it" to File(home, it) }
    fun checkParents(root: File, file: File) {
        require(file.toPath().normalize().startsWith(root.toPath().normalize()))
        var current: File? = file
        while (current != null && current != root) {
            requireLocalized(!Files.isSymbolicLink(current.toPath())) { AppText.get(R.string.ui_the_configuration_parent_directory_is_a_link_current_data) }
            current = current.parentFile
        }
        require(!Files.isSymbolicLink(root.toPath()))
    }
    fun transient(name: String) = name.startsWith("Singleton")
}
