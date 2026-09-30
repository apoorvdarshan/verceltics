package com.apoorvdarshan.verceltics.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

enum class ThemedActionTone {
    PRIMARY,
    NEUTRAL,
    DESTRUCTIVE,
}

@Composable
fun ThemedActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isBusy: Boolean = false,
    tone: ThemedActionTone = ThemedActionTone.PRIMARY,
    testTag: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = when (tone) {
        ThemedActionTone.PRIMARY -> colors.primary
        ThemedActionTone.NEUTRAL -> colors.surfaceVariant
        ThemedActionTone.DESTRUCTIVE -> colors.error.copy(alpha = 0.13f).compositeOver(colors.surface)
    }
    val contentColor = when (tone) {
        ThemedActionTone.PRIMARY -> colors.onPrimary
        ThemedActionTone.NEUTRAL -> colors.onSurface
        ThemedActionTone.DESTRUCTIVE -> colors.error
    }
    val borderColor = when (tone) {
        ThemedActionTone.PRIMARY -> colors.primary.copy(alpha = 0.30f)
        ThemedActionTone.NEUTRAL -> colors.outline
        ThemedActionTone.DESTRUCTIVE -> colors.error.copy(alpha = 0.28f)
    }
    Surface(
        onClick = onClick,
        enabled = enabled && !isBusy,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .then(
                if (isBusy) {
                    Modifier.semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    }
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(13.dp),
        color = containerColor,
        contentColor = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
        border = BorderStroke(1.dp, borderColor.copy(alpha = borderColor.alpha * if (enabled) 1f else 0.34f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun ThemedAuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(13.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outline,
            disabledBorderColor = colors.outline.copy(alpha = 0.34f),
            focusedContainerColor = colors.primary.copy(alpha = 0.07f).compositeOver(colors.surface),
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surface.copy(alpha = 0.72f),
            cursorColor = colors.primary,
            focusedLabelColor = colors.primary,
        ),
    )
}

@Composable
fun ThemedAlertDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    confirmTone: ThemedActionTone = ThemedActionTone.PRIMARY,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier.then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = { Text(message) },
        confirmButton = {
            ThemedActionButton(
                text = confirmText,
                onClick = onConfirm,
                enabled = enabled,
                tone = confirmTone,
            )
        },
        dismissButton = dismissText?.let { label ->
            {
                ThemedActionButton(
                    text = label,
                    onClick = onDismissRequest,
                    enabled = enabled,
                    tone = ThemedActionTone.NEUTRAL,
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemedModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        tonalElevation = 0.dp,
        scrimColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .width(44.dp)
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(50)),
            )
        },
        content = content,
    )
}
