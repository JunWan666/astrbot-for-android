package io.astrbot.android.core

import java.nio.file.Path

object ArchivePaths {
    private fun validBackslashes(root: Path, value: String, allowUnixEscapes: Boolean): Boolean =
        !value.contains('\\') || (allowUnixEscapes && root.fileSystem.separator == "/" &&
            !value.replace(Regex("\\\\x[0-9a-fA-F]{2}"), "").contains('\\'))

    fun entry(root: Path, name: String, allowUnixEscapes: Boolean = false): Path {
        require(!name.startsWith('/') && validBackslashes(root, name, allowUnixEscapes) && !name.contains('\u0000')) { "无效的归档路径" }
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(name).normalize()
        require(target.startsWith(base)) { "归档路径越界：$name" }
        return target
    }

    /** Guest absolute links are rewritten to relative links confined to this rootfs. */
    fun link(root: Path, entry: Path, target: String, allowUnixEscapes: Boolean = false): Path {
        require(target.isNotEmpty() && validBackslashes(root, target, allowUnixEscapes) && !target.contains('\u0000'))
        val base = root.toAbsolutePath().normalize()
        val resolved = if (target.startsWith('/')) base.resolve(target.trimStart('/')).normalize()
                       else entry.parent.resolve(target).normalize()
        require(resolved.startsWith(base)) { "链接路径越界：$target" }
        val relative = entry.parent.relativize(resolved)
        // POSIX permits links to "."; Java represents that relative path as an empty string.
        return if (relative.toString().isEmpty()) base.fileSystem.getPath(".") else relative
    }
}
