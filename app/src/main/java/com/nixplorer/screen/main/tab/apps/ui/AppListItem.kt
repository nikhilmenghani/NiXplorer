package com.nixplorer.screen.main.tab.apps.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nixplorer.R
import com.nixplorer.screen.main.tab.apps.provider.AppUsageSnapshot
import com.nixplorer.common.limitLength
import com.nixplorer.common.toFormattedSize
import com.nixplorer.common.ui.Space
import com.nixplorer.screen.main.tab.apps.holder.AppHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppListItem(
    app: AppHolder,
    usage: AppUsageSnapshot?,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {

                    }
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                model = app.path,
                filterQuality = FilterQuality.Low,
                error = painterResource(id = R.drawable.apk_file_placeholder),
                contentScale = ContentScale.Fit,
                contentDescription = null
            )

            Space(size = 12.dp)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val lastOpened = usage?.lastOpened?.get(app.packageName)
                Text(
                    text = when {
                        usage == null -> stringResource(R.string.apps_usage_loading)
                        !usage.available -> stringResource(R.string.apps_usage_unavailable)
                        lastOpened == null -> stringResource(R.string.apps_no_usage_recorded)
                        else -> stringResource(R.string.apps_last_opened,
                            android.text.format.DateUtils.getRelativeTimeSpanString(lastOpened,
                                System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = app.versionName.limitLength(12),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    VerticalDivider(Modifier
                        .height(12.dp)
                        .padding(horizontal = 8.dp))

                    Text(
                        text = app.size.toFormattedSize(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (app.isSystemApp) {
                        VerticalDivider(Modifier
                            .height(12.dp)
                            .padding(horizontal = 8.dp))

                        Text(
                            text = stringResource(R.string.system),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(start = 72.dp))
    }
}