package com.gathin.tvhome.iptv

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

@OptIn(UnstableApi::class)
internal class IptvMedia3PlayerView(
    context: Context,
    private val onError: (String) -> Unit = {},
) : PlayerView(context) {
    private val appContext = context.applicationContext
    private var released = false
    private var currentChannel: IptvChannel? = null
    private val exoPlayer: ExoPlayer

    init {
        setBackgroundColor(Color.BLACK)
        useController = false
        isFocusable = false
        isFocusableInTouchMode = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        exoPlayer = ExoPlayer.Builder(appContext).build()
        player = exoPlayer
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                reportError()
            }
        })
    }

    fun play(channel: IptvChannel) {
        if (released || channel == currentChannel) return
        currentChannel = channel
        try {
            val headers = channel.headers
            val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                ?: "TvHome IPTV/1.0"
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)
            val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
            val drmConfiguration = buildDrmConfiguration(channel.drm, headers)
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(channel.streamUrl)
                .setMediaId(channel.id)
                .setMimeType(mimeTypeFor(channel.streamUrl))
            drmConfiguration?.let(mediaItemBuilder::setDrmConfiguration)

            val sourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            channel.drm?.let { drm ->
                val provider = drmProviderFor(drm, httpFactory)
                if (provider != null) sourceFactory.setDrmSessionManagerProvider(provider)
            }
            exoPlayer.setMediaSource(sourceFactory.createMediaSource(mediaItemBuilder.build()))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (_: Exception) {
            reportError()
        }
    }

    fun togglePause() {
        if (released) return
        exoPlayer.playWhenReady = !exoPlayer.playWhenReady
    }

    fun resume() {
        if (!released) exoPlayer.playWhenReady = true
    }

    fun pause() {
        if (!released) exoPlayer.pause()
    }

    fun release() {
        if (released) return
        released = true
        player = null
        exoPlayer.release()
    }

    private fun buildDrmConfiguration(
        drm: IptvDrmConfig?,
        headers: Map<String, String>,
    ): MediaItem.DrmConfiguration? {
        if (drm == null) return null
        val uuid = when (drm.scheme.lowercase(Locale.ROOT)) {
            "clearkey", "org.w3.clearkey", "clear-key" -> C.CLEARKEY_UUID
            "widevine", "com.widevine.alpha" -> C.WIDEVINE_UUID
            else -> throw IllegalArgumentException("This playlist uses an unsupported DRM system.")
        }
        if (uuid == C.WIDEVINE_UUID && drm.licenseUrl.isBlank()) {
            throw IllegalArgumentException("The Widevine playlist entry has no license server URL.")
        }
        return MediaItem.DrmConfiguration.Builder(uuid)
            .apply {
                if (drm.licenseUrl.isNotBlank()) setLicenseUri(drm.licenseUrl)
                setLicenseRequestHeaders(headers)
            }
            .build()
    }

    private fun drmProviderFor(
        drm: IptvDrmConfig,
        httpFactory: DefaultHttpDataSource.Factory,
    ): DrmSessionManagerProvider? {
        val isClearKey = drm.scheme.equals("clearkey", true) ||
            drm.scheme.equals("org.w3.clearkey", true) ||
            drm.scheme.equals("clear-key", true)
        if (!isClearKey || drm.licenseUrl.isNotBlank()) {
            return DefaultDrmSessionManagerProvider().apply {
                setDrmHttpDataSourceFactory(httpFactory)
            }
        }
        val response = clearKeyResponse(drm.licenseKey)
            ?: throw IllegalArgumentException("The ClearKey value must be JSON or a hexadecimal key-id:key pair.")
        val manager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(LocalMediaDrmCallback(response))
        return DrmSessionManagerProvider { manager }
    }

    private fun clearKeyResponse(value: String): ByteArray? {
        val candidate = value.trim()
        if (candidate.startsWith("{")) {
            return try {
                val root = JSONObject(candidate)
                val keys = root.optJSONArray("keys") ?: return null
                if (keys.length() == 0) return null
                val normalizedKeys = JSONArray()
                for (index in 0 until keys.length()) {
                    val entry = keys.optJSONObject(index) ?: return null
                    val keyId = normalizeClearKeyValue(entry.optString("kid")) ?: return null
                    val key = normalizeClearKeyValue(entry.optString("k")) ?: return null
                    normalizedKeys.put(
                        JSONObject()
                            .put("kty", entry.optString("kty", "oct"))
                            .put("kid", keyId)
                            .put("k", key),
                    )
                }
                JSONObject()
                    .put("keys", normalizedKeys)
                    .put("type", root.optString("type", "temporary"))
                    .toString()
                    .toByteArray(StandardCharsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
        val pair = candidate.split(':', limit = 2)
        if (pair.size != 2) return null
        val keyId = decodeKey(pair[0]) ?: return null
        val key = decodeKey(pair[1]) ?: return null
        if (keyId.size != 16 || key.size != 16) return null
        val keyEntry = JSONObject()
            .put("kty", "oct")
            .put("kid", Base64.encodeToString(keyId, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            .put("k", Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        return JSONObject()
            .put("keys", JSONArray().put(keyEntry))
            .put("type", "temporary")
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun normalizeClearKeyValue(value: String): String? {
        val decoded = decodeKey(value) ?: return null
        if (decoded.size != 16) return null
        return Base64.encodeToString(decoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun decodeKey(value: String): ByteArray? {
        val clean = value.trim().removePrefix("0x").removePrefix("0X")
        if (clean.isNotEmpty() && clean.length % 2 == 0 && clean.all { it.digitToIntOrNull(16) != null }) {
            return runCatching {
                ByteArray(clean.length / 2) { index ->
                    clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            }.getOrNull()
        }
        return runCatching {
            Base64.decode(clean, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun mimeTypeFor(url: String): String? {
        val path = Uri.parse(url).lastPathSegment.orEmpty().lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ts") || path.endsWith(".mpegts") -> MimeTypes.VIDEO_MP2T
            else -> null
        }
    }

    private fun reportError() {
        onError("Playback could not start. Check the stream URL, provider access, DRM data, or device codec support.")
    }
}
