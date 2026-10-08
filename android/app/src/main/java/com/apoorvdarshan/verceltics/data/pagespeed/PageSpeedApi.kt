package com.apoorvdarshan.verceltics.data.pagespeed

import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Native PageSpeed + CrUX orchestration matching the iOS mobile-required partial-data contract. */
class PageSpeedApi(
    private val transport: PageSpeedHttpTransport = SecurePageSpeedHttpTransport(),
    private val jsonParser: PageSpeedJsonParser = AndroidPageSpeedJsonParser(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val includeFullReport: Boolean = true,
) {
    /**
     * Runs the mobile/desktop Lighthouse audits and current CrUX lookup for the summary. With
     * [includeFullReport] the same responses also build the full report, plus one CrUX History
     * request, so the Pro breakdown never re-runs a slow Lighthouse audit.
     */
    fun newSnapshotCall(credentials: PageSpeedCredentials): CancelableCall<PageSpeedFetchResult> =
        PageSpeedSnapshotCall(credentials, transport, jsonParser, nowMillis, includeFullReport)
}

/** Collects report pieces; a malformed optional section becomes a warning, never a failed audit. */
private class PageSpeedReportBuilder {
    val strategies = mutableListOf<PageSpeedStrategyReport>()
    val raw = LinkedHashMap<String, com.apoorvdarshan.verceltics.data.network.ProviderJsonValue>()
    val warnings = mutableListOf<String>()
    var crux: PageSpeedCruxRecord? = null
    var history: PageSpeedCruxHistory? = null

    fun addLighthouse(strategy: PageSpeedStrategy, body: ByteArray) {
        try {
            val tree = PageSpeedReportParser.parseJson(body)
            raw["pagespeed.${strategy.wireValue}"] = tree
            strategies += PageSpeedReportParser.lighthouse(tree, strategy)
        } catch (_: Exception) {
            warnings += "The full ${strategy.label.lowercase()} Lighthouse report could not be read."
        }
    }

    fun addCrux(body: ByteArray) {
        try {
            val tree = PageSpeedReportParser.parseJson(body)
            raw["crux.current"] = tree
            crux = PageSpeedReportParser.cruxRecord(tree)
        } catch (_: Exception) {
            warnings += "Current Chrome UX field distributions could not be read."
        }
    }

    /** Throws [PageSpeedResponseFormatException] so the request reports an invalid response. */
    fun addHistory(body: ByteArray) {
        val tree = PageSpeedReportParser.parseJson(body)
        history = PageSpeedReportParser.cruxHistory(tree)
        raw["crux.history"] = tree
    }

    fun build(fetchedAtMillis: Long): PageSpeedReport = PageSpeedReport(
        strategies = strategies.sortedBy { it.strategy.ordinal },
        crux = crux,
        cruxHistory = history,
        rawResponses = raw.toMap(),
        warnings = warnings.toList(),
        fetchedAtMillis = fetchedAtMillis,
    )
}

private class PageSpeedSnapshotCall(
    private val credentials: PageSpeedCredentials,
    private val transport: PageSpeedHttpTransport,
    private val jsonParser: PageSpeedJsonParser,
    private val nowMillis: () -> Long,
    includeFullReport: Boolean,
) : CancelableCall<PageSpeedFetchResult> {
    private val report: PageSpeedReportBuilder? = if (includeFullReport) PageSpeedReportBuilder() else null
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeCall = AtomicReference<CancelableCall<HttpResponse>?>()

    override fun execute(): PageSpeedFetchResult {
        check(started.compareAndSet(false, true)) { "A PageSpeed snapshot call can only execute once." }
        throwIfCancelled()

        val mobile = try {
            executeInsights(PageSpeedStrategy.MOBILE)
        } catch (error: PageSpeedRequestFailure) {
            return PageSpeedFetchResult.Failure(error.failure)
        }

        val metrics = mobile.toMutableList()
        val warnings = mutableListOf<String>()
        var desktopState = PageSpeedSourceState.AVAILABLE
        var cruxState = PageSpeedSourceState.AVAILABLE

        try {
            metrics += executeInsights(PageSpeedStrategy.DESKTOP)
        } catch (error: PageSpeedRequestFailure) {
            desktopState = PageSpeedSourceState.UNAVAILABLE
            warnings += "Desktop PageSpeed data is unavailable: ${error.failure.message}"
        }

        try {
            metrics += executeCrux()
        } catch (error: PageSpeedRequestFailure) {
            cruxState = PageSpeedSourceState.UNAVAILABLE
            warnings += "Chrome UX field data is unavailable: ${error.failure.message}"
        }

        report?.let { builder ->
            try {
                executeCruxHistory(builder)
            } catch (error: PageSpeedRequestFailure) {
                builder.warnings += "Chrome UX history is unavailable: ${error.failure.message}"
            }
        }

        throwIfCancelled()
        val performance = metrics
            .filter {
                it.key == "pagespeed.mobile.performance" ||
                    it.key == "pagespeed.desktop.performance"
            }
            .minOfOrNull(PageSpeedMetric::value)
        val status = when {
            performance == null -> "Audited"
            performance >= 90 -> "Good"
            performance >= 50 -> "Needs work"
            else -> "Poor"
        }
        val availability = PageSpeedSourceAvailability(
            desktop = desktopState,
            crux = cruxState,
        )
        val snapshot = PageSpeedSnapshot(
            siteUrl = credentials.siteUrl,
            siteName = checkNotNull(credentials.siteUrl.host),
            status = status,
            metrics = metrics,
            fetchedAtMillis = nowMillis(),
            availability = availability,
            warnings = warnings,
        )
        val fullReport = report?.build(snapshot.fetchedAtMillis)
        return if (availability.isPartial) {
            PageSpeedFetchResult.Partial(snapshot, fullReport)
        } else {
            PageSpeedFetchResult.Complete(snapshot, fullReport)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeCall.getAndSet(null)?.cancel()
    }

    private fun executeInsights(strategy: PageSpeedStrategy): List<PageSpeedMetric> =
        executeRequest(
            call = transport.newInsightsCall(credentials, strategy),
            operation = "load ${strategy.wireValue} PageSpeed data",
        ) { body ->
            jsonParser.parseInsights(body, strategy).also { report?.addLighthouse(strategy, body) }
        }

    private fun executeCrux(): List<PageSpeedMetric> = executeRequest(
        call = transport.newCruxCall(credentials),
        operation = "load Chrome UX field data",
    ) { body -> jsonParser.parseCrux(body).also { report?.addCrux(body) } }

    private fun executeCruxHistory(builder: PageSpeedReportBuilder) = executeRequest(
        call = transport.newCruxHistoryCall(credentials),
        operation = "load Chrome UX history",
    ) { body -> builder.addHistory(body) }

    private fun <T> executeRequest(
        call: CancelableCall<HttpResponse>,
        operation: String,
        parse: (ByteArray) -> T,
    ): T {
        throwIfCancelled()
        activeCall.set(call)
        if (cancelled.get()) {
            activeCall.compareAndSet(call, null)
            call.cancel()
            throw CancellationException("The PageSpeed request was cancelled.")
        }
        return try {
            val response = call.execute()
            throwIfCancelled()
            if (response.statusCode !in 200..299) {
                response.takeBody().fill(0)
                throw PageSpeedRequestFailure(httpFailure(response.statusCode, operation))
            }
            val body = response.takeBody()
            try {
                parse(body)
            } catch (error: PageSpeedResponseFormatException) {
                throw PageSpeedRequestFailure(
                    PageSpeedFailure(
                        kind = PageSpeedFailureKind.INVALID_RESPONSE,
                        message = error.message ?: "The provider returned an invalid response.",
                    ),
                )
            } finally {
                body.fill(0)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PageSpeedRequestFailure) {
            throw error
        } catch (_: IOException) {
            throw PageSpeedRequestFailure(
                PageSpeedFailure(
                    kind = PageSpeedFailureKind.NETWORK,
                    message = "The provider could not be reached. Check your connection and try again.",
                ),
            )
        } catch (_: IllegalArgumentException) {
            throw PageSpeedRequestFailure(
                PageSpeedFailure(
                    kind = PageSpeedFailureKind.CONFIGURATION,
                    message = "The PageSpeed request configuration is invalid.",
                ),
            )
        } catch (_: RuntimeException) {
            throw PageSpeedRequestFailure(
                PageSpeedFailure(
                    kind = PageSpeedFailureKind.INVALID_RESPONSE,
                    message = "The provider returned an invalid response.",
                ),
            )
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun httpFailure(statusCode: Int, operation: String): PageSpeedFailure {
        val kind: PageSpeedFailureKind
        val message: String
        when (statusCode) {
            400 -> {
                kind = PageSpeedFailureKind.CONFIGURATION
                message = "Google rejected the site URL or request configuration."
            }
            401, 403 -> {
                kind = PageSpeedFailureKind.AUTHENTICATION
                message = "Google rejected this API key or its API access."
            }
            404 -> {
                kind = PageSpeedFailureKind.NOT_FOUND
                message = "The requested provider data is unavailable for this page."
            }
            408 -> {
                kind = PageSpeedFailureKind.NETWORK
                message = "Google timed out while trying to $operation."
            }
            429 -> {
                kind = PageSpeedFailureKind.RATE_LIMITED
                message = "Google is rate limiting requests. Please try again shortly."
            }
            in 500..599 -> {
                kind = PageSpeedFailureKind.TEMPORARY
                message = "Google's performance services are temporarily unavailable."
            }
            else -> {
                kind = PageSpeedFailureKind.TEMPORARY
                message = "Unable to $operation (HTTP $statusCode)."
            }
        }
        return PageSpeedFailure(kind = kind, message = message, statusCode = statusCode)
    }

    private fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("The PageSpeed request was cancelled.")
    }
}

private class PageSpeedRequestFailure(val failure: PageSpeedFailure) : Exception(failure.message) {
    override fun toString(): String =
        "PageSpeedRequestFailure(kind=${failure.kind}, statusCode=${failure.statusCode})"
}
