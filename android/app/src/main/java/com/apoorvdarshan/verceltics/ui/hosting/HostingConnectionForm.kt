package com.apoorvdarshan.verceltics.ui.hosting

import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.hosting.RailwayTokenType
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAuthTextField
import com.apoorvdarshan.verceltics.ui.theme.LocalVercelticsDarkTheme
import java.lang.ref.WeakReference

/**
 * iOS `HostingProviderCredentialView`. Secret fields are native password `EditText`s whose text
 * never enters Compose state, saved instance state or semantics; they are read once on submit.
 */
@Composable
internal fun HostingConnectionForm(
    provider: HostingProvider,
    catalogProvider: IntegrationProvider,
    state: HostingProviderUiState,
    onConnect: (HostingCredentials) -> Unit,
    onCancel: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val accent = Color(catalogProvider.accentColor)
    val tokenController = remember(provider) { EphemeralSecretController() }
    val awsSecretController = remember(provider) { EphemeralSecretController() }
    val sessionTokenController = remember(provider) { EphemeralSecretController() }
    var hasToken by remember(provider) { mutableStateOf(false) }
    var hasAwsSecret by remember(provider) { mutableStateOf(false) }
    var railwayTokenType by rememberSaveable(provider) { mutableStateOf(RailwayTokenType.ACCOUNT) }
    var organization by rememberSaveable(provider) { mutableStateOf("personal") }
    var projectId by rememberSaveable(provider) { mutableStateOf("") }
    var region by rememberSaveable(provider) { mutableStateOf("us-east-1") }
    // An access key ID is not a secret, but it is kept out of saved instance state anyway.
    var accessKeyId by remember(provider) { mutableStateOf("") }
    var localError by remember(provider) { mutableStateOf<String?>(null) }
    val connecting = state.operation == HostingOperation.CONNECTING
    val isFirebase = provider == HostingProvider.FIREBASE

    DisposableEffect(provider) {
        onDispose {
            tokenController.clear()
            awsSecretController.clear()
            sessionTokenController.clear()
        }
    }
    LaunchedEffect(state.status) {
        if (state.status == HostingConnectionStatus.CONNECTED) {
            tokenController.clear()
            awsSecretController.clear()
            sessionTokenController.clear()
            hasToken = false
            hasAwsSecret = false
        }
    }

    val canConnect = when (provider) {
        HostingProvider.FIREBASE -> projectId.isNotBlank()
        HostingProvider.AWS_AMPLIFY -> hasAwsSecret && accessKeyId.isNotBlank() && region.isNotBlank()
        HostingProvider.FLY -> hasToken && organization.isNotBlank()
        else -> hasToken
    }

    fun submit() {
        localError = null
        val credentials: HostingCredentials? = try {
            when (provider) {
                HostingProvider.FIREBASE -> HostingCredentials.Firebase(projectId)
                HostingProvider.AWS_AMPLIFY -> {
                    HostingCredentials.awsIdentifierProblem(accessKeyId, region)?.let { problem ->
                        localError = problem
                        null
                    } ?: run {
                        val secret = awsSecretController.consume()
                        val session = sessionTokenController.consume()
                        hasAwsSecret = false
                        if (secret == null) {
                            localError = "Enter the AWS secret access key."
                            null
                        } else {
                            HostingCredentials.AwsAmplify(accessKeyId, secret, region, session)
                        }
                    }
                }
                else -> {
                    val token = tokenController.consume()
                    hasToken = false
                    if (token == null) {
                        localError = "Enter the ${credentialLabel(provider).lowercase()}."
                        null
                    } else {
                        when (provider) {
                            HostingProvider.RAILWAY -> HostingCredentials.Railway(token, railwayTokenType)
                            HostingProvider.RENDER -> HostingCredentials.Render(token)
                            HostingProvider.DIGITAL_OCEAN -> HostingCredentials.DigitalOcean(token)
                            HostingProvider.HEROKU -> HostingCredentials.Heroku(token)
                            HostingProvider.FLY -> HostingCredentials.Fly(token, organization)
                        }
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            localError = error.message ?: "Check the connection details."
            null
        }
        if (credentials != null) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onConnect(credentials)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hosting.${provider.id}.connectionForm"),
        contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("header") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProviderMark(provider = catalogProvider, size = 64.dp)
                Text("Connect ${provider.displayName}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    catalogProvider.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val feedback = localError ?: state.error
        feedback?.let { message ->
            item("error") { HostingFeedbackPanel(title = "Connection failed", message = message, isError = true) }
        }
        state.notice?.let { message -> item("notice") { HostingFeedbackPanel(title = null, message = message, isError = false) } }
        item("instructions") {
            OffsetPanel(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                borderColor = accent.copy(alpha = 0.22f),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connect securely", style = MaterialTheme.typography.titleMedium)
                    HostingStepRow(1, instructionOne(provider), accent)
                    HostingStepRow(2, instructionTwo(provider, railwayTokenType), accent)
                    HostingStepRow(
                        3,
                        if (isFirebase) {
                            "Enter the project ID, then continue with Google"
                        } else {
                            "Paste the credentials below and connect"
                        },
                        accent,
                    )
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(
                            credentialStorageMessage(provider),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item("credential-link") {
            ThemedActionButton(
                if (isFirebase) "OPEN FIREBASE CONSOLE" else "OPEN ${provider.displayName.uppercase()} CREDENTIALS",
                onClick = { onOpenLink(provider.credentialPageUrl) },
                tone = ThemedActionTone.NEUTRAL,
                modifier = Modifier.fillMaxWidth(),
                testTag = "hosting.${provider.id}.credentialLink",
            )
        }
        item("fields") {
            OffsetPanel(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (provider) {
                        HostingProvider.FIREBASE -> PlainField(
                            label = "Firebase / Google Cloud project ID",
                            value = projectId,
                            onValueChange = {
                                projectId = it
                                localError = null
                            },
                            testTag = "hosting.firebase.projectId",
                            imeAction = ImeAction.Done,
                        )
                        HostingProvider.AWS_AMPLIFY -> {
                            PlainField(
                                label = "AWS Access Key ID",
                                value = accessKeyId,
                                onValueChange = {
                                    accessKeyId = it
                                    localError = null
                                },
                                testTag = "hosting.awsAmplify.accessKeyId",
                                keyboardCapitalization = KeyboardCapitalization.Characters,
                            )
                            EphemeralSecretInput(
                                label = "AWS Secret Access Key",
                                hint = "Enter value",
                                accent = accent,
                                controller = awsSecretController,
                                testTag = "hosting.awsAmplify.secretAccessKey",
                                onPresenceChange = {
                                    hasAwsSecret = it
                                    localError = null
                                },
                                onDone = {},
                            )
                            PlainField(
                                label = "Region (for example us-east-1)",
                                value = region,
                                onValueChange = {
                                    region = it
                                    localError = null
                                },
                                testTag = "hosting.awsAmplify.region",
                            )
                            EphemeralSecretInput(
                                label = "Session token (optional)",
                                hint = "Enter value",
                                accent = accent,
                                controller = sessionTokenController,
                                testTag = "hosting.awsAmplify.sessionToken",
                                onPresenceChange = {},
                                onDone = { if (canConnect && !connecting) submit() },
                            )
                        }
                        else -> {
                            EphemeralSecretInput(
                                label = credentialLabel(provider),
                                hint = "Enter value",
                                accent = accent,
                                controller = tokenController,
                                testTag = "hosting.${provider.id}.token",
                                onPresenceChange = {
                                    hasToken = it
                                    localError = null
                                },
                                onDone = { if (canConnect && !connecting) submit() },
                            )
                            if (provider == HostingProvider.RAILWAY) {
                                HostingSegmentedChoice(
                                    label = "Railway token type",
                                    options = listOf(
                                        RailwayTokenType.ACCOUNT to "Account / Workspace",
                                        RailwayTokenType.PROJECT to "Project",
                                    ),
                                    selected = railwayTokenType,
                                    accent = accent,
                                    onSelect = { railwayTokenType = it },
                                    testTagPrefix = "hosting.railway.tokenType",
                                )
                            } else if (provider == HostingProvider.FLY) {
                                PlainField(
                                    label = "Organization slug",
                                    value = organization,
                                    onValueChange = {
                                        organization = it
                                        localError = null
                                    },
                                    testTag = "hosting.fly.organization",
                                )
                            }
                        }
                    }
                }
            }
        }
        item("connect") {
            if (connecting) {
                ThemedActionButton(
                    "CANCEL REQUEST",
                    onClick = onCancel,
                    tone = ThemedActionTone.NEUTRAL,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "hosting.${provider.id}.cancel",
                )
            } else {
                ThemedActionButton(
                    if (isFirebase) "CONTINUE WITH GOOGLE" else "CONNECT ${provider.displayName.uppercase()}",
                    onClick = ::submit,
                    enabled = canConnect && state.operation == null,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "hosting.${provider.id}.connect",
                )
            }
        }
    }
}

@Composable
private fun HostingStepRow(number: Int, text: String, accent: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = accent.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(number.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PlainField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    testTag: String,
    imeAction: ImeAction = ImeAction.Next,
    keyboardCapitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    ThemedAuthTextField(
        value = value,
        onValueChange = { onValueChange(it.take(MAX_PLAIN_FIELD_CHARACTERS)) },
        label = label,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        keyboardOptions = KeyboardOptions(
            capitalization = keyboardCapitalization,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = imeAction,
        ),
    )
}

@Composable
private fun <T> HostingSegmentedChoice(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    accent: Color,
    onSelect: (T) -> Unit,
    testTagPrefix: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, title) ->
                val isSelected = value == selected
                Surface(
                    onClick = { onSelect(value) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .testTag("$testTagPrefix.${value.toString().lowercase()}")
                        .semantics {
                            role = Role.RadioButton
                            this.selected = isSelected
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        accent.copy(alpha = 0.18f).compositeOver(MaterialTheme.colorScheme.surface)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = BorderStroke(1.dp, if (isSelected) accent else MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun EphemeralSecretInput(
    label: String,
    hint: String,
    accent: Color,
    controller: EphemeralSecretController,
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
            Row(modifier = Modifier.padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = accent)
                Spacer(Modifier.width(10.dp))
                AndroidView(
                    factory = { context ->
                        EditText(context).apply {
                            background = null
                            setSingleLine(true)
                            isSaveEnabled = false
                            importantForAutofill = EditText.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            textSize = 16f
                            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                            setPadding(0, 0, 0, 0)
                            this.hint = hint
                            contentDescription = label
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
                            contentDescription = label
                            password()
                            // Publish edit actions without ever exposing the secret to semantics.
                            this[SemanticsProperties.EditableText] = AnnotatedString("")
                            this[SemanticsActions.RequestFocus] = AccessibilityAction("Focus $label", controller::requestFocus)
                            this[SemanticsActions.SetText] = AccessibilityAction("Enter $label") { value ->
                                controller.replace(value.text)
                            }
                            this[SemanticsActions.InsertTextAtCursor] = AccessibilityAction("Type $label") { value ->
                                controller.insert(value.text)
                            }
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

/** Holds only a weak reference to a native password field; the value is read once and cleared. */
internal class EphemeralSecretController {
    private var field = WeakReference<EditText>(null)

    fun attach(editText: EditText) {
        field = WeakReference(editText)
    }

    fun detach(editText: EditText) {
        if (field.get() === editText) field.clear()
    }

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

    override fun toString(): String = "EphemeralSecretController(value=<redacted>)"
}

// region iOS copy (HostingProviderCredentialView)

internal fun credentialLabel(provider: HostingProvider): String = when (provider) {
    HostingProvider.FLY -> "Fly.io access token"
    HostingProvider.AWS_AMPLIFY -> "AWS Secret Access Key"
    else -> "${provider.displayName} API token"
}

internal fun instructionOne(provider: HostingProvider): String = when (provider) {
    HostingProvider.AWS_AMPLIFY -> "Create an IAM access key with Amplify permissions"
    HostingProvider.FIREBASE -> "Enable the Firebase Hosting API for your Google Cloud project"
    else -> "Open ${provider.displayName}’s token or API key page"
}

internal fun instructionTwo(provider: HostingProvider, railwayTokenType: RailwayTokenType): String = when (provider) {
    HostingProvider.RAILWAY -> if (railwayTokenType == RailwayTokenType.PROJECT) {
        "Copy a project token from Project Settings, or choose Account / Workspace for a broader API token"
    } else {
        "Create an account or workspace token with the access you want Verceltics to use"
    }
    HostingProvider.FLY -> "Copy the token and your organization slug"
    HostingProvider.FIREBASE -> "Use a Google account with access to that Firebase project"
    HostingProvider.AWS_AMPLIFY -> "Copy the access key ID, secret and AWS region"
    else -> "Create a token with the access you want Verceltics to use"
}

internal fun credentialStorageMessage(provider: HostingProvider): String = if (provider == HostingProvider.FIREBASE) {
    "Google authorization is handled by your Google sign-in. Tokens go only to Google’s official OAuth and " +
        "Firebase Hosting endpoints."
} else {
    "Credentials are encrypted and stay on this device. Verceltics sends them only to ${provider.displayName}’s official API."
}

// endregion

private const val MAX_PLAIN_FIELD_CHARACTERS = 128
