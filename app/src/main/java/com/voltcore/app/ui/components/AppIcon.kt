package com.voltcore.app.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Launcher icon for an installed package.
 *
 * Decoding an adaptive icon is not free and the freezer list can hold several hundred
 * rows, so results are cached and loaded off the main thread. While a load is in flight
 * the row shows the app's initial rather than a blank box, which keeps the list from
 * flickering as it scrolls.
 */
@Composable
fun AppIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = IconCache.peek(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { IconCache.load(context, packageName) }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 26)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        } else {
            Box(
                Modifier
                    .size(size)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private object IconCache {
    private const val ICON_PX = 128

    // Roughly 64 KB per ARGB_8888 icon at this size; 150 entries is about 10 MB.
    private val cache = LruCache<String, ImageBitmap>(150)

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    fun load(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = drawable.toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            cache.put(packageName, bitmap)
            bitmap
        }.getOrNull()
    }
}
