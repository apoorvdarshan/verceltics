package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerVersionDetailInfo
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.titleCased
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsActionButton
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsLoading
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsMonospaceBlock
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Port of iOS `CloudflareWorkerVersionDetailView`. */
@Composable
fun CloudflareWorkerVersionScreen(
    viewModel: CloudflareWorkerVersionViewModel,
    refreshSignal: Int,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloudflareWorkerRefreshEffect(refreshSignal) { viewModel.load(forceRefresh = true) }
    val detail = state.detail
    CloudflareOpsScreen(
        "cloudflare.workerVersion",
        modifier,
        isRefreshing = state.isLoading || state.isRefreshing,
        onRefresh = { viewModel.load(forceRefresh = true) },
    ) {
        when {
            detail != null -> {
                item("header") {
                    CloudflareOpsPanel(accent = 0.08f) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                detail.number?.let { "VERSION $it" } ?: "WORKER VERSION",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = CloudflareOpsColors.Orange,
                            )
                            SelectionContainer {
                                Text(
                                    detail.id,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                versionDetailSubtitle(detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item("bindings") {
                    CloudflareOpsPanel(testTag = "cloudflare.workerVersion.bindings") {
                        CloudflareOpsSectionHeader("Bindings", Icons.Rounded.Link, count = detail.bindings.size)
                        CloudflareOpsDivider()
                        if (detail.bindings.isEmpty()) {
                            CloudflareOpsEmptySection(
                                icon = Icons.Rounded.Link,
                                title = "No bindings returned",
                                message = "This version has no external resource bindings.",
                            )
                        } else {
                            detail.bindings.forEach { binding ->
                                CloudflareOpsResourceRow(
                                    icon = Icons.Rounded.Link,
                                    title = binding.name,
                                    subtitle = binding.type,
                                    tint = CloudflareOpsColors.Amber,
                                )
                            }
                        }
                    }
                }
            }
            state.error != null -> item("error") {
                LoadFailure(state.error.orEmpty()) { viewModel.load(forceRefresh = true) }
            }
            else -> item("loading") { CloudflareOpsLoading("Loading version…") }
        }
    }
}

/** iOS `versionDetailSubtitle`. */
internal fun versionDetailSubtitle(detail: CloudflareWorkerVersionDetailInfo): String = buildList {
    detail.metadata?.source?.let { add(it.titleCased()) }
    detail.metadata?.authorEmail?.let(::add)
    detail.startupTimeMilliseconds?.let { add("Startup ${CloudflareFormat.decimal(java.math.BigDecimal.valueOf(it))} ms") }
}.joinToString(" · ").ifEmpty { "Version metadata" }

/** Port of iOS `CloudflareWorkerContentView`. Source stays in memory only. */
@Composable
fun CloudflareWorkerContentScreen(
    viewModel: CloudflareWorkerContentViewModel,
    refreshSignal: Int,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloudflareWorkerRefreshEffect(refreshSignal) { viewModel.load() }
    val content = state.content
    val chunks = remember(content) { content?.text?.lines()?.chunked(CONTENT_LINES_PER_BLOCK).orEmpty() }
    CloudflareOpsScreen(
        "cloudflare.workerContent",
        modifier,
        isRefreshing = state.isLoading || state.isRefreshing,
        onRefresh = viewModel::load,
    ) {
        when {
            content != null -> {
                item("summary") {
                    Text(
                        buildList {
                            add("${CloudflareFormat.bytes(content.byteCount.toLong())} returned")
                            content.contentType?.let { add(it.substringBefore(';')) }
                            if (content.truncatedForDisplay) add("showing the first 512 KB")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(chunks.size, key = { "chunk-$it" }) { index ->
                    CloudflareOpsMonospaceBlock(chunks[index].joinToString("\n"), testTag = "cloudflare.workerContent.block.$index")
                }
            }
            state.error != null -> item("error") { LoadFailure(state.error.orEmpty(), viewModel::load) }
            else -> item("loading") { CloudflareOpsLoading("Downloading Worker source…") }
        }
    }
}

private const val CONTENT_LINES_PER_BLOCK = 300

/** Port of iOS `CloudflareWorkerLiveTailView`. */
@Composable
fun CloudflareWorkerLiveTailScreen(
    viewModel: CloudflareWorkerLiveTailViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    CloudflareConfirmationHost(viewModel)
    val activity = LocalContext.current.findActivity()
    DisposableEffect(viewModel) {
        onDispose {
            // iOS stops on disappear; keep streaming across rotation only.
            if (activity?.isChangingConfigurations != true) viewModel.stop()
        }
    }
    val listState = rememberLazyListState()
    val following by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(state.lines.size) {
        if (following && state.lines.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }
    val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("cloudflare.workerTail.status"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (state.isConnected) CloudflareOpsColors.Green else CloudflareOpsColors.Amber, CircleShape),
            )
            Text(
                state.status,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${state.lines.size} EVENTS",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CloudflareOpsScreen("cloudflare.workerTail", Modifier.weight(1f), state = listState) {
            item("controls") {
                if (state.isConnected || state.isStarting) {
                    ThemedActionButton(
                        "STOP LIVE TAIL",
                        onClick = viewModel::stop,
                        tone = ThemedActionTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "cloudflare.workerTail.stop",
                    )
                } else {
                    CloudflareOpsActionButton(
                        "Start live tail",
                        Icons.Rounded.PlayArrow,
                        onClick = viewModel::requestStart,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "cloudflare.workerTail.start",
                    )
                }
            }
            banner?.let { current ->
                item("banner") { CloudflareActionResultBanner(current, onDismiss = viewModel::dismissBanner) }
            }
            when {
                state.error != null && state.lines.isEmpty() -> item("error") {
                    CloudflareOpsPanel {
                        CloudflareOpsEmptySection(Icons.Rounded.WarningAmber, "Live tail unavailable", state.error.orEmpty())
                    }
                }
                state.lines.isEmpty() -> item("empty") {
                    CloudflareOpsPanel {
                        if (state.isConnected) {
                            CloudflareOpsEmptySection(
                                Icons.Rounded.Timeline,
                                "Waiting for requests",
                                "Events will appear here as the Worker receives traffic.",
                            )
                        } else {
                            CloudflareOpsEmptySection(
                                Icons.Rounded.Stop,
                                "Live tail stopped",
                                "Start a live tail to stream this Worker's request logs, console output and exceptions.",
                            )
                        }
                    }
                }
                else -> items(state.lines, key = { it.id }) { line ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            timeFormatter.format(line.timestamp),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = CloudflareOpsColors.Orange.copy(alpha = 0.8f),
                        )
                        SelectionContainer {
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadFailure(message: String, onRetry: () -> Unit) {
    CloudflareOpsPanel {
        CloudflareOpsEmptySection(Icons.Rounded.WarningAmber, "Couldn't load", message)
        ThemedActionButton(
            "TRY AGAIN",
            onClick = onRetry,
            tone = ThemedActionTone.NEUTRAL,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
