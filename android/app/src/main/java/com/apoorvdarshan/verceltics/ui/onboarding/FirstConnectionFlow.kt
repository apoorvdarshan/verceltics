package com.apoorvdarshan.verceltics.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.R
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.domain.Workspace
import com.apoorvdarshan.verceltics.ui.screens.ConnectionCatalog

/**
 * First-connection root shown instead of the tab shell while nothing is connected, like iOS
 * `FirstConnectionFlow`: the one-time welcome, then the full-screen Hosting / Registrars / Sites
 * connect flow. Choosing a provider hands off to the shell's provider route.
 */
@Composable
fun FirstConnectionFlow(
    presentation: FirstLaunchPresentation,
    onContinue: () -> Unit,
    onConnectProvider: (IntegrationProvider) -> Unit,
    modifier: Modifier = Modifier,
    onPreviewSampleData: (() -> Unit)? = null,
) {
    AnimatedContent(
        targetState = presentation,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            if (initialState == FirstLaunchPresentation.WELCOME &&
                targetState == FirstLaunchPresentation.CONNECT
            ) {
                (fadeIn(tween(350)) + slideInHorizontally(tween(350)) { it / 3 }) togetherWith
                    (fadeOut(tween(250)) + slideOutHorizontally(tween(350)) { -it / 3 })
            } else {
                fadeIn(tween(220)) togetherWith fadeOut(tween(180))
            }
        },
        label = "firstLaunch.presentation",
    ) { target ->
        when (target) {
            FirstLaunchPresentation.LOADING -> FirstLaunchLoadingScreen()
            FirstLaunchPresentation.WELCOME -> FirstConnectionWelcomeScreen(onContinue = onContinue)
            FirstLaunchPresentation.CONNECT, FirstLaunchPresentation.SHELL -> FirstConnectionCatalogScreen(
                onConnectProvider = onConnectProvider,
                onPreviewSampleData = onPreviewSampleData,
            )
        }
    }
}

/** Full-screen connect flow, the Android counterpart of the iOS `LoginView` provider catalog. */
@Composable
fun FirstConnectionCatalogScreen(
    onConnectProvider: (IntegrationProvider) -> Unit,
    modifier: Modifier = Modifier,
    onPreviewSampleData: (() -> Unit)? = null,
) {
    var categoryId by rememberSaveable { mutableStateOf(Workspace.HOSTING.id) }
    val category = Workspace.entries.firstOrNull { it.id == categoryId } ?: Workspace.HOSTING
    ConnectionCatalog(
        selectedCategory = category,
        onCategorySelected = { categoryId = it.id },
        focusRequestId = 0,
        connectedProviderIds = emptySet(),
        onProviderSelected = onConnectProvider,
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .testTag("firstLaunch.connect"),
        title = stringResource(R.string.onboarding_connect_title),
        subtitle = stringResource(R.string.onboarding_connect_subtitle),
        showsDragHandle = false,
        footer = onPreviewSampleData?.let { preview ->
            {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(
                        onClick = preview,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("firstLaunch.sampleData"),
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_sample_data),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        },
    )
}

/** Shown only while saved connections are read, so connected users never see the welcome. */
@Composable
fun FirstLaunchLoadingScreen(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.onboarding_loading)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .clearAndSetSemantics {
                contentDescription = label
                testTag = "firstLaunch.loading"
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(colorResource(R.color.launcher_black)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(96.dp),
                )
            }
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
            )
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
