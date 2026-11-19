package com.gathin.tvhome.iptv

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvUnreadableBackupTest {
    @Test
    fun preservesTheOriginalBytesBeforeAReplacementCanBeSaved() {
        val directory = Files.createTempDirectory("iptv-backup").toFile()
        try {
            val savedState = File(directory, "iptv-state.enc")
            val encryptedData = byteArrayOf(1, 4, 9, 16, 25)
            savedState.writeBytes(encryptedData)

            val backup = preserveUnreadableIptvFile(savedState, timestamp = 1234)

            assertNotNull(backup)
            assertFalse(savedState.exists())
            assertTrue(backup!!.name.startsWith("iptv-state.enc$IPTV_UNREADABLE_BACKUP_MARKER"))
            assertArrayEquals(encryptedData, backup.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun neverOverwritesAnExistingUnreadableBackup() {
        val directory = Files.createTempDirectory("iptv-backup-collision").toFile()
        try {
            val savedState = File(directory, "iptv-state.enc")
            savedState.writeText("first")
            val firstBackup = preserveUnreadableIptvFile(savedState, timestamp = 1234)!!

            savedState.writeText("second")
            val secondBackup = preserveUnreadableIptvFile(savedState, timestamp = 1234)!!

            assertTrue(firstBackup.exists())
            assertTrue(secondBackup.exists())
            assertFalse(firstBackup == secondBackup)
            assertEquals("first", firstBackup.readText())
            assertEquals("second", secondBackup.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun doesNothingWhenThereIsNoSavedFileToPreserve() {
        val directory = Files.createTempDirectory("iptv-backup-empty").toFile()
        try {
            assertNull(preserveUnreadableIptvFile(File(directory, "missing.enc"), timestamp = 1234))
        } finally {
            directory.deleteRecursively()
        }
    }
}
