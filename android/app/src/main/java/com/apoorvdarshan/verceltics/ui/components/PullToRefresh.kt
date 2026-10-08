package com.apoorvdarshan.verceltics.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared Material 3 pull-to-refresh container, the Android counterpart of SwiftUI `.refreshable`.
 *
 * The scrollable child (usually a `LazyColumn`) must fill this box so the nested-scroll gesture
 * reaches it. When [enabled] is false the content renders in a plain box with no gesture, so a
 * screen keeps one layout whether or not refreshing is currently possible.
 *
 * Like iOS, the indicator stays visible only for refreshes the user pulled for: after a pull it
 * waits for [isRefreshing] to start (briefly) and then to finish. Background refreshes that also
 * flip [isRefreshing] do not pop the indicator unless [showsExternalRefreshes] is true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showsExternalRefreshes: Boolean = false,
    testTag: String? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val taggedModifier = if (testTag == null) modifier else modifier.testTag(testTag)
    if (!enabled) {
        Box(modifier = taggedModifier, contentAlignment = contentAlignment, content = content)
        return
    }

    val haptic = LocalHapticFeedback.current
    val state = rememberPullToRefreshState()
    val currentIsRefreshing by rememberUpdatedState(isRefreshing)
    var isPullRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(isPullRefreshing) {
        if (!isPullRefreshing) return@LaunchedEffect
        // The owner may start its work asynchronously; give it a moment to report it.
        withTimeoutOrNull(PULL_REFRESH_START_TIMEOUT_MILLIS) {
            snapshotFlow { currentIsRefreshing }.first { it }
        }
        snapshotFlow { currentIsRefreshing }.first { !it }
        isPullRefreshing = false
    }
    val showsIndicator = isPullRefreshing || (showsExternalRefreshes && isRefreshing)

    PullToRefreshBox(
        isRefreshing = showsIndicator,
        onRefresh = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            isPullRefreshing = true
            onRefresh()
        },
        modifier = taggedModifier,
        state = state,
        contentAlignment = contentAlignment,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = showsIndicator,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(if (testTag == null) Modifier else Modifier.testTag("$testTag.indicator")),
                containerColor = MaterialTheme.colorScheme.surface,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        content = content,
    )
}

/** Longest wait for a pulled refresh to report that it started before the indicator hides. */
private const val PULL_REFRESH_START_TIMEOUT_MILLIS = 1_000L
