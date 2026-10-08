package com.apoorvdarshan.verceltics.ui.registrar

import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import kotlin.math.floor

/** Resolved layout for one registrar screen at its available width. */
internal data class RegistrarScreenLayout(
    /** iOS regular horizontal size class: the content uses tablet padding, columns and widths. */
    val isRegular: Boolean,
    /** Horizontal padding on each side, which also centers content inside its maximum width. */
    val sidePaddingDp: Float,
    /** Domain-card columns on the dashboard, or side-by-side panels on a domain detail. */
    val columns: Int,
)

/**
 * Port of iOS `AppLayout` for the registrar dashboard and domain detail. Phones (< 600 dp) keep
 * the single-column layout; wider windows get regular padding, an adaptive domain grid (340 dp
 * minimum columns, so two columns from about 744 dp), three-column stats and a two-column detail.
 */
internal object RegistrarLayout {
    const val REGULAR_MIN_WIDTH_DP: Float = 600f
    const val DASHBOARD_MAX_WIDTH_DP: Float = 1180f
    const val DETAIL_MAX_WIDTH_DP: Float = 920f
    const val COMPACT_PAGE_PADDING_DP: Float = 18f
    const val REGULAR_PAGE_PADDING_DP: Float = 24f
    const val DOMAIN_MIN_COLUMN_DP: Float = 340f
    const val DOMAIN_SPACING_DP: Float = 14f
    const val DETAIL_MIN_COLUMN_DP: Float = 340f
    const val DETAIL_SPACING_DP: Float = 16f

    fun dashboard(availableWidthDp: Float): RegistrarScreenLayout = resolve(
        availableWidthDp = availableWidthDp,
        maxContentWidthDp = DASHBOARD_MAX_WIDTH_DP,
        minColumnDp = DOMAIN_MIN_COLUMN_DP,
        spacingDp = DOMAIN_SPACING_DP,
        maxColumns = Int.MAX_VALUE,
    )

    /** Properties and nameservers sit side by side once two 340 dp panels fit. */
    fun detail(availableWidthDp: Float): RegistrarScreenLayout = resolve(
        availableWidthDp = availableWidthDp,
        maxContentWidthDp = DETAIL_MAX_WIDTH_DP,
        minColumnDp = DETAIL_MIN_COLUMN_DP,
        spacingDp = DETAIL_SPACING_DP,
        maxColumns = 2,
    )

    private fun resolve(
        availableWidthDp: Float,
        maxContentWidthDp: Float,
        minColumnDp: Float,
        spacingDp: Float,
        maxColumns: Int,
    ): RegistrarScreenLayout {
        if (availableWidthDp < REGULAR_MIN_WIDTH_DP) {
            return RegistrarScreenLayout(isRegular = false, sidePaddingDp = COMPACT_PAGE_PADDING_DP, columns = 1)
        }
        val frame = minOf(availableWidthDp, maxContentWidthDp)
        val content = frame - 2 * REGULAR_PAGE_PADDING_DP
        val columns = floor((content + spacingDp) / (minColumnDp + spacingDp)).toInt().coerceIn(1, maxColumns)
        return RegistrarScreenLayout(
            isRegular = true,
            sidePaddingDp = (availableWidthDp - frame) / 2 + REGULAR_PAGE_PADDING_DP,
            columns = columns,
        )
    }
}

/** Title, message and confirm label for a destructive registrar removal. */
internal data class RegistrarRemovalCopy(
    val title: String,
    val message: String,
    val confirmText: String,
    val dismissText: String,
)

/** iOS `RegistrarAccountMenu` removal copy, adapted to one registrar's accounts. */
internal fun registrarRemovalCopy(
    provider: RegistrarProvider,
    confirmation: RegistrarRemovalConfirmation,
): RegistrarRemovalCopy = when {
    confirmation.scope == RegistrarRemovalScope.ALL_ACCOUNTS && confirmation.accountCount > 1 -> RegistrarRemovalCopy(
        title = "Remove all ${provider.displayName} accounts?",
        message = "The encrypted credentials and saved domain portfolios of all ${confirmation.accountCount} " +
            "${provider.displayName} accounts will be removed from this device only.",
        confirmText = "REMOVE ALL",
        dismissText = "KEEP ACCOUNTS",
    )
    confirmation.removesEveryAccount -> RegistrarRemovalCopy(
        title = "Disconnect ${provider.displayName}?",
        message = "The encrypted ${provider.displayName} credentials and saved domain portfolio will be removed from this device.",
        confirmText = "DISCONNECT",
        dismissText = "KEEP ACCOUNT",
    )
    else -> RegistrarRemovalCopy(
        title = "Remove ${confirmation.accountName?.takeIf(String::isNotBlank) ?: "this account"}?",
        message = "The encrypted credentials and saved domain portfolio of this ${provider.displayName} account will be " +
            "removed from this device only. Your other ${provider.displayName} accounts stay connected.",
        confirmText = "REMOVE ACCOUNT",
        dismissText = "KEEP ACCOUNT",
    )
}

/** The dashboard's destructive button: Remove Current with several accounts, else Disconnect. */
internal fun registrarRemoveCurrentLabel(provider: RegistrarProvider, accountCount: Int): String =
    if (accountCount > 1) "REMOVE CURRENT ACCOUNT" else "Disconnect ${provider.displayName}".uppercase()

/** iOS empty-nameserver hint; the Complete API explorer is available on Android too. */
internal const val REGISTRAR_EMPTY_NAMESERVERS_HINT: String =
    "The list endpoint did not include nameservers. Open the Complete API explorer for the domain detail or DNS route."
