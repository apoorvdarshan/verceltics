package com.apoorvdarshan.verceltics.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The four persistent workspaces in the app's primary navigation.
 *
 * Search deliberately is not a destination: it acts on the current workspace and is exposed as
 * a separate action by [AppNavigationDock], matching the native iOS navigation hierarchy.
 */
enum class AppNavigationDestination(
    val id: String,
    val label: String,
    val compactLabel: String,
    val icon: ImageVector,
) {
    HOSTING("hosting", "Hosting", "Hosting", Icons.Rounded.Storage),
    REGISTRARS("registrars", "Registrars", "Registrars", Icons.Rounded.Language),
    SITES("sites", "Sites", "Sites", Icons.Rounded.QueryStats),
    ABOUT("about", "About", "About", Icons.Rounded.Info),
}

/**
 * Native Compose counterpart to the restored iOS system Liquid Glass navigation hierarchy.
 *
 * Android uses translucent Material surfaces, native elevation, and restrained selection tint
 * without pretending to expose Apple's Liquid Glass API. Haptic
 * feedback intentionally belongs to [onDestinationSelected] and [onSearch], so the app shell can
 * coordinate feedback with the navigation state change.
 */
@Composable
fun AppNavigationDock(
    selectedDestination: AppNavigationDestination,
    onDestinationSelected: (AppNavigationDestination) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    showsAboutBadge: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // This pointer target owns the complete dock rectangle, including safe-area padding.
            // It only consumes events that none of the controls claimed, preventing click-through.
            .consumeUnclaimedPointerInput()
            .navigationBarsPadding()
            .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp)
            .testTag("mainNavigation.dock")
            .semantics { isTraversalGroup = true },
    ) {
        val labelLayout = navigationLabelLayout(
            availableWidthDp = maxWidth.value,
            fontScale = fontScale,
        )
        val dockArrangement = navigationDockArrangement(maxWidth.value)
        val dockHeight = if (labelLayout == NavigationLabelLayout.ICON_ONLY) {
            64.dp
        } else {
            (62f + (fontScale - 1f).coerceAtLeast(0f) * 28f).dp
        }
        val searchWidth = 62.dp

        if (dockArrangement == NavigationDockArrangement.STACKED_SEARCH) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryNavigationDock(
                    selectedDestination = selectedDestination,
                    onDestinationSelected = onDestinationSelected,
                    showsAboutBadge = showsAboutBadge,
                    labelLayout = NavigationLabelLayout.ICON_ONLY,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                )
                SearchDockButton(
                    onClick = onSearch,
                    labelLayout = NavigationLabelLayout.COMPACT,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(accessibleLabeledDockHeight(fontScale)),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PrimaryNavigationDock(
                    selectedDestination = selectedDestination,
                    onDestinationSelected = onDestinationSelected,
                    showsAboutBadge = showsAboutBadge,
                    labelLayout = labelLayout,
                    modifier = Modifier
                        .weight(1f)
                        .height(dockHeight),
                )

                SearchDockButton(
                    onClick = onSearch,
                    labelLayout = labelLayout,
                    modifier = Modifier
                        .width(searchWidth)
                        .height(dockHeight),
                )
            }
        }
    }
}

internal enum class NavigationLabelLayout {
    FULL,
    COMPACT,
    ICON_ONLY,
}

internal enum class NavigationDockArrangement {
    INLINE,
    STACKED_SEARCH,
}

/** Pure layout policy so narrow-screen and accessibility behavior can be regression tested. */
internal fun navigationLabelLayout(
    availableWidthDp: Float,
    fontScale: Float,
): NavigationLabelLayout = when {
    availableWidthDp < 304f || fontScale >= 1.3f -> NavigationLabelLayout.ICON_ONLY
    // maxWidth is measured after the dock's 12dp side insets. A standard 390dp phone therefore
    // reports about 366dp here and must retain the user-facing labels rather than abbreviations.
    availableWidthDp < 360f || fontScale >= 1.15f -> NavigationLabelLayout.COMPACT
    else -> NavigationLabelLayout.FULL
}

/**
 * Inline navigation needs 286dp after the dock's outer padding to keep all four primary targets
 * at least 48dp wide alongside Search. Below that, Search moves to its own row.
 */
internal fun navigationDockArrangement(availableWidthDp: Float): NavigationDockArrangement =
    if (availableWidthDp < 286f) {
        NavigationDockArrangement.STACKED_SEARCH
    } else {
        NavigationDockArrangement.INLINE
    }

/** Estimated primary hit-target width in the stacked fallback, kept pure for unit coverage. */
internal fun stackedPrimaryTargetWidthDp(availableWidthDp: Float): Float =
    (availableWidthDp - 3f - (2f * 5f) - (3f * 4f)) / AppNavigationDestination.entries.size

private fun accessibleLabeledDockHeight(fontScale: Float): Dp =
    (62f + (fontScale - 1f).coerceAtLeast(0f) * 28f).dp

@Composable
private fun PrimaryNavigationDock(
    selectedDestination: AppNavigationDestination,
    onDestinationSelected: (AppNavigationDestination) -> Unit,
    showsAboutBadge: Boolean,
    labelLayout: NavigationLabelLayout,
    modifier: Modifier = Modifier,
) {
    GlassDockSurface(
        modifier = modifier,
        shape = DockShape,
        tintStrength = 0.035f,
        testTag = "mainNavigation.primary",
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppNavigationDestination.entries.forEach { destination ->
                NavigationDestinationButton(
                    destination = destination,
                    isSelected = selectedDestination == destination,
                    showsBadge = showsAboutBadge &&
                        destination == AppNavigationDestination.ABOUT,
                    labelLayout = labelLayout,
                    onClick = { onDestinationSelected(destination) },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minWidth = MinimumTouchTarget),
                )
            }
        }
    }
}

@Composable
private fun NavigationDestinationButton(
    destination: AppNavigationDestination,
    isSelected: Boolean,
    showsBadge: Boolean,
    labelLayout: NavigationLabelLayout,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.82f),
        label = "${destination.id} dock press",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) colors.surfaceVariant.copy(alpha = 0.88f) else Color.Transparent,
        label = "${destination.id} dock container",
    )
    val contentColor by animateColorAsState(
        targetValue = colors.onSurface,
        label = "${destination.id} dock content",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .scale(scale),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .testTag("mainNavigation.${destination.id}")
                .semantics {
                    contentDescription = destinationContentDescription(destination, showsBadge)
                    role = Role.Tab
                    selected = isSelected
                },
            shape = SelectedShape,
            color = containerColor,
            contentColor = contentColor,
            border = if (isSelected) BorderStroke(1.dp, colors.outline) else null,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            interactionSource = interactionSource,
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 2.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                    )
                    if (labelLayout != NavigationLabelLayout.ICON_ONLY) {
                        Text(
                            text = if (labelLayout == NavigationLabelLayout.COMPACT) {
                                destination.compactLabel
                            } else {
                                destination.label
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (showsBadge) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 3.dp, end = 5.dp)
                            .size(9.dp)
                            .background(colors.error, CircleShape)
                            .border(1.dp, colors.outline, CircleShape)
                            .testTag("mainNavigation.${destination.id}.badge"),
                    )
                }

            }
        }
    }
}

@Composable
private fun SearchDockButton(
    onClick: () -> Unit,
    labelLayout: NavigationLabelLayout,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.82f),
        label = "search dock press",
    )

    GlassDockSurface(
        modifier = modifier.scale(scale),
        shape = CircleShape,
        tintStrength = 0.055f,
        testTag = "mainNavigation.searchSurface",
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .testTag("mainNavigation.search")
                .semantics {
                    contentDescription = "Search current workspace"
                    role = Role.Button
                },
            color = Color.Transparent,
            contentColor = colors.onSurface,
            shape = CircleShape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            interactionSource = interactionSource,
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 3.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(23.dp),
                    )
                    if (labelLayout != NavigationLabelLayout.ICON_ONLY) {
                        Text(
                            text = "Search",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassDockSurface(
    shape: Shape,
    tintStrength: Float,
    testTag: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val glassAlpha = if (isDark) 0.86f else 0.92f

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag(testTag),
            color = colors.surface.copy(alpha = glassAlpha),
            contentColor = colors.onSurface,
            shape = shape,
            border = BorderStroke(1.dp, colors.outline),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.primary.copy(alpha = tintStrength)),
            ) {
                content()
            }
        }
    }
}

/** Consume only pointer gestures that no child control claimed. */
private fun Modifier.consumeUnclaimedPointerInput(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = true,
            pass = PointerEventPass.Final,
        )
        down.consume()

        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
            event.changes.forEach { change ->
                if (!change.isConsumed) change.consume()
            }
        } while (event.changes.any { it.pressed })
    }
}

private val DockShape = RoundedCornerShape(24.dp)
private val SelectedShape = RoundedCornerShape(19.dp)
private val MinimumTouchTarget: Dp = 48.dp

private fun destinationContentDescription(
    destination: AppNavigationDestination,
    showsBadge: Boolean,
): String = if (showsBadge) "${destination.label}, update available" else destination.label

/** Material window-size breakpoint: medium and expanded windows get a navigation rail. */
internal const val NavigationRailMinWidthDp: Float = 600f

/** Pure layout policy: a rail replaces the bottom dock once the window is at least 600dp wide. */
internal fun usesNavigationRail(windowWidthDp: Float): Boolean = windowWidthDp >= NavigationRailMinWidthDp

/**
 * App shell layout that keeps the four persistent destinations and the contextual Search action in
 * a bottom [AppNavigationDock] on compact windows and an [AppNavigationRail] on windows at least
 * 600dp wide, matching the adaptive iOS sidebar/tab bar.
 *
 * [content] is placed in a box that already avoids the system bars, the optional [topBar], and the
 * navigation chrome, so callers never double-apply window insets. With [showsNavigation] false
 * (for example while the first connection is being made) no navigation chrome is shown at all.
 */
@Composable
fun AdaptiveNavigationScaffold(
    selectedDestination: AppNavigationDestination,
    onDestinationSelected: (AppNavigationDestination) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    showsNavigation: Boolean = true,
    showsAboutBadge: Boolean = false,
    topBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val showsRail = showsNavigation && usesNavigationRail(maxWidth.value)
        val showsDock = showsNavigation && !showsRail
        // Screens own keyboard insets themselves, so the shell only avoids system bars and cutouts.
        // The dock already pads for the navigation bar; without it the content must.
        val contentInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            .let { insets ->
                if (showsDock) insets else insets.union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            }
        Row(Modifier.fillMaxSize()) {
            if (showsRail) {
                AppNavigationRail(
                    selectedDestination = selectedDestination,
                    onDestinationSelected = onDestinationSelected,
                    onSearch = onSearch,
                    showsAboutBadge = showsAboutBadge,
                )
            }
            Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = topBar,
                bottomBar = {
                    if (showsDock) {
                        AppNavigationDock(
                            selectedDestination = selectedDestination,
                            onDestinationSelected = onDestinationSelected,
                            onSearch = onSearch,
                            showsAboutBadge = showsAboutBadge,
                        )
                    }
                },
            ) { contentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .consumeWindowInsets(contentPadding)
                        // The rail already pads for start-side system bars and display cutouts.
                        .then(
                            if (showsRail) {
                                Modifier.consumeWindowInsets(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Start),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .windowInsetsPadding(contentInsets)
                        .testTag(if (showsRail) "mainNavigation.content.rail" else "mainNavigation.content"),
                    content = content,
                )
            }
        }
    }
}

/** Navigation rail for medium and expanded windows; destinations stay tabs and Search a button. */
@Composable
fun AppNavigationRail(
    selectedDestination: AppNavigationDestination,
    onDestinationSelected: (AppNavigationDestination) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    showsAboutBadge: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    NavigationRail(
        modifier = modifier
            .fillMaxHeight()
            .testTag("mainNavigation.rail")
            .semantics { isTraversalGroup = true },
        containerColor = colors.surface,
        contentColor = colors.onSurface,
        header = {
            RailSearchButton(
                onClick = onSearch,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
        },
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
    ) {
        AppNavigationDestination.entries.forEach { destination ->
            val showsBadge = showsAboutBadge && destination == AppNavigationDestination.ABOUT
            NavigationRailItem(
                selected = selectedDestination == destination,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    if (showsBadge) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    modifier = Modifier.testTag("mainNavigation.${destination.id}.badge"),
                                    containerColor = colors.error,
                                )
                            },
                        ) {
                            Icon(destination.icon, contentDescription = null)
                        }
                    } else {
                        Icon(destination.icon, contentDescription = null)
                    }
                },
                label = {
                    Text(
                        text = destination.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .testTag("mainNavigation.${destination.id}")
                    .semantics {
                        contentDescription = destinationContentDescription(destination, showsBadge)
                    },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = colors.onSurface,
                    selectedTextColor = colors.onSurface,
                    indicatorColor = colors.surfaceVariant,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun RailSearchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(56.dp)
            .testTag("mainNavigation.search")
            .semantics {
                contentDescription = "Search current workspace"
                role = Role.Button
            },
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceVariant,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
