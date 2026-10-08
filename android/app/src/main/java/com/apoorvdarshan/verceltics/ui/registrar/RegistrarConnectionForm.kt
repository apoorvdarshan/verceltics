package com.apoorvdarshan.verceltics.ui.registrar

import android.content.ClipData
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.registrar.PublicIpv4Lookup
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAuthTextField
import com.apoorvdarshan.verceltics.ui.theme.LocalVercelticsDarkTheme
import java.lang.ref.WeakReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** iOS `RegistrarConnectionView.connectionNote`. */
internal fun registrarConnectionNote(provider: RegistrarProvider): String = when (provider) {
    RegistrarProvider.NAME_DOT_COM ->
        "Use a CORE API username and production token. If two-step verification is enabled, also turn Name.com API Access ON under Security Settings; that toggle authorizes API calls without a 2FA code."
    RegistrarProvider.NAMECHEAP ->
        "Enable API access and whitelist the same public IPv4 address you enter here. Namecheap requires that address on every API call."
    RegistrarProvider.GO_DADDY ->
        "GoDaddy may require portfolio size or a paid Discount Domain Club plan before production Domains API access is enabled."
    RegistrarProvider.GANDI ->
        "Create a scoped personal access token for the organization and domains you want available in the app."
    else ->
        "Create a key with read access for domain lists and add write permissions only for operations you want to run."
}

/** iOS `publicIPv4Explanation`. */
internal fun registrarPublicIpv4Explanation(provider: RegistrarProvider): String = when (provider) {
    RegistrarProvider.NAMECHEAP ->
        "Add this exact address to Namecheap’s API whitelist, then choose Use this IP to place it in the required ClientIp field."
    RegistrarProvider.NAME_DOT_COM ->
        "Only copy this into Name.com if you enable its optional IP allowlist. It is not saved or sent as a Name.com API credential."
    else -> ""
}

/** iOS field labels per registrar. */
internal fun registrarPrimaryCredentialLabel(provider: RegistrarProvider): String = when (provider) {
    RegistrarProvider.NAME_DOT_COM -> "API token"
    RegistrarProvider.GANDI -> "Personal access token"
    else -> "API key"
}

internal fun registrarUsernameLabel(provider: RegistrarProvider): String =
    if (provider == RegistrarProvider.NAMECHEAP) "Namecheap username / API user" else "API username"

/** iOS `canConnect`, with secret presence reported by the native password fields. */
internal fun canConnectRegistrar(
    provider: RegistrarProvider,
    hasApiKey: Boolean,
    hasApiSecret: Boolean,
    username: String,
    clientIp: String,
): Boolean {
    if (!hasApiKey) return false
    return when {
        provider == RegistrarProvider.NAME_DOT_COM -> username.isNotBlank()
        provider == RegistrarProvider.NAMECHEAP ->
            username.isNotBlank() && PublicIpv4Lookup.normalizedPublicIpv4(clientIp) != null
        provider.requiresSecret -> hasApiSecret
        else -> true
    }
}

@Composable
internal fun RegistrarConnectionForm(
    provider: RegistrarProvider,
    catalogProvider: IntegrationProvider,
    providerState: RegistrarProviderUiState,
    restoreError: String?,
    publicIpv4: RegistrarPublicIpv4Ui,
    onConnect: (RegistrarConnectRequest) -> Unit,
    onCancel: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDetectPublicIpv4: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(catalogProvider.accentColor)
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyController = remember(provider) { RegistrarSecretFieldController() }
    val secretController = remember(provider) { RegistrarSecretFieldController() }
    var hasApiKey by remember(provider) { mutableStateOf(false) }
    var hasApiSecret by remember(provider) { mutableStateOf(false) }
    var username by rememberSaveable(provider.id) { mutableStateOf("") }
    var clientIp by rememberSaveable(provider.id) { mutableStateOf("") }
    var organization by rememberSaveable(provider.id) { mutableStateOf("") }
    var localError by remember(provider) { mutableStateOf<String?>(null) }
    val isConnecting = providerState.operation == RegistrarOperation.CONNECTING
    val canConnect = canConnectRegistrar(provider, hasApiKey, hasApiSecret, username, clientIp) &&
        !providerState.isBusy

    DisposableEffect(keyController, secretController) {
        onDispose {
            keyController.clear()
            secretController.clear()
        }
    }

    fun submit() {
        if (!canConnect) {
            keyboard?.hide()
            return
        }
        val apiKey = keyController.consume()
        val apiSecret = if (provider.requiresSecret) secretController.consume() else null
        hasApiKey = false
        hasApiSecret = false
        if (apiKey == null || (provider.requiresSecret && apiSecret == null)) {
            localError = if (apiKey == null) "Enter the API key." else "Enter the API secret."
            return
        }
        localError = null
        keyboard?.hide()
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        onConnect(
            RegistrarConnectRequest(
                providerId = provider.id,
                apiKey = apiKey,
                apiSecret = apiSecret,
                username = username.trim(),
                clientIp = PublicIpv4Lookup.normalizedPublicIpv4(clientIp) ?: clientIp.trim(),
                organization = organization.trim(),
            ),
        )
    }

    // A plain scrolling column (not lazy): the native secret fields must stay attached while the
    // user scrolls, otherwise disposing them would silently clear what was typed.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("registrar.connectionForm")
            .padding(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProviderMark(provider = catalogProvider, size = 72.dp)
            Text(
                "Connect ${provider.displayName}",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                provider.apiDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        restoreError?.let { message ->
            RegistrarFeedbackPanel(
                title = "Saved registrar accounts need attention",
                message = message,
                isError = true,
                accent = accent,
                testTag = "registrar.restoreError",
            )
        }
        (localError ?: providerState.error)?.let { message ->
            RegistrarFeedbackPanel(
                title = "Connection failed",
                message = message,
                isError = true,
                accent = accent,
                testTag = "registrar.connectError",
            )
        }
        providerState.notice?.let { message ->
            RegistrarFeedbackPanel(title = null, message = message, isError = false, accent = accent)
        }
        RegistrarSecurityCard(provider, accent)
        ThemedActionButton(
            text = "Open ${provider.displayName} API settings",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onOpenUrl(provider.credentialUrl)
            },
            tone = ThemedActionTone.NEUTRAL,
            modifier = Modifier.fillMaxWidth(),
            testTag = "registrar.credentialLink",
        )
        if (provider.showsPublicIpv4Helper) {
            RegistrarPublicIpv4Helper(
                provider = provider,
                accent = accent,
                publicIpv4 = publicIpv4.takeIf { it.providerId == provider.id } ?: RegistrarPublicIpv4Ui(),
                clientIp = clientIp,
                onUseAddress = { address ->
                    clientIp = address
                    keyboard?.hide()
                },
                onDetect = onDetectPublicIpv4,
            )
        }
        OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (provider.requiresUsername) {
                    ThemedAuthTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            localError = null
                        },
                        label = registrarUsernameLabel(provider),
                        enabled = !isConnecting,
                        keyboardOptions = PLAIN_KEYBOARD,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("registrar.field.username"),
                    )
                }
                RegistrarSecretField(
                    controller = keyController,
                    label = registrarPrimaryCredentialLabel(provider),
                    accessibilityLabel = "${provider.displayName} ${registrarPrimaryCredentialLabel(provider)}",
                    accent = accent,
                    testTag = "registrar.field.apiKey",
                    onPresenceChange = {
                        hasApiKey = it
                        localError = null
                    },
                    onDone = ::submit,
                )
                if (provider.requiresSecret) {
                    RegistrarSecretField(
                        controller = secretController,
                        label = "API secret",
                        accessibilityLabel = "${provider.displayName} API secret",
                        accent = accent,
                        testTag = "registrar.field.apiSecret",
                        onPresenceChange = {
                            hasApiSecret = it
                            localError = null
                        },
                        onDone = ::submit,
                    )
                }
                if (provider.requiresClientIp) {
                    ThemedAuthTextField(
                        value = clientIp,
                        onValueChange = {
                            clientIp = it
                            localError = null
                        },
                        label = "Whitelisted public IPv4 address",
                        enabled = !isConnecting,
                        keyboardOptions = PLAIN_KEYBOARD.copy(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("registrar.field.clientIp"),
                    )
                    if (clientIp.isNotBlank() && PublicIpv4Lookup.normalizedPublicIpv4(clientIp) == null) {
                        InlineWarning("Enter a public IPv4 address that is also allowed in Namecheap.")
                    }
                }
                if (provider.acceptsOrganizationLabel) {
                    ThemedAuthTextField(
                        value = organization,
                        onValueChange = { organization = it },
                        label = "Organization label (optional)",
                        enabled = !isConnecting,
                        keyboardOptions = PLAIN_KEYBOARD,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("registrar.field.organization"),
                    )
                }
                if (isConnecting) {
                    ThemedActionButton(
                        "CANCEL REQUEST",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onCancel()
                        },
                        tone = ThemedActionTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "registrar.cancel",
                    )
                } else {
                    ThemedActionButton(
                        "Connect ${provider.displayName}".uppercase(),
                        enabled = canConnect,
                        isBusy = providerState.operation == RegistrarOperation.RESTORING,
                        onClick = ::submit,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "registrar.connect",
                    )
                }
            }
        }
    }
}

@Composable
private fun RegistrarSecurityCard(provider: RegistrarProvider, accent: Color) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("registrar.security"),
        color = MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = 0.24f),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Device-only credentials",
                    color = accent,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                registrarConnectionNote(provider),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Credentials are encrypted with this device’s Android Keystore and sent only to the registrar’s official API.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun RegistrarPublicIpv4Helper(
    provider: RegistrarProvider,
    accent: Color,
    publicIpv4: RegistrarPublicIpv4Ui,
    clientIp: String,
    onUseAddress: (String) -> Unit,
    onDetect: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(publicIpv4.address) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    fun copy(address: String) {
        scope.launch {
            runCatching { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Public IPv4", address))) }
        }
        copied = true
    }
    val address = publicIpv4.address
    val usingDetected = address != null && PublicIpv4Lookup.normalizedPublicIpv4(clientIp) == address
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("registrar.publicIpv4"),
        color = MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = 0.24f),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = accent.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
                    contentColor = accent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "PUBLIC NETWORK ADDRESS",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                    )
                    Text(
                        if (provider == RegistrarProvider.NAMECHEAP) "Required by Namecheap" else "Optional Name.com allowlist",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (publicIpv4.isDetecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
                }
            }

            when {
                address != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                "This network",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                address,
                                modifier = Modifier.testTag("registrar.publicIpv4.address"),
                                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        RegistrarIconAction(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = "Detect public IPv4 again",
                            enabled = !publicIpv4.isDetecting,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                onDetect()
                            },
                            testTag = "registrar.publicIpv4.redetect",
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val primaryLabel = when {
                            provider == RegistrarProvider.NAMECHEAP && usingDetected -> "Using this IP"
                            provider == RegistrarProvider.NAMECHEAP -> "Use this IP"
                            copied -> "Copied for Name.com"
                            else -> "Copy for Name.com"
                        }
                        val primaryIcon = when {
                            provider == RegistrarProvider.NAMECHEAP && usingDetected -> Icons.Rounded.CheckCircle
                            provider == RegistrarProvider.NAMECHEAP -> Icons.Rounded.Download
                            else -> Icons.Rounded.ContentCopy
                        }
                        RegistrarTintedAction(
                            text = primaryLabel,
                            icon = primaryIcon,
                            accent = accent,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                if (provider == RegistrarProvider.NAMECHEAP) onUseAddress(address) else copy(address)
                            },
                            modifier = Modifier.weight(1f),
                            testTag = "registrar.publicIpv4.use",
                        )
                        if (provider == RegistrarProvider.NAMECHEAP) {
                            RegistrarIconAction(
                                icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                contentDescription = if (copied) "Public IPv4 copied" else "Copy public IPv4",
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    copy(address)
                                },
                                testTag = "registrar.publicIpv4.copy",
                            )
                        }
                    }
                }
                publicIpv4.isDetecting -> Text(
                    "Detecting the public IPv4 used by this network…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> RegistrarTintedAction(
                    text = if (publicIpv4.error == null) "Detect IP to whitelist" else "Try detection again",
                    icon = Icons.Rounded.GpsFixed,
                    accent = accent,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onDetect()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "registrar.publicIpv4.detect",
                )
            }

            publicIpv4.error?.let { InlineWarning(it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                registrarPublicIpv4Explanation(provider),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Rounded.SyncAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Wi-Fi, cellular, or VPN changes can change this address.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun InlineWarning(message: String) {
    val warning = registrarWarningColor()
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = warning, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(message, color = warning, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun RegistrarTintedAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun RegistrarIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Native password field whose value never enters Compose state, saved instance state, autofill or
 * semantics. The secret is read once by [RegistrarSecretFieldController.consume].
 */
@Composable
private fun RegistrarSecretField(
    controller: RegistrarSecretFieldController,
    label: String,
    accessibilityLabel: String,
    accent: Color,
    testTag: String,
    onPresenceChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            color = colors.primary.copy(alpha = 0.07f).compositeOver(colors.surface),
            shape = RoundedCornerShape(13.dp),
            border = BorderStroke(if (LocalVercelticsDarkTheme.current) 1.dp else 2.dp, colors.outline),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = accent)
                Spacer(Modifier.width(10.dp))
                AndroidView(
                    factory = { context ->
                        EditText(context).apply {
                            background = null
                            setSingleLine(true)
                            setSaveEnabled(false)
                            importantForAutofill = EditText.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            textSize = 16f
                            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                            setPadding(0, 0, 0, 0)
                            hint = "Enter value"
                            contentDescription = accessibilityLabel
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                    onPresenceChange(!s.isNullOrBlank())
                                }
                                override fun afterTextChanged(s: Editable?) = Unit
                            })
                            setOnEditorActionListener { _, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onDone()
                                    true
                                } else {
                                    false
                                }
                            }
                            controller.attach(this)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 54.dp)
                        .testTag(testTag)
                        .semantics {
                            contentDescription = accessibilityLabel
                            password()
                            // Delegate focus/edit actions to the native field while exposing an
                            // intentionally empty EditableText so the secret never enters semantics.
                            this[SemanticsProperties.EditableText] = AnnotatedString("")
                            this[SemanticsActions.RequestFocus] = AccessibilityAction(
                                label = "Focus $accessibilityLabel",
                                action = controller::requestFocus,
                            )
                            this[SemanticsActions.SetText] = AccessibilityAction(
                                label = "Enter $accessibilityLabel",
                                action = { value -> controller.replace(value.text) },
                            )
                            this[SemanticsActions.InsertTextAtCursor] = AccessibilityAction(
                                label = "Type $accessibilityLabel",
                                action = { value -> controller.insert(value.text) },
                            )
                        },
                    update = { field ->
                        field.setTextColor(colors.onSurface.toArgb())
                        field.setHintTextColor(colors.onSurfaceVariant.toArgb())
                    },
                    onRelease = { field ->
                        controller.detach(field)
                        field.text?.clear()
                    },
                )
            }
        }
    }
}

internal class RegistrarSecretFieldController {
    private var field = WeakReference<EditText>(null)

    fun attach(editText: EditText) {
        field = WeakReference(editText)
    }

    fun detach(editText: EditText) {
        if (field.get() === editText) field.clear()
    }

    /** Reads the trimmed value once and clears the native field. */
    fun consume(): SecretValue? {
        val editable = field.get()?.text ?: return null
        val value = editable.toString().trim()
        editable.clear()
        return runCatching { SecretValue.of(value) }.getOrNull()
    }

    fun clear() {
        field.get()?.text?.clear()
    }

    fun requestFocus(): Boolean {
        val editText = field.get() ?: return false
        val focused = editText.requestFocus()
        if (focused) {
            editText.post {
                editText.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(editText, 0)
            }
        }
        return focused
    }

    fun replace(value: String): Boolean {
        val editText = field.get() ?: return false
        editText.setText(value)
        editText.setSelection(editText.text?.length ?: 0)
        return true
    }

    fun insert(value: String): Boolean {
        val editText = field.get() ?: return false
        val editable = editText.text ?: return false
        val selection = editText.selectionStart.coerceIn(0, editable.length)
        editable.insert(selection, value)
        editText.setSelection((selection + value.length).coerceAtMost(editable.length))
        return true
    }

    override fun toString(): String = "RegistrarSecretFieldController(value=<redacted>)"
}

private val PLAIN_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii,
)
