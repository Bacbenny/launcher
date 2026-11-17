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
    fun parsesClearKeyHexLicenseAndPreservesHttpHeaders() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,Protected channel
            #KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
            #KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100
            #EXTVLCOPT:http-referrer=https://provider.test/
            https://stream.test/protected.mpd|Cookie=session%3Dabc
        """.trimIndent()

        val channel = IptvM3uParser.parse(playlist, source).single()

        assertEquals("clearkey", channel.drm?.scheme)
        assertEquals(
            "00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100",
            channel.drm?.licenseKey,
        )
        assertEquals("https://provider.test/", channel.headers["Referer"])
        assertEquals("session=abc", channel.headers["Cookie"])
    }

    @Test
    fun parsesClearKeyJsonLicense() {
        val jsonKey = """{"keys":[{"kty":"oct","kid":"AQID","k":"BAUG"}],"type":"temporary"}"""
        val playlist = """
            #EXTM3U
            #EXTINF:-1,Protected channel
            #KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
            #KODIPROP:inputstream.adaptive.license_key=$jsonKey
            https://stream.test/protected.m3u8
        """.trimIndent()

        val channel = IptvM3uParser.parse(playlist, source).single()

        assertEquals("clearkey", channel.drm?.scheme)
        assertEquals(jsonKey, channel.drm?.licenseKey)
    }

    @Test
    fun parsesWidevineLicenseUrlAndLicenseHeaders() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,Widevine channel
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://drm.test/license|User-Agent=IPTV%20TV&Cookie=token%3Dabc
            https://stream.test/protected.mpd
        """.trimIndent()

        val channel = IptvM3uParser.parse(playlist, source).single()

        assertEquals("widevine", channel.drm?.scheme)
        assertEquals("https://drm.test/license", channel.drm?.licenseUrl)
        assertEquals("IPTV TV", channel.headers["User-Agent"])
        assertEquals("token=abc", channel.headers["Cookie"])
    }

    @Test
    fun rejectsPlaylistWithoutPlayableEntries() {
        assertThrows(IllegalArgumentException::class.java) {
            IptvM3uParser.parse("#EXTM3U\n#EXTINF:-1,No URL", source)
        }
    }
}
