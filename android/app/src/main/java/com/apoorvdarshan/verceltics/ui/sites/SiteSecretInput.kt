package com.apoorvdarshan.verceltics.ui.sites

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.ui.theme.LocalVercelticsDarkTheme
import java.lang.ref.WeakReference

/**
 * Holds a weak reference to the native password field. The secret is read once by [consume],
 * which clears the field; it never enters Compose state, saved state, or semantics.
 */
internal class SiteSecretController {
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

    override fun toString(): String = "SiteSecretController(value=<redacted>)"
}

@Composable
internal fun SiteSecretInput(
    controller: SiteSecretController,
    label: String,
    placeholder: String,
    accent: Color,
    enabled: Boolean,
    onPresenceChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    testTag: String,
) {
    val colors = MaterialTheme.colorScheme
    // The native field is created once; always call the latest callbacks (and form values).
    val currentOnPresenceChange by rememberUpdatedState(onPresenceChange)
    val currentOnDone by rememberUpdatedState(onDone)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label.uppercase(),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            color = colors.primary.copy(alpha = 0.07f).compositeOver(colors.surface),
            shape = RoundedCornerShape(13.dp),
            border = BorderStroke(if (LocalVercelticsDarkTheme.current) 1.dp else 1.5.dp, colors.outline),
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
                            isSaveEnabled = false
                            importantForAutofill = EditText.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            textSize = 16f
                            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                            setPadding(0, 0, 0, 0)
                            hint = placeholder
                            contentDescription = label
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                    currentOnPresenceChange(!s.isNullOrBlank())
                                }

                                override fun afterTextChanged(s: Editable?) = Unit
                            })
                            setOnEditorActionListener { _, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                    currentOnDone()
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
                            // Publish focus/edit actions while exposing an intentionally empty
                            // EditableText so the secret never enters the semantics tree.
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
                        field.isEnabled = enabled
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
