package dev.kosha.core.designsystem.component

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Draws an image from a local `content://` or `file://` URI.
 *
 * Deliberately hand-rolled rather than pulling in an image-loading library:
 * every mainstream one is built around fetching over the network, and Kosha
 * has no INTERNET permission and a CI check that keeps it that way. A loader
 * whose whole purpose is HTTP has no business in this dependency graph when
 * the only images that exist are receipts sitting in app-private storage.
 *
 * Decoding is downsampled to roughly the requested size. A full-resolution
 * phone photo is ~12 megapixels; decoding fifty of those into a scrolling list
 * at thumbnail size is how a ledger runs out of memory.
 */
@Composable
fun KoshaLocalImage(
    uri: String,
    contentDescription: String?,
    targetSize: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val targetPx = with(density) { targetSize.roundToPx() }.coerceAtLeast(1)

    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, uri, targetPx) {
        val key = "$uri@$targetPx"
        cache[key]?.let {
            value = it
            return@produceState
        }
        val decoded = withContext(Dispatchers.IO) {
            runCatching { decodeDownsampled(context, Uri.parse(uri), targetPx) }.getOrNull()
        }
        if (decoded != null) cache.put(key, decoded)
        value = decoded
    }

    Box(modifier) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Thumbnails already decoded, keyed by URI and requested size.
 *
 * Without it every scroll back up re-reads and re-decodes the same files from
 * disk. Deliberately small and LRU: these are thumbnails, so a few dozen is
 * ample, and an unbounded cache of bitmaps is just a slower memory leak.
 */
private val cache = object : android.util.LruCache<String, ImageBitmap>(CACHE_ENTRIES) {}

private const val CACHE_ENTRIES = 48

private fun decodeDownsampled(
    context: android.content.Context,
    uri: Uri,
    targetPx: Int,
): ImageBitmap? {
    val resolver = context.contentResolver
    // Pass one reads the dimensions only — no pixels are allocated.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null
    return decoded.asImageBitmap()
}
