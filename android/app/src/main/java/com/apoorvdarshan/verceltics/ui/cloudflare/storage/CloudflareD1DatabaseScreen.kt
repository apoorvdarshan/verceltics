package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareD1QueryResult
import com.apoorvdarshan.verceltics.data.cloudflare.storage.cloudflareStorageDisplayValue
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsActionButton
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDetailRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsHero
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsTextField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice

@Composable
fun CloudflareD1DatabaseRoute(
    client: CloudflareRestClient,
    accountId: String,
    databaseId: String,
    databaseName: String,
    context: CloudflareOperationsContext,
    routeKey: String,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(key = routeKey) { CloudflareD1DatabaseViewModel(storageApi(client), accountId, databaseId, databaseName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloudflareStorageRefreshEffect(context.refreshSignal) { viewModel.load() }
    LaunchedEffect(state.didDelete) { if (state.didDelete) context.close() }
    CloudflareD1DatabaseScreen(viewModel, modifier)
}

/** iOS `CloudflareD1DatabaseView`: metadata, SQL console with results, and deletion. */
@Composable
fun CloudflareD1DatabaseScreen(viewModel: CloudflareD1DatabaseViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var sql by rememberSaveable { mutableStateOf(CloudflareD1DatabaseViewModel.DEFAULT_SQL) }
    val database = state.database
    CloudflareConfirmationHost(viewModel)

    CloudflareOpsScreen("cloudflare.storage.d1Screen", modifier) {
        item("hero") {
            CloudflareOpsHero(
                title = database.name,
                subtitle = "Cloudflare D1 · SQLite",
                icon = Icons.Rounded.TableChart,
                status = if (state.isRefreshing && !state.hasLoadedDetails) "LOADING" else "READY",
                statusColor = CloudflareOpsColors.Green,
            )
        }
        item("metadata") {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Database", Icons.Rounded.Info)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Database ID", database.uuid, monospace = true, copyable = true)
                CloudflareOpsDetailRow("Tables", database.numberOfTables?.let { CloudflareFormat.grouped(it.toLong()) } ?: "Not returned")
                CloudflareOpsDetailRow("File size", database.fileSize?.let(CloudflareFormat::bytes) ?: "Not returned")
                CloudflareOpsDetailRow("Jurisdiction", database.jurisdiction?.uppercase() ?: "Automatic")
                CloudflareOpsDetailRow(
                    "Read replication",
                    database.readReplicationMode?.replaceFirstChar { it.uppercase() } ?: "Not returned",
                )
                database.version?.let { CloudflareOpsDetailRow("Version", it) }
                database.createdDate?.let { CloudflareOpsDetailRow("Created", CloudflareDates.formatDateTime(it)) }
            }
        }
        item("notice") { CloudflareWriteNotice() }
        banner?.let { item("banner") { CloudflareActionResultBanner(it, viewModel::dismissBanner) } }
        item("console") {
            CloudflareOpsPanel(testTag = "cloudflare.storage.d1.console") {
                CloudflareOpsSectionHeader("SQL Console", Icons.Rounded.Terminal)
                CloudflareOpsDivider()
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CloudflareOpsTextField(
                        value = sql,
                        onValueChange = { sql = it },
                        label = "SQL",
                        singleLine = false,
                        minLines = 5,
                        monospace = true,
                        testTag = "cloudflare.storage.d1.sql",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Queries run directly against this live database.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        CloudflareOpsActionButton(
                            title = "Run SQL",
                            icon = Icons.Rounded.PlayArrow,
                            onClick = { viewModel.requestRun(sql) },
                            enabled = sql.isNotBlank(),
                            working = CloudflareD1DatabaseViewModel.QUERY_WORKING_ID in working,
                            testTag = "cloudflare.storage.d1.run",
                        )
                    }
                }
            }
        }
        state.queryResults.forEachIndexed { index, result ->
            item("result-$index") {
                D1ResultPanel(
                    title = if (state.queryResults.size == 1) "Query Result" else "Result ${index + 1}",
                    result = result,
                )
            }
        }
        item("danger") {
            CloudflareOpsPanel(accent = 0.06f) {
                CloudflareOpsSectionHeader("Danger Zone", Icons.Rounded.WarningAmber)
                CloudflareOpsDivider()
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Delete database", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "This permanently removes its schema and every row.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    CloudflareOpsActionButton(
                        title = "Delete",
                        icon = Icons.Rounded.Delete,
                        onClick = viewModel::requestDelete,
                        destructive = true,
                        working = CloudflareD1DatabaseViewModel.DELETE_WORKING_ID in working,
                        testTag = "cloudflare.storage.d1.delete",
                    )
                }
            }
        }
    }
}

@Composable
private fun D1ResultPanel(title: String, result: CloudflareD1QueryResult) {
    CloudflareOpsPanel(testTag = "cloudflare.storage.d1.result") {
        CloudflareOpsSectionHeader(title, Icons.Rounded.TableChart, count = result.rows.size)
        CloudflareOpsDivider()
        if (result.rows.isEmpty()) {
            CloudflareOpsEmptySection(
                icon = if (result.success) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                title = if (result.success) "Statement completed" else "Statement failed",
                message = result.meta?.summary ?: CloudflareD1QueryResult.NO_METADATA,
            )
        } else {
            D1ResultTable(result)
        }
        result.meta?.let { meta ->
            CloudflareOpsDivider()
            Text(
                meta.summary,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Pages through every row of a D1 result. The rows are already in memory (D1 returns the whole
 * statement result), so only one page is composed at a time and long cell values are trimmed for
 * display, keeping huge results responsive without hiding any row.
 */
object CloudflareD1ResultPaging {
    const val PAGE_SIZE: Int = 100

    /** Long cells are trimmed for display only; Copy/selection still shows this bounded text. */
    const val MAXIMUM_CELL_CHARACTERS: Int = 2_000

    fun pageCount(rowCount: Int, pageSize: Int = PAGE_SIZE): Int =
        if (rowCount <= 0) 1 else (rowCount + pageSize - 1) / pageSize

    fun clampPage(page: Int, rowCount: Int, pageSize: Int = PAGE_SIZE): Int =
        page.coerceIn(0, pageCount(rowCount, pageSize) - 1)

    fun <T> pageRows(rows: List<T>, page: Int, pageSize: Int = PAGE_SIZE): List<T> {
        val safePage = clampPage(page, rows.size, pageSize)
        val start = safePage * pageSize
        return if (start >= rows.size) emptyList() else rows.subList(start, minOf(rows.size, start + pageSize))
    }

    /** "Rows 101–200 of 1,234". */
    fun label(page: Int, rowCount: Int, pageSize: Int = PAGE_SIZE): String {
        if (rowCount == 0) return "No rows"
        val safePage = clampPage(page, rowCount, pageSize)
        val first = safePage * pageSize + 1
        val last = minOf(rowCount, first + pageSize - 1)
        return "Rows ${"%,d".format(first)}–${"%,d".format(last)} of ${"%,d".format(rowCount)}"
    }

    fun displayCell(value: String): String =
        if (value.length > MAXIMUM_CELL_CHARACTERS) value.take(MAXIMUM_CELL_CHARACTERS) + "…" else value
}

/** iOS `CloudflareD1ResultTable`: sorted columns, NULL for missing cells, horizontally scrollable. */
@Composable
private fun D1ResultTable(result: CloudflareD1QueryResult) {
    val columns = result.columns
    var page by rememberSaveable(result) { mutableIntStateOf(0) }
    val pageCount = CloudflareD1ResultPaging.pageCount(result.rows.size)
    val rows = CloudflareD1ResultPaging.pageRows(result.rows, page)
    if (pageCount > 1) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { page = CloudflareD1ResultPaging.clampPage(page - 1, result.rows.size) },
                enabled = page > 0,
                modifier = Modifier.testTag("cloudflare.storage.d1.previousPage"),
            ) { Text("Previous") }
            Text(
                CloudflareD1ResultPaging.label(page, result.rows.size),
                modifier = Modifier
                    .weight(1f)
                    .testTag("cloudflare.storage.d1.pageLabel"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = { page = CloudflareD1ResultPaging.clampPage(page + 1, result.rows.size) },
                enabled = page < pageCount - 1,
                modifier = Modifier.testTag("cloudflare.storage.d1.nextPage"),
            ) { Text("Next") }
        }
        CloudflareOpsDivider()
    }
    SelectionContainer {
        Column(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                columns.forEach { column ->
                    Text(
                        column.uppercase(),
                        modifier = Modifier.widthIn(min = 100.dp, max = 260.dp),
                        color = CloudflareOpsColors.Orange,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            rows.forEach { row ->
                CloudflareOpsDivider()
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    columns.forEach { column ->
                        Text(
                            CloudflareD1ResultPaging.displayCell(cloudflareStorageDisplayValue(row[column])),
                            modifier = Modifier.widthIn(min = 100.dp, max = 260.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
