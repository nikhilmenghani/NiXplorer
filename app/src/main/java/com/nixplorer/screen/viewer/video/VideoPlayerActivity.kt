package com.nixplorer.screen.viewer.video

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nixplorer.screen.viewer.ViewerActivity
import com.nixplorer.screen.viewer.ViewerInstance
import com.nixplorer.screen.viewer.video.ui.VideoPlayerScreen
import com.nixplorer.theme.FileExplorerTheme

class VideoPlayerActivity : ViewerActivity() {
    private var playerReady = false

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && playerReady) hideSystemBars()
    }

    override fun onCreateNewInstance(
        uri: Uri,
        uid: String
    ): ViewerInstance {
        return VideoPlayerInstance(uri, uid)
    }

    override fun onReady(instance: ViewerInstance) {
        playerReady = true
        hideSystemBars()
        val videoPlayerInstance = instance as VideoPlayerInstance
        setContent {
            FileExplorerTheme {
                VideoPlayerScreen(
                    videoUri = videoPlayerInstance.uri,
                    videoPlayerInstance = videoPlayerInstance,
                    onBackPressed = { finish() }
                )
            }
        }
    }

}
