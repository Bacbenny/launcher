package com.gathin.tvhome.iptv

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * M3U metadata parsing based on Nuvio Enhanced's Live TV playlist handling.
 * Upstream attribution and license details are in THIRD_PARTY_NOTICES.md.
 */
internal object IptvM3uParser {
    private const val MAX_CHANNELS = 20_000
    private val supportedSchemes = setOf("http", "https", "rtsp", "rtmp", "udp", "rtp", "srt", "mms", "mmsh")
    private val attributePattern =
        Regex("""([A-Za-z0-9_-]+)\s*=\s*("(?:\\.|[^"])*"|'[^']*'|[^\s,]+)""")

    fun parse(text: String, source: IptvSource): List<IptvChannel> {
        require(text.length <= IptvRepository.MAX_PLAYLIST_CHARS) {
            "Playlist file is too large."
        }

        val channels = ArrayList<IptvChannel>()
        var pendingInfo: Info? = null
        val pendingHeaders = linkedMapOf<String, String>()
        var pendingGroup: String? = null

        text.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = parseInfo(line)
                    pendingHeaders.clear()
                    pendingInfo?.headers?.let(pendingHeaders::putAll)
                    pendingGroup = null
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    parseVlcOption(line.substringAfter(':'))?.let { (key, value) ->
                        pendingHeaders[key] = value
                    }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim()
                }

                line.startsWith("#") -> Unit

                pendingInfo != null -> {
                    val (streamUrl, inlineHeaders) = parseStreamUrl(line)
                    if (streamUrl.isNotBlank() && isSupportedStreamUrl(streamUrl)) {
                        pendingHeaders.putAll(inlineHeaders)
                        val info = pendingInfo!!
                        val stablePart = info.epgId.ifBlank {
                            "${streamUrl.hashCode().toUInt().toString(16)}-${channels.size}"
                        }
                        channels += IptvChannel(
                            id = "${source.id}:$stablePart",
                            sourceId = source.id,
                            name = info.name.ifBlank { "Channel ${channels.size + 1}" },
                            streamUrl = streamUrl,
                            logoUrl = info.logoUrl,
                            group = pendingGroup?.takeIf(String::isNotBlank) ?: info.group,
                            epgId = info.epgId,
                            headers = pendingHeaders.toMap(),
                        )
                        require(channels.size <= MAX_CHANNELS) {
                            "Playlist has more than $MAX_CHANNELS channels."
                        }
                    }
                    pendingInfo = null
                    pendingHeaders.clear()
                    pendingGroup = null
                }
            }
        }

        require(channels.isNotEmpty()) { "No playable channels were found in this M3U playlist." }
        return channels
    }

    private fun parseInfo(line: String): Info {
        val colon = line.indexOf(':')
        if (colon < 0) return Info()
        val comma = findInfoComma(line, colon + 1)
        val attributeText = if (comma >= 0) line.substring(colon + 1, comma) else line.substring(colon + 1)
        val attributes = attributePattern.findAll(attributeText).associate { match ->
            val value = match.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
                .replace("\\\"", "\"")
            match.groupValues[1].lowercase() to value
        }
        val name = if (comma >= 0) line.substring(comma + 1).trim() else ""
        return Info(
            name = name.ifBlank { attributes["tvg-name"].orEmpty() },
            logoUrl = attributes["tvg-logo"].orEmpty(),
            group = attributes["group-title"].orEmpty(),
            epgId = attributes["tvg-id"].orEmpty(),
        )
    }

    private fun findInfoComma(line: String, start: Int): Int {
        var inQuotes = false
        for (index in start until line.length) {
            when (line[index]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return index
            }
        }
        return -1
    }

    private fun parseVlcOption(value: String): Pair<String, String>? {
        val separator = value.indexOf('=')
        if (separator <= 0) return null
        val key = value.substring(0, separator).trim().lowercase()
        val headerName = when (key) {
            "http-user-agent", "user-agent" -> "User-Agent"
            "http-referrer", "http-referer", "referrer", "referer" -> "Referer"
            "http-cookie", "cookie" -> "Cookie"
            else -> return null
        }
        return headerName to value.substring(separator + 1).trim()
    }

    private fun parseStreamUrl(line: String): Pair<String, Map<String, String>> {
        val separator = line.indexOf('|')
        if (separator < 0) return line.trim() to emptyMap()
        val streamUrl = line.substring(0, separator).trim()
        val headers = linkedMapOf<String, String>()
        line.substring(separator + 1).split('&').forEach { component ->
            val equals = component.indexOf('=')
            if (equals <= 0) return@forEach
            val key = decode(component.substring(0, equals)).trim().lowercase()
            val value = decode(component.substring(equals + 1)).trim()
            val canonical = when (key) {
                "user-agent", "http-user-agent" -> "User-Agent"
                "referer", "referrer", "http-referrer" -> "Referer"
                "cookie", "http-cookie" -> "Cookie"
                else -> null
            }
            if (canonical != null && value.isNotEmpty()) headers[canonical] = value
        }
        return streamUrl to headers
    }

    private fun isSupportedStreamUrl(value: String): Boolean =
        value.substringBefore(':', "").lowercase() in supportedSchemes

    private fun decode(value: String): String =
        runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(value)

    private data class Info(
        val name: String = "",
        val logoUrl: String = "",
        val group: String = "",
        val epgId: String = "",
        val headers: Map<String, String> = emptyMap(),
    )
}
