package com.nixplorer.screen.viewer.audio

import android.net.Uri
import androidx.activity.compose.setContent
import com.nixplorer.screen.viewer.ViewerActivity
import com.nixplorer.screen.viewer.ViewerInstance
import com.nixplorer.screen.viewer.audio.ui.MusicPlayerScreen
import com.nixplorer.theme.FileExplorerTheme

class AudioPlayerActivity : ViewerActivity() {
    override fun onCreateNewInstance(
        uri: Uri,
        uid: String
    ): ViewerInstance {
        return AudioPlayerInstance(uri, uid)
    }

    override fun onReady(instance: ViewerInstance) {
        setContent {
            FileExplorerTheme {
                MusicPlayerScreen(
                    audioPlayerInstance = instance as AudioPlayerInstance,
                    onClosed = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}