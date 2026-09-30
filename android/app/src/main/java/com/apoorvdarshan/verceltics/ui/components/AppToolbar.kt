package com.apoorvdarshan.verceltics.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.domain.IntegrationProvider

/** Balanced inline navigation, with 48 dp touch targets and compact visible controls. */
@Composable
fun AppToolbar(
    title: String,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { leading() }
        Text(title, modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { trailing() }
    }
}

@Composable
fun AppToolbarAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
    enabled: Boolean = true,
    testTag: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.then(if (testTag == null) Modifier else Modifier.testTag(testTag)), shape = CircleShape, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
fun AccountMenuButton(provider: IntegrationProvider?, onClick: () -> Unit, contentDescription: String, testTag: String? = null) {
    AppToolbarAction(onClick = onClick, testTag = testTag) {
        Row(Modifier.fillMaxSize().semantics { this.contentDescription = contentDescription }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            if (provider != null) ProviderLogo(provider, Modifier.size(24.dp), monochrome = true)
            Icon(Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Quiet provider identity card; a status footer leaves enough width for long names. */
@Composable
fun ProviderSummaryCard(
    provider: IntegrationProvider,
    title: String,
    subtitle: String,
    status: String,
    statusColor: Color = MaterialTheme.colorScheme.tertiary,
    detail: String? = null,
    testTag: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Surface(modifier = Modifier.fillMaxWidth().then(if (testTag == null) Modifier else Modifier.testTag(testTag)), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(provider.accentColor).copy(alpha = 0.20f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderMark(provider, size = 38.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(detail.orEmpty(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                StatusPill(status, statusColor)
            }
            content?.invoke(this)
        }
    }
}
