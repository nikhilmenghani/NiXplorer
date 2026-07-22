package com.nixplorer.screen.viewer.image

import android.net.Uri
import androidx.activity.compose.setContent
import com.nixplorer.common.ui.SafeSurface
import com.nixplorer.screen.viewer.ViewerActivity
import com.nixplorer.screen.viewer.ViewerInstance
import com.nixplorer.screen.viewer.image.ui.ImageViewerScreen
import com.nixplorer.theme.FileExplorerTheme

class ImageViewerActivity : ViewerActivity() {
    override fun onCreateNewInstance(uri: Uri, uid: String): ViewerInstance {
        return ImageViewerInstance(uri, uid)
    }

    override fun onReady(instance: ViewerInstance) {
        setContent {
            FileExplorerTheme {
                SafeSurface(enableStatusBarsPadding = false) {
                    ImageViewerScreen(instance)
                }
            }
        }
    }
}