package com.apoorvdarshan.verceltics.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.domain.IntegrationProvider

private val PanelShape = RoundedCornerShape(16.dp)

@Composable
@Suppress("UNUSED_PARAMETER")
fun OffsetPanel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    shadowColor: Color = MaterialTheme.colorScheme.outline,
    shadowOffset: Dp? = null,
    shape: Shape = PanelShape,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val surfaceModifier = modifier
        .then(if (testTag == null) Modifier else Modifier.testTag(testTag))

    if (onClick == null) {
        Surface(
            modifier = surfaceModifier,
            shape = shape,
            color = color,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, borderColor),
            content = { Box(Modifier.fillMaxWidth(), content = content) },
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = surfaceModifier,
            shape = shape,
            color = color,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, borderColor),
            interactionSource = remember { MutableInteractionSource() },
            content = { Box(Modifier.fillMaxWidth(), content = content) },
        )
    }
}

@Composable
fun ControlSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    testTag: String = "catalog.search",
    focusRequester: FocusRequester? = null,
    onSearch: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val textInteractionSource = remember { MutableInteractionSource() }
    LaunchedEffect(textInteractionSource) {
        textInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            }
        }
    }
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .testTag("$testTag.container"),
        color = colors.surface,
        shape = PanelShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, colors.outline),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .testTag(testTag)
                .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
                .semantics { contentDescription = placeholder },
            interactionSource = textInteractionSource,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = colors.onSurface,
                fontWeight = FontWeight.Normal,
            ),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onSearch()
            }),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        innerTextField()
                    }
                    if (value.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                onValueChange("")
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("$testTag.clear"),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.Clear,
                                    contentDescription = "Clear search",
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
fun ProviderMark(
    provider: IntegrationProvider,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
) {
    val accent = Color(provider.accentColor)
    val foreground = contrastingContentColor(accent)
    Surface(
        modifier = modifier.size(size),
        color = accent,
        contentColor = foreground,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** Provider tile backed by the canonical cross-platform provider artwork. */
@Composable
fun ProviderMark(
    provider: IntegrationProvider,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
) {
    val accent = Color(provider.accentColor)
    Surface(
        modifier = modifier.size(size),
        color = accent.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.20f)),
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(size * 0.22f),
            contentAlignment = Alignment.Center,
        ) {
            ProviderLogo(
                provider = provider,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * A compact native Material control that mirrors the restored iOS glass hierarchy.
 * Android owns the blur-like translucency, elevation, shape, and touch feedback.
 */
@Composable
fun ThemedGlassControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        shape = shape,
        color = colors.surface.copy(alpha = 0.92f),
        contentColor = colors.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        border = BorderStroke(
            1.dp,
            colors.outline.copy(alpha = colors.outline.alpha * if (enabled) 1f else 0.34f),
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        interactionSource = interactionSource,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.primary.copy(alpha = if (enabled) 0.055f else 0.02f)),
        ) {
            content()
        }
    }
}

/** Selects the higher-contrast WCAG foreground for an arbitrary provider accent. */
fun contrastingContentColor(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White

@Composable
fun SectionHeading(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = eyebrow.uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                ),
            )
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.20f)),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(color, RoundedCornerShape(50)),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun LabelChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
fun PressableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.clickable(
            role = role,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
