package com.apoorvdarshan.verceltics.ui.sites

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SiteTimelineChartScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun timelineChartLabelsTheYAxisAndScrubsToTheNearestPoint() {
        var selected by mutableStateOf<Int?>(null)
        composeRule.setContent {
            VercelticsTheme(darkTheme = false) {
                SiteTimelineChart(
                    points = listOf(
                        TimelineChartPoint("Aug 1", 12.0),
                        TimelineChartPoint("Aug 2", 87.0),
                        TimelineChartPoint("Aug 3", 143.0),
                    ),
                    accent = Color(0xFF4FBD7A),
                    formatValue = ::formatCompactAxisValue,
                    description = "Visitors chart",
                    modifier = Modifier.fillMaxWidth(),
                    selectedIndex = selected,
                    onSelectedIndexChange = { selected = it },
                    testTag = "chart",
                )
            }
        }

        composeRule.onNodeWithTag("chart.yAxis").assertIsDisplayed()
        listOf("0", "50", "100", "150").forEach { tick ->
            composeRule.onNodeWithText(tick).assertExists()
        }
        composeRule.onNodeWithText("Aug 1").assertExists()
        composeRule.onNodeWithText("Aug 3").assertExists()

        composeRule.onNode(hasContentDescription("Visitors chart")).performTouchInput { click(centerRight) }
        composeRule.runOnIdle { assertEquals(2, selected) }
        composeRule.onNode(hasContentDescription("Visitors chart"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Aug 3, 143"))
    }
}
