package com.nixplorer.common

import android.text.format.DateUtils
import com.nixplorer.App.Companion.globalClass

fun Long.toModifiedTime(): String = if (globalClass.preferencesManager.relativeModifiedTime && this > 0) {
    DateUtils.getRelativeTimeSpanString(this, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
} else toFormattedDate(hideSeconds = true)
