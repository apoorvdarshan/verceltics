package com.apoorvdarshan.verceltics.ui.sample

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.ProviderLogo
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private data class SampleDomain(val name: String, val expiresInDays: Int, val autoRenew: Boolean, val privacy: Boolean = true)
private val SamplePortfolios = mapOf(
    "nameDotCom" to listOf(SampleDomain("studio.example", 284, true), SampleDomain("studio-design.example", 18, false), SampleDomain("docs-studio.example", 196, true), SampleDomain("edge-tools.example", 92, true)),
    "namecheap" to listOf(SampleDomain("commerce.example", 347, true), SampleDomain("launch-kit.example", 12, false), SampleDomain("portfolio.example", 162, true), SampleDomain("newsletter.example", 68, true), SampleDomain("old-project.example", 24, false, false)),
)

/** Registrar layout preview. No credentials, registrar API or renewal purchase is involved. */
@Composable
fun SampleRegistrarScreen(
    modifier: Modifier = Modifier,
    initialProviderId: String = "nameDotCom",
    searchRequestId: Int = 0,
    onBack: (() -> Unit)? = null,
) {
    var providerId by rememberSaveable(initialProviderId) { mutableStateOf(initialProviderId) }
    var selectedName by rememberSaveable(providerId) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(providerId) { mutableStateOf("") }
    val provider = requireNotNull(IntegrationCatalog.provider(providerId))
    val domains = SamplePortfolios.getValue(providerId)
    val selected = domains.firstOrNull { it.name == selectedName }
    val accent = Color(provider.accentColor)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchRequestId) {
        if (searchRequestId > 0) {
            selectedName = null
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    BackHandler(enabled = selected != null) { selectedName = null }
    Column(modifier.fillMaxSize().testTag("sample.registrars")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null || onBack != null) {
                IconButton(onClick = { if (selected != null) selectedName = null else onBack?.invoke() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
            }
            Text(if (selected == null) "Registrars" else "Domain details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ProviderLogo(provider, Modifier.size(28.dp))
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (selected == null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("nameDotCom", "namecheap").forEach { id ->
                            FilterChip(selected = providerId == id, onClick = { providerId = id }, label = { Text(requireNotNull(IntegrationCatalog.provider(id)).displayName) })
                        }
                    }
                }
                item {
                    PreviewCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            ProviderLogo(provider, Modifier.size(44.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Studio · ${provider.displayName}", style = MaterialTheme.typography.titleMedium)
                                Text("Domain portfolio", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("EXPIRY HEALTH", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val expiring = domains.count { it.expiresInDays <= 30 }
                        Text(if (expiring == 1) "1 domain expires within 30 days" else "$expiring domains expire within 30 days", color = Color(0xFFFFB74D))
                        LinearProgressIndicator(progress = { domains.count { it.expiresInDays > 30 }.toFloat() / domains.size }, modifier = Modifier.fillMaxWidth(), color = accent)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Domains" to domains.size, "Attention" to domains.count { it.expiresInDays <= 30 }, "Auto renew" to domains.count { it.autoRenew }).forEach { (label, count) ->
                            Surface(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(count.toString(), style = MaterialTheme.typography.headlineSmall, color = accent)
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                item {
                    ControlSearchField(value = query, onValueChange = { query = it }, placeholder = "Search domains", focusRequester = focusRequester, onSearch = { keyboard?.hide() }, modifier = Modifier.fillMaxWidth(), testTag = "sample.registrars.search")
                }
                items(domains.filter { it.name.contains(query.trim(), true) }, key = { it.name }) { domain ->
                    Surface(onClick = { selectedName = domain.name; keyboard?.hide() }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column {
                                Text(domain.expiresInDays.toString(), style = MaterialTheme.typography.titleLarge, color = if (domain.expiresInDays <= 30) Color(0xFFFFB74D) else accent)
                                Text("DAYS", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(domain.name, style = MaterialTheme.typography.titleSmall)
                                Text("Expires ${expiryDate(domain)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${if (domain.autoRenew) "Auto renew" else "Manual renewal"} · ${if (domain.privacy) "Private" else "Public WHOIS"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "View ${domain.name}")
                        }
                    }
                }
                if (domains.none { it.name.contains(query.trim(), true) }) item { Text("No domains match your search.") }
            } else {
                item { PreviewCard {
                    Text(selected.name, style = MaterialTheme.typography.headlineSmall)
                    Text("Active · ${provider.displayName}", color = accent)
                    Text("Expires ${expiryDate(selected)} · ${selected.expiresInDays} days remaining", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { PreviewCard {
                    Text("Domain protection", style = MaterialTheme.typography.titleMedium)
                    SampleToggle("Auto renew", selected.autoRenew, "${providerId}.${selected.name}.renew")
                    SampleToggle("WHOIS privacy", selected.privacy, "${providerId}.${selected.name}.privacy")
                    SampleToggle("Transfer lock", true, "${providerId}.${selected.name}.lock")
                } }
                item { PreviewCard {
                    Text("Nameservers", style = MaterialTheme.typography.titleMedium)
                    Text("maya.ns.cloudflare.com")
                    Text("rick.ns.cloudflare.com")
                } }
                item { PreviewCard {
                    Text("DNS records", style = MaterialTheme.typography.titleMedium)
                    listOf("A  ·  @" to "192.0.2.10", "CNAME  ·  www" to selected.name, "MX  ·  @" to "10 mail.example", "TXT  ·  @" to "v=spf1 include:mail.example ~all").forEach { (type, value) ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(type, style = MaterialTheme.typography.labelLarge, color = accent)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                        Text("TTL 3600", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } }
                item { Text("Sample records and settings. Changes here only affect this preview.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

private fun expiryDate(domain: SampleDomain) = LocalDate.now().plusDays(domain.expiresInDays.toLong()).format(DateTimeFormatter.ofPattern("d MMM yyyy"))

@Composable
private fun PreviewCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SampleToggle(label: String, initial: Boolean, id: String) {
    var checked by rememberSaveable(id) { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { checked = it })
    }
}
