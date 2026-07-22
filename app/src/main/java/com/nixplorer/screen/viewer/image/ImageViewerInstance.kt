package com.nixplorer.screen.viewer.image

import android.net.Uri
import com.nixplorer.screen.viewer.ViewerInstance

class ImageViewerInstance(
    override val uri: Uri,
    override val id: String
) : ViewerInstance {
    override fun onClose() {

    }
}