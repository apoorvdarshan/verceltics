package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareD1Database
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareD1QueryResult
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareStorageApi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudflareD1DatabaseState(
    val database: CloudflareD1Database,
    val hasLoadedDetails: Boolean = false,
    val isRefreshing: Boolean = false,
    val queryResults: List<CloudflareD1QueryResult> = emptyList(),
    val didDelete: Boolean = false,
)

/** Port of iOS `CloudflareD1DatabaseViewModel`: metadata, the SQL console and deletion. */
class CloudflareD1DatabaseViewModel(
    private val api: CloudflareStorageApi,
    val accountId: String,
    databaseId: String,
    databaseName: String,
) : CloudflareOperationsViewModel() {
    val databaseId: String = databaseId

    private val _state = MutableStateFlow(
        CloudflareD1DatabaseState(
            database = CloudflareD1Database(databaseId, databaseName.ifBlank { "D1 database" }, null, null, null, null, null, null),
        ),
    )
    val state: StateFlow<CloudflareD1DatabaseState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        if (loadJob?.isActive == true) return
        _state.update { it.copy(isRefreshing = true) }
        loadJob = viewModelScope.launch {
            try {
                val database = api.fetchD1Database(accountId, databaseId)
                _state.update { it.copy(database = database, hasLoadedDetails = true, isRefreshing = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(isRefreshing = false) }
                showError(error)
            }
        }
    }

    /** iOS "Run this SQL statement?" — D1 accepts reads and writes, so every statement is confirmed. */
    fun requestRun(sql: String) {
        val statement = sql.trim()
        if (statement.isEmpty()) return
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Run this SQL statement?",
                message = "D1 accepts both read and write SQL here. Review the statement before running it.\n\n" +
                    preview(statement),
                confirmLabel = "Run SQL",
                resourceId = databaseId,
                workingId = QUERY_WORKING_ID,
            ),
        ) { confirmation ->
            loadJob?.cancel()
            _state.update { it.copy(isRefreshing = false) }
            val results = try {
                api.queryD1Database(accountId, databaseId, statement, confirmation = confirmation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(queryResults = emptyList()) }
                throw error
            }
            _state.update { it.copy(queryResults = results) }
            val rows = results.sumOf { it.rows.size }
            showSuccess(if (rows == 1) "Query returned 1 row." else "Query returned $rows rows.")
            if (results.any { it.meta?.changedDatabase == true }) {
                try {
                    val database = api.fetchD1Database(accountId, databaseId)
                    _state.update { it.copy(database = database, hasLoadedDetails = true) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // The statement already committed; never invite running a non-idempotent query twice.
                    showSuccess("Query succeeded, but database metadata could not refresh: ${cloudflareUserMessage(error)}")
                }
            }
        }
    }

    fun requestDelete() {
        val name = _state.value.database.name
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Delete this D1 database?",
                message = "$name and all of its data will be permanently deleted.",
                confirmLabel = "Delete Database",
                resourceId = databaseId,
                destructive = true,
                workingId = DELETE_WORKING_ID,
            ),
        ) { confirmation ->
            loadJob?.cancel()
            api.deleteD1Database(accountId, databaseId, confirmation)
            _state.update { it.copy(didDelete = true, isRefreshing = false) }
        }
    }

    private fun preview(statement: String): String =
        if (statement.length > 600) statement.take(600) + "…" else statement

    companion object {
        const val QUERY_WORKING_ID: String = "d1-query"
        const val DELETE_WORKING_ID: String = "d1-delete"
        const val DEFAULT_SQL: String = "SELECT name, type FROM sqlite_schema WHERE type IN ('table', 'view') ORDER BY name;"
    }
}
