package com.apoorvdarshan.verceltics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.R
import com.apoorvdarshan.verceltics.ui.screens.about.AboutAppearance
import com.apoorvdarshan.verceltics.ui.screens.about.AboutDestination
import com.apoorvdarshan.verceltics.ui.screens.about.AboutScreenAction
import com.apoorvdarshan.verceltics.ui.screens.about.AboutScreenState
import com.apoorvdarshan.verceltics.ui.screens.about.AboutUpdateState
import java.util.Locale

/** Stateless About surface. Root owns persistence, update work, and external Android intents. */
@Composable
fun AboutScreen(
    state: AboutScreenState,
    onAction: (AboutScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag("about")) {
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("about.content"),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "updates") {
                AboutSectionCard(
                    title = stringResource(R.string.about_section_app),
                    testTag = "about.section.app",
                ) {
                    UpdateRow(
                        versionName = state.version.name,
                        state = state.update,
                        onCheck = { onAction(AboutScreenAction.CheckForUpdates) },
                        onOpen = { onAction(AboutScreenAction.OpenExternalUri(it)) },
                    )
                }
            }

            item(key = "appearance") {
                AppearanceSection(
                    selected = state.appearance,
                    onSelected = { onAction(AboutScreenAction.SelectAppearance(it)) },
                )
            }

            item(key = "links") {
                AboutSectionCard(
                    title = stringResource(R.string.about_section_links),
                    testTag = "about.section.links",
                ) {
                    DestinationRow(
                        icon = Icons.Rounded.Language,
                        title = stringResource(R.string.about_website),
                        subtitle = stringResource(R.string.about_website_subtitle),
                        destination = AboutDestination.WEBSITE,
                        onAction = onAction,
                    )
                    AboutDivider()
                    DestinationRow(
                        icon = Icons.Rounded.Code,
                        title = stringResource(R.string.about_source_code),
                        subtitle = stringResource(R.string.about_source_code_subtitle),
                        destination = AboutDestination.SOURCE_CODE,
                        onAction = onAction,
                    )
                    AboutDivider()
                    DestinationRow(
                        icon = Icons.Rounded.Business,
                        title = stringResource(R.string.about_linkedin),
                        subtitle = stringResource(R.string.about_linkedin_subtitle),
                        destination = AboutDestination.LINKED_IN,
                        onAction = onAction,
                    )
                    AboutDivider()
                    DestinationRow(
                        icon = Icons.Rounded.AlternateEmail,
                        title = stringResource(R.string.about_x),
                        subtitle = stringResource(R.string.about_x_subtitle),
                        destination = AboutDestination.X_PROFILE,
                        onAction = onAction,
                    )
                }
            }

            item(key = "support") {
                AboutSectionCard(
                    title = stringResource(R.string.about_section_support),
                    testTag = "about.section.support",
                ) {
                    DestinationRow(
                        icon = Icons.Rounded.Star,
                        title = stringResource(R.string.about_rate_app),
                        subtitle = stringResource(R.string.about_rate_app_subtitle),
                        destination = AboutDestination.RATE_APP,
                        onAction = onAction,
                    )
                    AboutDivider()
                    AboutActionRow(
                        icon = Icons.Rounded.Share,
                        title = stringResource(R.string.about_share),
                        subtitle = stringResource(R.string.about_share_subtitle),
                        testTag = "about.action.share",
                        onClick = { onAction(AboutScreenAction.ShareApp) },
                    )
                    AboutDivider()
                    DestinationRow(
                        icon = Icons.Rounded.Star,
                        title = stringResource(R.string.about_star_github),
                        subtitle = stringResource(R.string.about_star_github_subtitle),
                        destination = AboutDestination.SOURCE_CODE,
                        onAction = onAction,
                    )
                    AboutDivider()
                    DestinationRow(
                        icon = Icons.Rounded.ThumbUp,
                        title = stringResource(R.string.about_product_hunt),
                        subtitle = stringResource(R.string.about_product_hunt_subtitle),
                        destination = AboutDestination.PRODUCT_HUNT,
                        onAction = onAction,
                    )
                }
            }

            item(key = "developer-support") {
                AboutSectionCard(
                    title = stringResource(R.string.about_section_developer_support),
                    testTag = "about.section.developerSupport",
                ) {
                    DestinationRow(
                        icon = Icons.Rounded.Favorite,
                        title = stringResource(R.string.about_support_development),
                        subtitle = stringResource(R.string.about_support_development_subtitle),
                        destination = AboutDestination.SUPPORT_DEVELOPMENT,
                        onAction = onAction,
                    )
                }
            }

            item(key = "help") {
                AboutSectionCard(
                    title = stringResource(R.string.about_section_help),
                    testTag = "about.section.help",
                ) {
                    DestinationRow(
                        icon = Icons.Rounded.Email,
                        title = stringResource(R.string.about_contact),
                        subtitle = stringResource(R.string.about_contact_subtitle),
                        destination = AboutDestination.CONTACT,
                        onAction = onAction,
                    )
                    AboutDivider()
                    DestinationRow(
                        icon = Icons.Rounded.BugReport,
                        title = stringResource(R.string.about_report_issue),
                        subtitle = stringResource(R.string.about_report_issue_subtitle),
                        destination = AboutDestination.REPORT_ISSUE,
                        onAction = onAction,
                    )
                }
            }

            item(key = "legal") {
                AboutSectionCard(
                    title = stringResource(R.string.about_section_legal),
                    testTag = "about.section.legal",
                ) {
                    DestinationRow(
                        icon = Icons.Rounded.PrivacyTip,
                        title = stringResource(R.string.about_privacy),
                        subtitle = stringResource(R.string.about_privacy_subtitle),
                        destination = AboutDestination.PRIVACY_POLICY,
                        onAction = onAction,
                    )
                    AboutDivider()
                    DestinationRow(
                        icon = Icons.Rounded.Description,
                        title = stringResource(R.string.about_terms),
                        subtitle = stringResource(R.string.about_terms_subtitle),
                        destination = AboutDestination.TERMS_OF_SERVICE,
                        onAction = onAction,
                    )
                    AboutDivider()
                    DestinationRow(
                        icon = Icons.Rounded.Verified,
                        title = stringResource(R.string.about_license),
                        subtitle = stringResource(R.string.about_license_subtitle),
                        destination = AboutDestination.LICENSE,
                        onAction = onAction,
                    )
                }
            }

            item(key = "footer") { AboutFooter() }
        }
    }
}

@Composable
private fun AppearanceSection(
    selected: AboutAppearance,
    onSelected: (AboutAppearance) -> Unit,
) {
    val selectedExplanation = appearanceExplanation(selected)
    AboutSectionCard(
        title = stringResource(R.string.about_section_appearance),
        testTag = "about.section.appearance",
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AboutIconTile(
                    icon = appearanceIcon(selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.about_color_mode),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = selectedExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val useVerticalChoices = LocalDensity.current.fontScale >= 1.5f || maxWidth < 240.dp
                if (useVerticalChoices) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AboutAppearance.entries.forEach { appearance ->
                            AppearanceChoice(
                                appearance = appearance,
                                isSelected = appearance == selected,
                                onClick = { onSelected(appearance) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50),
                    ) {
                        Row(Modifier.padding(3.dp)) {
                            AboutAppearance.entries.forEach { appearance ->
                                AppearanceChoice(
                                    appearance = appearance,
                                    isSelected = appearance == selected,
                                    onClick = { onSelected(appearance) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceChoice(
    appearance: AboutAppearance,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = {
            if (!isSelected) {
                haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                onClick()
            }
        },
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .testTag("about.appearance.${appearance.persistedValue}")
            .semantics {
                role = Role.RadioButton
                selected = isSelected
            },
        shape = RoundedCornerShape(50),
        color = if (isSelected) colors.onSurface.copy(alpha = 0.2f) else Color.Transparent,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = appearanceTitle(appearance),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UpdateRow(
    versionName: String,
    state: AboutUpdateState,
    onCheck: () -> Unit,
    onOpen: (String) -> Unit,
) {
    when (state) {
        AboutUpdateState.NotConfigured -> AboutActionRow(
            icon = Icons.Rounded.Verified,
            title = stringResource(R.string.about_check_updates),
            subtitle = stringResource(R.string.about_updates_not_configured, versionName),
            testTag = "about.update.notConfigured",
        )

        AboutUpdateState.Idle -> AboutActionRow(
            icon = Icons.Rounded.Sync,
            title = stringResource(R.string.about_check_updates),
            subtitle = stringResource(R.string.about_version_format, versionName),
            testTag = "about.update.check",
            onClick = onCheck,
        )

        AboutUpdateState.Checking -> AboutActionRow(
            icon = Icons.Rounded.Sync,
            title = stringResource(R.string.about_checking_updates),
            subtitle = stringResource(R.string.about_version_format, versionName),
            testTag = "about.update.checking",
            isBusy = true,
        )

        is AboutUpdateState.Current -> AboutActionRow(
            icon = Icons.Rounded.Verified,
            title = stringResource(R.string.about_check_updates),
            subtitle = stringResource(R.string.about_version_current, state.checkedVersion),
            testTag = "about.update.current",
            onClick = onCheck,
        )

        is AboutUpdateState.Available -> AboutActionRow(
            icon = Icons.Rounded.SystemUpdate,
            title = stringResource(R.string.about_update_available),
            subtitle = stringResource(R.string.about_version_ready, state.latestVersion),
            testTag = "about.update.available",
            iconTint = MaterialTheme.colorScheme.tertiary,
            onClick = { onOpen(state.destinationUri) },
        )

        is AboutUpdateState.Failed -> AboutActionRow(
            icon = Icons.Rounded.Warning,
            title = stringResource(R.string.about_update_failed),
            subtitle = stringResource(R.string.about_update_failed_detail, state.message),
            testTag = "about.update.failed",
            iconTint = MaterialTheme.colorScheme.error,
            onClick = onCheck,
        )
    }
}

@Composable
private fun AboutSectionCard(
    title: String,
    testTag: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Text(
            text = title.uppercase(Locale.ROOT),
            modifier = Modifier
                .padding(start = 4.dp, bottom = 8.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun DestinationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    destination: AboutDestination,
    onAction: (AboutScreenAction) -> Unit,
) {
    AboutActionRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        testTag = "about.destination.${destination.name.lowercase(Locale.ROOT)}",
        onClick = { onAction(AboutScreenAction.OpenDestination(destination)) },
    )
}

@Composable
private fun AboutActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    isBusy: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 70.dp)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AboutIconTile(icon = icon, tint = iconTint)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            when {
                isBusy -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(22.dp)
                        .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )

                onClick != null -> Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (onClick == null || isBusy) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
        ) {
            rowContent()
        }
    } else {
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            rowContent()
        }
    }
}

@Composable
private fun AboutIconTile(
    icon: ImageVector,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(9.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 65.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun AboutFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("about.footer"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = stringResource(R.string.about_made_by),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun appearanceIcon(appearance: AboutAppearance): ImageVector = when (appearance) {
    AboutAppearance.SYSTEM -> Icons.Rounded.Contrast
    AboutAppearance.LIGHT -> Icons.Rounded.LightMode
    AboutAppearance.DARK -> Icons.Rounded.DarkMode
}

@Composable
private fun appearanceTitle(appearance: AboutAppearance): String = when (appearance) {
    AboutAppearance.SYSTEM -> stringResource(R.string.about_appearance_system)
    AboutAppearance.LIGHT -> stringResource(R.string.about_appearance_light)
    AboutAppearance.DARK -> stringResource(R.string.about_appearance_dark)
}

@Composable
private fun appearanceExplanation(appearance: AboutAppearance): String = when (appearance) {
    AboutAppearance.SYSTEM -> stringResource(R.string.about_appearance_system_explanation)
    AboutAppearance.LIGHT -> stringResource(R.string.about_appearance_light_explanation)
    AboutAppearance.DARK -> stringResource(R.string.about_appearance_dark_explanation)
}
