package com.gathin.tvhome.iptv

import java.io.File

internal const val IPTV_UNREADABLE_BACKUP_MARKER = ".unreadable-"

internal fun preserveUnreadableIptvFile(file: File, timestamp: Long = System.currentTimeMillis()): File? {
    if (!file.isFile) return null
    val parent = file.parentFile ?: return null
    for (suffix in 0..100) {
        val extra = if (suffix == 0) "" else "-$suffix"
        val backup = File(parent, "${file.name}$IPTV_UNREADABLE_BACKUP_MARKER$timestamp$extra")
        if (backup.exists()) continue
        if (file.renameTo(backup)) return backup
        if (file.exists()) return null
    }
    return null
}
