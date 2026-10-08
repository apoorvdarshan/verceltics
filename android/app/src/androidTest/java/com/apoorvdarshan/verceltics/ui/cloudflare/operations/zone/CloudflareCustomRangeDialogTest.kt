package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CloudflareCustomRangeDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-09T12:34:56Z")

    @Test
    fun customTrafficWindowIsEditedAndAppliedToTheMinute() {
        var applied: Pair<Instant, Instant>? = null
        compose.setContent {
            VercelticsTheme {
                CloudflareCustomRangeDialog(
                    initialFrom = Instant.parse("2026-10-09T03:45:42Z"),
                    initialTo = Instant.parse("2026-10-09T04:17:09Z"),
                    onApply = { from, to ->
                        applied = from to to
                        null
                    },
                    onDismiss = {},
                    zone = zone,
                    now = { now },
                )
            }
        }

        compose.onNodeWithTag("cloudflare.zone.customRange").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.zone.customRange.from.time").assertTextContains("09:15")
        compose.onNodeWithTag("cloudflare.zone.customRange.to.time").assertTextContains("09:47")

        // The time picker opens and confirming keeps the minute-level value.
        compose.onNodeWithTag("cloudflare.zone.customRange.from.time").performClick()
        compose.onNodeWithTag("cloudflare.zone.customRange.timePicker").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.zone.customRange.timeConfirm").performClick()

        compose.onNodeWithTag("cloudflare.zone.customRange.apply").performClick()
        compose.runOnIdle {
            assertEquals(Instant.parse("2026-10-09T03:45:00Z") to Instant.parse("2026-10-09T04:17:00Z"), applied)
        }
    }

    @Test
    fun anEmptyWindowShowsTheIosErrorInsteadOfApplying() {
        var applied = false
        compose.setContent {
            VercelticsTheme {
                CloudflareCustomRangeDialog(
                    initialFrom = Instant.parse("2026-10-09T03:45:00Z"),
                    initialTo = Instant.parse("2026-10-09T03:45:30Z"),
                    onApply = { _, _ ->
                        applied = true
                        null
                    },
                    onDismiss = {},
                    zone = zone,
                    now = { now },
                )
            }
        }

        compose.onNodeWithTag("cloudflare.zone.customRange.apply").performClick()
        compose.onNodeWithTag("cloudflare.zone.customRange.error")
            .assertTextContains(CloudflareCustomRange.START_AFTER_END_MESSAGE)
        compose.runOnIdle { assertEquals(false, applied) }
    }
}
