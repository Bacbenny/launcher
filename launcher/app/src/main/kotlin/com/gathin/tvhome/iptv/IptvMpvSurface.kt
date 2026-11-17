package com.gathin.tvhome.iptv

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import `is`.xyz.mpv.MPV
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android libmpv surface lifecycle adapted from Nuvio Enhanced's MpvSurfaceView.android.kt.
 * The iOS MPVKit package is not used on Android; this is the Android libmpv wrapper used by Nuvio.
 */
internal class IptvMpvSurface(
    context: Context,
    private val onError: (String) -> Unit = {},
) : SurfaceView(context), SurfaceHolder.Callback {
    private val appContext = context.applicationContext
    private val playerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TvHome-libmpv").apply { isDaemon = true }
    }
    private val released = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var mpv: MPV? = null
    @Volatile private var currentSurface: Surface? = null
    @Volatile private var hasSurface = false
    @Volatile private var currentChannel: IptvChannel? = null
    private var surfaceAttached = false

    init {
        setBackgroundColor(Color.BLACK)
        holder.addCallback(this)
        playerExecutor.execute(::initializePlayer)
    }

    private fun initializePlayer() {
        if (released.get()) return
        val configDir = File(appContext.filesDir, "iptv/mpv").apply { mkdirs() }
        val cacheDir = File(appContext.cacheDir, "iptv/mpv").apply { mkdirs() }
        var createdPlayer: MPV? = null
        try {
            val player = MPV()
            createdPlayer = player
            player.create(appContext)
            player.setOptionString("config", "yes")
            player.setOptionString("config-dir", configDir.absolutePath)
            player.setOptionString("gpu-shader-cache-dir", cacheDir.absolutePath)
            player.setOptionString("icc-cache-dir", cacheDir.absolutePath)
            // auto-safe selects MediaCodec hardware decoding only for codecs the device supports.
            player.setOptionString("hwdec", "auto-safe")
            player.setOptionString("vo", "gpu")
            player.setOptionString("profile", "fast")
            player.setOptionString("cache", "yes")
            player.setOptionString("demuxer-readahead-secs", "10")
            player.setOptionString("audio-display", "no")
            player.init()
            // Set post-init options after libmpv has created its core.
            player.setOptionString("force-window", "no")
            // Avoid writing provider URLs (which can contain credentials) to device logs.
            player.setOptionString("msg-level", "all=no")
            mpv = player
            if (released.get()) {
                player.destroy()
                mpv = null
            }
        } catch (_: Exception) {
            runCatching { createdPlayer?.takeIf { it.isInitialized }?.destroy() }
            reportError()
        } catch (_: LinkageError) {
            runCatching { createdPlayer?.takeIf { it.isInitialized }?.destroy() }
            reportError()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released.get()) return
        currentSurface = holder.surface
        hasSurface = true
        playerExecutor.execute {
            val player = mpv ?: return@execute
            val surface = currentSurface ?: return@execute
            if (released.get() || !surface.isValid) return@execute
            try {
                player.attachSurface(surface)
                surfaceAttached = true
                player.setOptionString("force-window", "yes")
                player.setPropertyString("vo", "gpu")
                currentChannel?.let { load(player, it) }
            } catch (_: Exception) {
                reportError()
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (released.get()) return
        playerExecutor.execute {
            mpv?.setPropertyString("android-surface-size", "${width}x$height")
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        hasSurface = false
        currentSurface = null
        if (released.get()) return
        playerExecutor.execute(::detachSurface)
    }

    fun play(channel: IptvChannel) {
        if (released.get()) return
        val previous = currentChannel
        if (previous?.id == channel.id &&
            previous.streamUrl == channel.streamUrl &&
            previous.headers == channel.headers
        ) {
            return
        }
        currentChannel = channel
        playerExecutor.execute {
            if (!released.get() && hasSurface) mpv?.let { load(it, channel) }
        }
    }

    fun togglePause() {
        if (released.get()) return
        playerExecutor.execute {
            val player = mpv ?: return@execute
            val paused = player.getPropertyBoolean("pause") ?: false
            player.setPropertyBoolean("pause", !paused)
        }
    }

    fun resume() {
        if (released.get()) return
        playerExecutor.execute { mpv?.setPropertyBoolean("pause", false) }
    }

    fun pause() {
        if (released.get()) return
        playerExecutor.execute { mpv?.setPropertyBoolean("pause", true) }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        holder.removeCallback(this)
        runCatching {
            playerExecutor.execute {
                try {
                    detachSurface()
                    mpv?.command("stop")
                    mpv?.destroy()
                    mpv = null
                } finally {
                    playerExecutor.shutdown()
                }
            }
        }.onFailure {
            playerExecutor.shutdown()
        }
    }

    private fun load(player: MPV, channel: IptvChannel) {
        if (!hasSurface || released.get()) return
        try {
            val headers = channel.headers
            player.setOptionString("user-agent", headers["User-Agent"] ?: "TvHome IPTV/1.0")
            player.setOptionString("referrer", headers["Referer"] ?: "")
            val additionalHeaders = headers.filterKeys {
                !it.equals("User-Agent", ignoreCase = true) && !it.equals("Referer", ignoreCase = true)
            }
            player.setOptionString(
                "http-header-fields",
                additionalHeaders.entries.joinToString(",") { "${it.key}: ${it.value}" },
            )
            player.command("loadfile", channel.streamUrl, "replace")
            player.setPropertyBoolean("pause", false)
        } catch (_: Exception) {
            reportError()
        }
    }

    private fun detachSurface() {
        if (!surfaceAttached) return
        surfaceAttached = false
        val player = mpv ?: return
        runCatching {
            player.setPropertyString("vo", "null")
            player.setOptionString("force-window", "no")
            player.detachSurface()
        }
    }

    private fun reportError() {
        mainHandler.post {
            onError("Playback could not start. Check the stream URL, provider access, or device codec support.")
        }
    }
}
