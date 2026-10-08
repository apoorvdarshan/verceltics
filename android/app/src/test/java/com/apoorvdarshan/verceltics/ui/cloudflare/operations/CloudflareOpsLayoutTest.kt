package com.apoorvdarshan.verceltics.ui.cloudflare.operations

import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.hosting.ProviderContentMetrics
import com.apoorvdarshan.verceltics.ui.hosting.ProviderLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudflareOpsLayoutTest {
    @Test
    fun phonesKeepTheTwoColumnMetricGridAndEdgePadding() {
        assertEquals(2, cloudflareOpsMetricColumns(null))
        val phone = ProviderContentMetrics.of(411.dp, ProviderLayout.CatalogMaxWidth, compactPadding = 18.dp)
        assertEquals(18.dp, phone.horizontalPadding)
        assertEquals(2, cloudflareOpsMetricColumns(phone))
    }

    @Test
    fun wideWindowsFitMoreMetricColumnsInsideTheCappedWidth() {
        // 1000 dp tablet: 24 dp padding, 952 dp content -> four 200 dp columns with 10 dp gaps.
        val tablet = ProviderContentMetrics.of(1000.dp, ProviderLayout.CatalogMaxWidth, compactPadding = 18.dp)
        assertEquals(24.dp, tablet.horizontalPadding)
        assertEquals(4, cloudflareOpsMetricColumns(tablet))

        // A very wide window centers the 1080 dp catalog width (iOS `appContentWidth`).
        val desktop = ProviderContentMetrics.of(1400.dp, ProviderLayout.CatalogMaxWidth, compactPadding = 18.dp)
        assertEquals(160.dp, desktop.horizontalPadding)
        assertEquals(5, cloudflareOpsMetricColumns(desktop))
    }

    @Test
    fun storageTwoPaneMatchesIosMinimumPaneWidths() {
        val portraitTablet = ProviderContentMetrics.of(700.dp, ProviderLayout.DashboardMaxWidth, compactPadding = 18.dp)
        assertEquals(false, portraitTablet.fitsTwoPanes(380.dp, 380.dp))
        val landscapeTablet = ProviderContentMetrics.of(1024.dp, ProviderLayout.DashboardMaxWidth, compactPadding = 18.dp)
        assertEquals(true, landscapeTablet.fitsTwoPanes(380.dp, 380.dp))
    }
}
