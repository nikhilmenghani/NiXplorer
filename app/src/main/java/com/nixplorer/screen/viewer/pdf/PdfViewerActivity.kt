package com.nixplorer.screen.viewer.pdf

import android.net.Uri
import androidx.activity.compose.setContent
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.ui.SafeSurface
import com.nixplorer.screen.viewer.ViewerActivity
import com.nixplorer.screen.viewer.ViewerInstance
import com.nixplorer.screen.viewer.pdf.ui.PdfViewerContent
import com.nixplorer.theme.FileExplorerTheme
import net.engawapg.lib.zoomable.ExperimentalZoomableApi

class PdfViewerActivity : ViewerActivity() {
    override fun onCreateNewInstance(uri: Uri, uid: String): ViewerInstance {
        return PdfViewerInstance(uri, uid)
    }

    @OptIn(ExperimentalZoomableApi::class)
    override fun onReady(instance: ViewerInstance) {
        if (instance is PdfViewerInstance) {
            setContent {
                FileExplorerTheme {
                    SafeSurface(false) {
                        PdfViewerContent(
                            instance = instance,
                            onBackPress = { onBackPressedDispatcher.onBackPressed() }
                        )
                    }
                }
            }
        } else {
            globalClass.showMsg(getString(R.string.invalid_pdf))
            finish()
        }
    }
}