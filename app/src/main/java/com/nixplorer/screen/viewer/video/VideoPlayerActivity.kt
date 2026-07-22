package com.nixplorer.screen.viewer.video

import android.net.Uri
import androidx.activity.compose.setContent
import com.nixplorer.screen.viewer.ViewerActivity
import com.nixplorer.screen.viewer.ViewerInstance
import com.nixplorer.screen.viewer.video.ui.VideoPlayerScreen
import com.nixplorer.theme.FileExplorerTheme

class VideoPlayerActivity : ViewerActivity() {
    override fun onCreateNewInstance(
        uri: Uri,
        uid: String
    ): ViewerInstance {
        return VideoPlayerInstance(uri, uid)
    }

    override fun onReady(instance: ViewerInstance) {
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