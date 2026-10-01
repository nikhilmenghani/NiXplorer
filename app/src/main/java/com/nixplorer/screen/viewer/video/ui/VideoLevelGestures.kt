package com.nixplorer.screen.viewer.video.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun videoLevelGestures(
    enabled: () -> Boolean,
    onFeedback: (Pair<Boolean, Float>) -> Unit
): Modifier {
    val context = LocalContext.current
    val window = context.activity()?.window
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentFeedback by rememberUpdatedState(onFeedback)
    DisposableEffect(window) {
        val originalBrightness = window?.attributes?.screenBrightness
        onDispose {
            if (originalBrightness != null) window.attributes = window.attributes.apply {
                screenBrightness = originalBrightness
            }
        }
    }
    return Modifier.pointerInput(audio, window) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val brightness = down.position.x <= size.width * .25f
            val eligible = brightness || down.position.x >= size.width * .75f
            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val startLevel = if (brightness) {
                window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                    ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
            } else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
            var adjusting = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1 || !currentEnabled()) break
                val deltaY = down.position.y - change.position.y
                if (eligible && change.pressed && (adjusting || abs(deltaY) > 24.dp.toPx())) {
                    adjusting = true
                    val level = (startLevel + deltaY / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    if (brightness) {
                        window?.attributes = window.attributes.apply { screenBrightness = level.coerceAtLeast(.01f) }
                    } else {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * maxVolume).toInt(), 0)
                    }
                    currentFeedback(brightness to level)
                }
                if (adjusting) change.consume()
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
internal fun BoxScope.VideoLevelFeedback(feedback: Pair<Boolean, Float>) {
    val (brightness, level) = feedback
    Surface(
        modifier = Modifier.align(if (brightness) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .9f)
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 12.dp).width(88.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(if (brightness) Icons.Default.Brightness6 else Icons.AutoMirrored.Filled.VolumeUp, null)
            Text(androidx.compose.ui.res.stringResource(if (brightness) com.nixplorer.R.string.brightness else com.nixplorer.R.string.volume), style = MaterialTheme.typography.labelLarge)
            Box(Modifier.height(120.dp).width(14.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(level).align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.primary))
            }
            Text("${(level * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        }
    }
}
