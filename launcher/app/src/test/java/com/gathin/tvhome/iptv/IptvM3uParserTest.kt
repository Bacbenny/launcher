package com.gathin.tvhome.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IptvM3uParserTest {
    private val source = IptvSource(
        id = "source-1",
        name = "Test playlist",
        type = IptvSourceType.M3U_URL,
    )

    @Test
    fun parsesQuotedMetadataAndVlcHeaders() {
        val playlist = """
            ﻿#EXTM3U
            #EXTINF:-1 tvg-id="world.news" tvg-logo="https://img.test/news.png" group-title="World, News",News 24 HD
            #EXTVLCOPT:http-user-agent=TV Remote/1.0
            #EXTVLCOPT:http-referrer=https://provider.test/
            https://stream.test/live.m3u8|Cookie=session%3Dabc
        """.trimIndent()

        val channel = IptvM3uParser.parse(playlist, source).single()

        assertEquals("News 24 HD", channel.name)
        assertEquals("https://img.test/news.png", channel.logoUrl)
        assertEquals("World, News", channel.group)
        assertEquals("world.news", channel.epgId)
        assertEquals("TV Remote/1.0", channel.headers["User-Agent"])
        assertEquals("https://provider.test/", channel.headers["Referer"])
        assertEquals("session=abc", channel.headers["Cookie"])
    }

    @Test
    fun extgrpOverridesMetadataForItsChannel() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="General",Channel A
            #EXTGRP:Regional
            https://stream.test/a.ts
        """.trimIndent()

        assertEquals("Regional", IptvM3uParser.parse(playlist, source).single().group)
    }

    @Test
    fun rejectsPlaylistWithoutPlayableEntries() {
        assertThrows(IllegalArgumentException::class.java) {
            IptvM3uParser.parse("#EXTM3U\n#EXTINF:-1,No URL", source)
        }
    }
}
