package com.gathin.tvhome.iptv

/**
 * IPTV data types adapted for the launcher's local-first Android TV flow.
 * This feature is distributed under GPL-3.0-only; see COPYING and THIRD_PARTY_NOTICES.md.
 */
internal enum class IptvSourceType {
    M3U_URL,
    M3U_FILE,
    XTREAM,
}

internal data class IptvSource(
    val id: String,
    val name: String,
    val type: IptvSourceType,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val localContent: String = "",
    val enabled: Boolean = true,
    val lastSyncedAt: Long = 0L,
    val cachedChannels: List<IptvChannel> = emptyList(),
)

internal data class IptvChannel(
    val id: String,
    val sourceId: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String = "",
    val group: String = "",
    val epgId: String = "",
    val headers: Map<String, String> = emptyMap(),
    val drm: IptvDrmConfig? = null,
)

internal data class IptvDrmConfig(
    val scheme: String,
    val licenseUrl: String = "",
    val licenseKey: String = "",
)

internal data class IptvSyncResult(
    val updatedSources: List<IptvSource>,
    val errors: List<String>,
) {
    val channelCount: Int
        get() = updatedSources.filter(IptvSource::enabled).sumOf { it.cachedChannels.size }
}
