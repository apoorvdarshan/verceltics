package com.apoorvdarshan.verceltics.ui.vercel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.components.shimmer

/** Loads favicons for project tiles. Absent (tests, previews) means letter tiles only. */
fun interface VercelFaviconSource {
    suspend fun load(domain: String): ImageBitmap?

    /** An already-loaded favicon, read synchronously so recycled rows don't flash a placeholder. */
    fun cached(domain: String): ImageBitmap? = null
}

val LocalVercelFaviconSource = staticCompositionLocalOf<VercelFaviconSource?> { null }

private enum class IconPhase { LOADING, LOADED, FALLBACK }

/**
 * The project's own favicon, a shimmering globe while it loads, or a colored letter tile when
 * the project has no public domain or the site offers no usable raster icon (iOS `ProjectIcon`).
 */
@Composable
fun VercelProjectIcon(
    domain: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val source = LocalVercelFaviconSource.current
    val cached = remember(domain, source) { domain?.let { source?.cached(it) } }
    var image by remember(domain, source) { mutableStateOf(cached) }
    var phase by remember(domain, source) {
        mutableStateOf(
            when {
                cached != null -> IconPhase.LOADED
                domain == null || source == null -> IconPhase.FALLBACK
                else -> IconPhase.LOADING
            },
        )
    }
    LaunchedEffect(domain, source) {
        if (domain == null || source == null) {
            phase = IconPhase.FALLBACK
            return@LaunchedEffect
        }
        if (cached != null) return@LaunchedEffect
        val loaded = source.load(domain)
        image = loaded
        phase = if (loaded == null) IconPhase.FALLBACK else IconPhase.LOADED
    }
    val shape = RoundedCornerShape(size / 4)
    val density = LocalDensity.current
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        val loadedImage = image
        when {
            phase == IconPhase.LOADED && loadedImage != null -> Image(
                bitmap = loadedImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(size)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("vercel.projectIcon.favicon"),
            )

            phase == IconPhase.LOADING -> Box(
                modifier = Modifier
                    .size(size)
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                    .shimmer(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(size * 0.4f),
                )
            }

            else -> Box(
                modifier = Modifier
                    .size(size)
                    .background(Color(VERCEL_LETTER_TILE_COLORS[vercelLetterTileColorIndex(name)]), shape)
                    .testTag("vercel.projectIcon.letter"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    vercelProjectInitial(name),
                    modifier = Modifier.clearAndSetSemantics { },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    // Fixed to the tile, so large font scales cannot overflow it.
                    fontSize = with(density) { (size * 0.45f).toSp() },
                )
            }
        }
    }
}
