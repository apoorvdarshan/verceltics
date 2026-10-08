package com.apoorvdarshan.verceltics.ui.vercel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.VercelAccountUi
import com.apoorvdarshan.verceltics.ui.components.AppToolbar
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import com.apoorvdarshan.verceltics.ui.components.ThemedAuthTextField
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl

/** Loads Vercel profile avatars. Absent (tests, previews) means initials only. */
fun interface VercelAvatarSource {
    suspend fun load(url: String): ImageBitmap?

    /** An already-loaded avatar, read synchronously so menus never flash a placeholder. */
    fun cached(url: String): ImageBitmap? = null
}

val LocalVercelAvatarSource = staticCompositionLocalOf<VercelAvatarSource?> { null }

/**
 * A round Vercel profile avatar (iOS `ProviderAccountMenu.providerBadge`). Until the image loads,
 * or when there is none, [fallback] is shown, or else the account initial on a colored disc.
 */
@Composable
internal fun VercelAccountAvatar(
    account: VercelAccountUi,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    fallback: (@Composable () -> Unit)? = null,
) {
    val source = LocalVercelAvatarSource.current
    val url = account.avatarUrl
    var image by remember(url, source) { mutableStateOf(url?.let { source?.cached(it) }) }
    LaunchedEffect(url, source) {
        if (url == null || source == null || image != null) return@LaunchedEffect
        image = source.load(url)
    }
    val density = LocalDensity.current
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image
        when {
            loaded != null -> Image(
                bitmap = loaded,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .testTag("vercel.accountAvatar.image"),
            )

            fallback != null -> fallback()

            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(VERCEL_LETTER_TILE_COLORS[vercelLetterTileColorIndex(account.displayName)]))
                    .testTag("vercel.accountAvatar.initial"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    vercelProjectInitial(account.displayName),
                    modifier = Modifier.clearAndSetSemantics { },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = with(density) { (size * 0.44f).toSp() },
                )
            }
        }
    }
}

/**
 * The Vercel section of the hosting account menu (iOS `ProviderAccountMenu`): every saved account
 * with its avatar, the active one checked, then "Add Vercel account".
 */
@Composable
internal fun VercelAccountMenuSection(
    accounts: List<VercelAccountUi>,
    activeAccountId: String?,
    enabled: Boolean,
    onSwitch: (VercelAccountUi) -> Unit,
    onAddAccount: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Text(
        "VERCEL",
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
            .semantics { heading() }
            .testTag("workspace.hosting.accountMenu.vercelSection"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    accounts.forEach { account ->
        val isActive = account.id == activeAccountId
        DropdownMenuItem(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 54.dp)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                )
                .testTag("workspace.hosting.accountMenu.account.${account.id}")
                .semantics {
                    selected = isActive
                    stateDescription = if (isActive) "Active account" else "Switch to this account"
                },
            enabled = enabled || isActive,
            text = {
                Column {
                    Text(
                        account.displayName,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    account.email?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            leadingIcon = { VercelAccountAvatar(account, size = 30.dp) },
            trailingIcon = if (isActive) {
                {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            } else {
                null
            },
            colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onSwitch(account)
            },
        )
    }
    DropdownMenuItem(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .testTag("workspace.hosting.accountMenu.addVercel"),
        enabled = enabled,
        text = { Text("Add Vercel account") },
        leadingIcon = {
            Icon(Icons.Rounded.AddCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onAddAccount()
        },
    )
}

/** iOS "Remove Current Account" and, with more than one account, "Remove All Accounts". */
@Composable
internal fun VercelAccountRemovalMenuItems(
    accountCount: Int,
    enabled: Boolean,
    onRemoveCurrent: () -> Unit,
    onRemoveAll: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    DropdownMenuItem(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
            .testTag("workspace.hosting.accountMenu.removeCurrent"),
        enabled = enabled,
        text = { Text("Remove current account") },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
        colors = MenuDefaults.itemColors(
            textColor = MaterialTheme.colorScheme.error,
            leadingIconColor = MaterialTheme.colorScheme.error,
        ),
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onRemoveCurrent()
        },
    )
    if (accountCount > 1) {
        DropdownMenuItem(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                .testTag("workspace.hosting.accountMenu.removeAll"),
            enabled = enabled,
            text = { Text("Remove all Vercel accounts") },
            leadingIcon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null) },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            ),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onRemoveAll()
            },
        )
    }
}

/**
 * A pending destructive account removal, saved as a string so it survives recreation: `all`, or
 * `account:{id}` for one captured account (iOS removes exactly the account it asked about).
 */
internal sealed interface VercelAccountRemoval {
    data class One(val accountId: String) : VercelAccountRemoval

    data object All : VercelAccountRemoval

    val saved: String
        get() = when (this) {
            All -> SAVED_ALL
            is One -> "$SAVED_ACCOUNT_PREFIX$accountId"
        }

    companion object {
        private const val SAVED_ALL = "all"
        private const val SAVED_ACCOUNT_PREFIX = "account:"

        fun fromSaved(value: String?): VercelAccountRemoval? = when {
            value == SAVED_ALL -> All
            value != null && value.startsWith(SAVED_ACCOUNT_PREFIX) && value.length > SAVED_ACCOUNT_PREFIX.length ->
                One(value.removePrefix(SAVED_ACCOUNT_PREFIX))
            else -> null
        }
    }
}

/** The destructive confirmation for [removal]; removal only happens on confirm. */
@Composable
internal fun VercelAccountRemovalDialog(
    removal: VercelAccountRemoval,
    accounts: List<VercelAccountUi>,
    enabled: Boolean,
    onConfirm: (VercelAccountRemoval) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val title = when (removal) {
        VercelAccountRemoval.All -> "Remove all Vercel accounts?"
        is VercelAccountRemoval.One -> {
            val name = accounts.firstOrNull { it.id == removal.accountId }?.displayName
            if (name == null) "Remove account?" else "Remove $name?"
        }
    }
    ThemedAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        message = when (removal) {
            VercelAccountRemoval.All -> "Every saved Vercel token is removed from this device only."
            is VercelAccountRemoval.One -> "Credentials are removed from this device only."
        },
        confirmText = if (removal == VercelAccountRemoval.All) "REMOVE ALL ACCOUNTS" else "REMOVE ACCOUNT",
        confirmTone = ThemedActionTone.DESTRUCTIVE,
        dismissText = "CANCEL",
        onConfirm = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onConfirm(removal)
        },
        enabled = enabled,
        testTag = if (removal == VercelAccountRemoval.All) {
            "workspace.hosting.removeAllAccountsDialog"
        } else {
            "workspace.hosting.removeAccountDialog"
        },
    )
}

/**
 * Saved accounts with a switch action for the inactive ones, used where the toolbar menu is not
 * available (offline recovery and the Vercel connection screen).
 */
@Composable
internal fun VercelSavedAccountsList(
    accounts: List<VercelAccountUi>,
    activeAccountId: String?,
    enabled: Boolean,
    onSwitch: (VercelAccountUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier.testTag("vercel.savedAccounts"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "SAVED ACCOUNTS",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        accounts.forEach { account ->
            val isActive = account.id == activeAccountId
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSwitch(account)
                },
                enabled = enabled && !isActive,
                shape = RoundedCornerShape(13.dp),
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("vercel.savedAccount.${account.id}")
                    .semantics {
                        selected = isActive
                        stateDescription = if (isActive) "Active account" else "Switch to this account"
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VercelAccountAvatar(account, size = 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            account.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        account.email?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (isActive) {
                        StatusPill(text = "Active", color = MaterialTheme.colorScheme.tertiary)
                    } else {
                        Text(
                            "SWITCH",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/** Steps from iOS `LoginView` for creating a Vercel personal access token. */
internal val VERCEL_TOKEN_STEPS: List<String> = listOf(
    "Go to vercel.com/account/tokens",
    "Tap \"Create Token\"",
    "Name it anything (e.g. Verceltics)",
    "Set scope to your account",
    "Copy and paste below",
)

internal const val VERCEL_TOKENS_URL = "https://vercel.com/account/tokens"

/**
 * The Vercel personal-token form: how to create a token, a masked field, and the connect action.
 * The token lives only in composition (never in saved state) and is cleared once submitted.
 */
@Composable
internal fun VercelTokenConnectForm(
    title: String,
    subtitle: String,
    isBusy: Boolean,
    error: String?,
    onConnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Credentials must never be serialized into Android saved instance state.
    var token by remember { mutableStateOf("") }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        VercelTokenInstructions()
        Spacer(Modifier.height(14.dp))
        ThemedAuthTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("vercel.token"),
            enabled = !isBusy,
            label = "Personal access token",
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
            ),
            trailingIcon = {
                ThemedGlassControl(
                    modifier = Modifier.size(42.dp),
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                    onClick = {
                        tokenVisible = !tokenVisible
                        haptic.performHapticFeedback(
                            if (tokenVisible) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                        )
                    },
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Icon(
                            if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                        )
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        ThemedActionButton(
            text = if (isBusy) "CHECKING TOKEN" else "CONNECT SECURELY",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                val pendingToken = token.trim()
                if (pendingToken.isNotEmpty()) token = ""
                onConnect(pendingToken)
            },
            enabled = !isBusy,
            isBusy = isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp),
            testTag = "vercel.connect",
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            VercelConnectErrorNotice(it)
        }
    }
}

@Composable
private fun VercelTokenInstructions() {
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp))
                .padding(16.dp)
                .testTag("vercel.tokenSteps"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "How to get your token",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            VERCEL_TOKEN_STEPS.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        ThemedActionButton(
            text = "Open Vercel Tokens Page",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                runCatching { uriHandler.openUri(VERCEL_TOKENS_URL) }
            },
            tone = ThemedActionTone.NEUTRAL,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp),
            testTag = "vercel.openTokensPage",
        )
    }
}

@Composable
internal fun VercelConnectErrorNotice(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), RoundedCornerShape(13.dp))
            .padding(12.dp)
            .testTag("vercel.connectError"),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "!",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Adds another Vercel account from the Hosting workspace (iOS "Add Account" sheet) while the
 * active account stays connected. The window is secured while the token can be typed.
 */
@Composable
internal fun VercelAddAccountScreen(
    activeAccount: VercelAccountUi?,
    isBusy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onConnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProtectVercelCredentialWindow()
    // Always consumed: while a token is being checked, Back must not leave the app either.
    BackHandler { if (!isBusy) onBack() }
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("workspace.hosting.addAccount"),
    ) {
        AppToolbar(
            title = "Add Vercel Account",
            leading = {
                AppToolbarAction(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onBack()
                    },
                    enabled = !isBusy,
                    testTag = "workspace.hosting.addAccount.back",
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Vercel projects")
                }
            },
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val metrics = VercelLayout.page(
                availableWidthDp = maxWidth.value,
                windowWidthDp = vercelWindowWidthDp(),
                compactPaddingDp = 18f,
                maxContentWidthDp = VercelLayout.DETAIL_MAX_WIDTH_DP,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = metrics.horizontalPadding,
                    top = 6.dp,
                    end = metrics.horizontalPadding,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "form") {
                    OffsetPanel(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                        VercelTokenConnectForm(
                            title = "Connect another account",
                            subtitle = activeAccount?.let {
                                "${it.displayName} stays connected. Switch accounts from the Hosting menu."
                            } ?: "Use a personal access token. It never appears again after saving.",
                            isBusy = isBusy,
                            error = error,
                            onConnect = onConnect,
                            modifier = Modifier.padding(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Keeps credential entry out of screenshots, screen recording and the recents thumbnail. */
@Composable
internal fun ProtectVercelCredentialWindow() {
    val context = LocalView.current.context
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
