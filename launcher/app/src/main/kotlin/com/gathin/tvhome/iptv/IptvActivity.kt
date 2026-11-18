package com.gathin.tvhome.iptv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class IptvActivity : ComponentActivity() {
    private var activePlayer: IptvMedia3PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF8AE6D5),
                    onPrimary = androidx.compose.ui.graphics.Color(0xFF06201B),
                    background = androidx.compose.ui.graphics.Color(0xFF090D14),
                    onBackground = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
                    surface = androidx.compose.ui.graphics.Color(0xFF151C27),
                    onSurface = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
                    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF222C39),
                    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB9C4D2),
                ),
            ) {
                IptvScreen(
                    repository = IptvRepository(applicationContext),
                    onExit = ::finish,
                    onPlayerChanged = { activePlayer = it },
                )
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    activePlayer?.togglePause()
                    return activePlayer != null
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    activePlayer?.resume()
                    return activePlayer != null
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    activePlayer?.pause()
                    return activePlayer != null
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        activePlayer?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        activePlayer?.release()
        activePlayer = null
        super.onDestroy()
    }
}
