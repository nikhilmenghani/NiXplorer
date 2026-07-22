package com.nixplorer.screen.viewer

import android.net.Uri

interface ViewerInstance {
    val uri: Uri
    val id: String

    fun onClose()
}