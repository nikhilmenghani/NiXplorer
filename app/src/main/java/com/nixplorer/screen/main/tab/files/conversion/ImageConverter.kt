package com.nixplorer.screen.main.tab.files.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import com.nixplorer.screen.main.tab.files.task.SafeFileOperations
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ImageConverter {
    fun convert(source: File, target: File, format: ConversionFormat, quality: Int, cancelled: () -> Boolean) {
        require(format in setOf(ConversionFormat.JPEG, ConversionFormat.PNG, ConversionFormat.WEBP)) { "Unsupported image output" }
        SafeFileOperations.checkCancelled(cancelled)
        var decoded: Bitmap? = null
        var flattened: Bitmap? = null
        try {
            val image = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                    checkDimensions(info.size.width, info.size.height)
                    SafeFileOperations.checkCancelled(cancelled)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }
            } else {
                decodeLegacy(source, cancelled)
            }
            decoded = image
            SafeFileOperations.checkCancelled(cancelled)
            val bitmap = if (format == ConversionFormat.JPEG && image.hasAlpha()) {
                Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { opaque ->
                    flattened = opaque
                    Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(image, 0f, 0f, null) }
                }
            } else image
            val compression = when (format) {
                ConversionFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ConversionFormat.PNG -> Bitmap.CompressFormat.PNG
                else -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
            FileOutputStream(target).use { output ->
                if (!bitmap.compress(compression, quality.coerceIn(1, 100), output)) throw IOException("Image encoder could not write this file")
                output.fd.sync()
            }
            SafeFileOperations.checkCancelled(cancelled)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            require(target.length() > 0 && bounds.outWidth == bitmap.width && bounds.outHeight == bitmap.height) {
                "Converted image could not be verified"
            }
        } catch (error: OutOfMemoryError) {
            throw IOException("This image needs more memory than is available on this device", error)
        } finally {
            flattened?.recycle()
            decoded?.recycle()
        }
    }

    /** Keep original dimensions; fail clearly instead of silently reducing resolution. */
    private fun checkDimensions(width: Int, height: Int) {
        val budget = minOf(128L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 3)
        require(width > 0 && height > 0 && width.toLong() * height * 8 <= budget) {
            "Image is too large to convert at full resolution with available memory"
        }
    }

    private fun decodeLegacy(source: File, cancelled: () -> Boolean): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        checkDimensions(bounds.outWidth, bounds.outHeight)
        SafeFileOperations.checkCancelled(cancelled)
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }) ?: throw IOException("This image format could not be decoded on this device")
        val orientation = runCatching {
            ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }
        if (transform.isIdentity) return decoded
        try {
            val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
            if (oriented !== decoded) decoded.recycle()
            return oriented
        } catch (error: Throwable) {
            decoded.recycle()
            throw error
        }
    }
}
