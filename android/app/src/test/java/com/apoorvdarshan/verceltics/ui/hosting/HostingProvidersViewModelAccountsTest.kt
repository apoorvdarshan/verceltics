package com.apoorvdarshan.verceltics.ui.hosting

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostingProvidersViewModelAccountsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun restoreLoadsTheAccountMenuForEveryConnectedProvider() = runTest(dispatcher) {
        val gateway = AccountsGateway(mutableListOf("alpha", "beta"), active = "beta")
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        val render = viewModel.uiState.value.provider("render")
        assertEquals(listOf("Alpha", "Beta"), render.accounts.map { it.displayName })
        assertEquals("beta", render.activeAccountId)
        assertEquals("Beta", render.dashboard?.account?.displayName)
        assertTrue(viewModel.uiState.value.provider("railway").accounts.isEmpty())
    }

    @Test
    fun addingAnAccountShowsTheSecureFormWithoutDisconnectingAndConnectingAddsIt() = runTest(dispatcher) {
        val gateway = AccountsGateway(mutableListOf("alpha"), active = "alpha")
        val savedState = SavedStateHandle()
        val viewModel = HostingProvidersViewModel(gateway, savedState)
        advanceUntilIdle()
        viewModel.setRouteVisible("render", true)
        // Opening a stale route refreshes first; the menu is disabled until that finishes.
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.requiresSecureWindow)

        viewModel.startAddingAccount("render")
        val adding = viewModel.uiState.value.provider("render")
        assertTrue(adding.isAddingAccount)
        assertTrue(adding.showsConnectionForm)
        assertEquals(HostingConnectionStatus.CONNECTED, adding.status)
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        assertEquals(true, savedState.get<Boolean>(HostingProvidersViewModel.addingAccountKey("render")))

        // Back (or the cancel button) returns to the saved account untouched.
        assertTrue(viewModel.handleBack("render"))
        assertFalse(viewModel.uiState.value.provider("render").isAddingAccount)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        assertTrue(gateway.removed.isEmpty() && gateway.removedAll.isEmpty())

        viewModel.startAddingAccount("render")
        gateway.nextConnectName = "gamma"
        viewModel.connect(HostingCredentials.Render(SecretValue.of("gamma-token")))
        advanceUntilIdle()

        val connected = viewModel.uiState.value.provider("render")
        assertFalse(connected.isAddingAccount)
        assertEquals("Gamma", connected.dashboard?.account?.displayName)
        assertEquals(listOf("Alpha", "Gamma"), connected.accounts.map { it.displayName })
        assertEquals("gamma", connected.activeAccountId)
        assertNull(savedState.get<Boolean>(HostingProvidersViewModel.addingAccountKey("render")))
    }

    @Test
    fun switchingClosesTheOpenResourceAndShowsTheOtherAccount() = runTest(dispatcher) {
        val gateway = AccountsGateway(mutableListOf("alpha", "beta"), active = "alpha")
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        viewModel.openResource("render", "render-alpha-1")
        advanceUntilIdle()
        assertEquals("render-alpha-1", viewModel.uiState.value.provider("render").selectedResourceId)

        viewModel.switchAccount("render", "beta")
        assertEquals(HostingOperation.SWITCHING_ACCOUNT, viewModel.uiState.value.provider("render").operation)
        advanceUntilIdle()

        val state = viewModel.uiState.value.provider("render")
        assertNull(state.selectedResourceId)
        assertNull(state.operation)
        assertEquals("Beta", state.dashboard?.account?.displayName)
        assertEquals(listOf(false, true), state.accounts.map { it.isActive })
        assertEquals(listOf("beta"), gateway.switches)

        // Switching to the active account (or an unknown one) does nothing.
        viewModel.switchAccount("render", "beta")
        viewModel.switchAccount("render", "missing")
        advanceUntilIdle()
        assertEquals(listOf("beta"), gateway.switches)
    }

    @Test
    fun removeCurrentRemovesOnlyTheActiveAccountAndRemoveAllNeedsItsOwnConfirmation() = runTest(dispatcher) {
        val gateway = AccountsGateway(mutableListOf("alpha", "beta", "gamma"), active = "beta")
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation("render")
        assertTrue(viewModel.uiState.value.provider("render").showDisconnectConfirmation)
        viewModel.confirmDisconnect("render")
        advanceUntilIdle()

        var state = viewModel.uiState.value.provider("render")
        assertEquals(listOf("beta"), gateway.removed)
        assertEquals(HostingConnectionStatus.CONNECTED, state.status)
        assertEquals("Alpha", state.dashboard?.account?.displayName)
        assertEquals(listOf("Alpha", "Gamma"), state.accounts.map { it.displayName })

        viewModel.requestRemoveAllConfirmation("render")
        assertTrue(viewModel.uiState.value.provider("render").showRemoveAllConfirmation)
        assertTrue(viewModel.handleBack("render"))
        assertFalse(viewModel.uiState.value.provider("render").showRemoveAllConfirmation)
        assertTrue(gateway.removedAll.isEmpty())

        viewModel.requestRemoveAllConfirmation("render")
        viewModel.confirmRemoveAll("render")
        advanceUntilIdle()

        state = viewModel.uiState.value.provider("render")
        assertEquals(listOf("render"), gateway.removedAll)
        assertEquals(HostingConnectionStatus.DISCONNECTED, state.status)
        assertTrue(state.accounts.isEmpty())
    }

    @Test
    fun removingTheLastAccountShowsTheConnectForm() = runTest(dispatcher) {
        val gateway = AccountsGateway(mutableListOf("alpha"), active = "alpha")
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation("render")
        viewModel.confirmDisconnect("render")
        advanceUntilIdle()

        val state = viewModel.uiState.value.provider("render")
        assertEquals(listOf("alpha"), gateway.removed)
        assertEquals(HostingConnectionStatus.DISCONNECTED, state.status)
        assertTrue(state.showsConnectionForm)
    }

    @Test
    fun addingAccountStateSurvivesRecreationOnlyOverASavedAccount() = runTest(dispatcher) {
        val saved = SavedStateHandle(mapOf(HostingProvidersViewModel.addingAccountKey("render") to true))
        val viewModel = HostingProvidersViewModel(AccountsGateway(mutableListOf("alpha"), active = "alpha"), saved)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.provider("render").isAddingAccount)

        val stale = SavedStateHandle(mapOf(HostingProvidersViewModel.addingAccountKey("render") to true))
        val disconnected = HostingProvidersViewModel(AccountsGateway(mutableListOf(), active = null), stale)
        advanceUntilIdle()
        assertFalse(disconnected.uiState.value.provider("render").isAddingAccount)
        assertNull(stale.get<Boolean>(HostingProvidersViewModel.addingAccountKey("render")))
    }

    @Test
    fun launchProfileRefreshRunsOnceInTheForegroundAndRenamesTheActiveAccount() = runTest(dispatcher) {
        val gateway = AccountsGateway(mutableListOf("alpha", "beta"), active = "alpha")
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(0, gateway.profileRefreshes)

        gateway.renamed = true
        viewModel.onForeground()
        advanceUntilIdle()
        viewModel.onBackground()
        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(1, gateway.profileRefreshes)
        val state = viewModel.uiState.value.provider("render")
        assertEquals(listOf("Alpha (renamed)", "Beta (renamed)"), state.accounts.map { it.displayName })
        assertEquals("Alpha (renamed)", state.dashboard?.account?.displayName)
    }

    @Test
    fun firebaseRenewalReconnectsTheActiveProjectToRotateItInPlace() = runTest(dispatcher) {
        val gateway = AccountsGateway(mutableListOf("alpha"), active = "alpha", providerId = "firebase")
        gateway.refreshFailure = HostingUiException("Continue with Google to connect Firebase Hosting.", requiresGoogleSignIn = true)
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        viewModel.refresh("firebase")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.provider("firebase").googleSignInRequired)

        viewModel.requestGoogleSignIn("firebase")
        val request = checkNotNull(viewModel.uiState.value.provider("firebase").googleSignInRequest)
        viewModel.onGoogleSignInRequestHandled("firebase", request.id)
        viewModel.onGoogleSignInCompleted()
        advanceUntilIdle()

        val credentials = gateway.connects.single() as HostingCredentials.Firebase
        assertEquals("project-alpha", credentials.projectId)
    }

    /** One provider with several saved accounts; account ids are their lowercase names. */
    private class AccountsGateway(
        private val ids: MutableList<String>,
        private var active: String?,
        private val providerId: String = "render",
    ) : HostingProviderUiGateway {
        val switches = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val removedAll = mutableListOf<String>()
        val connects = mutableListOf<HostingCredentials>()
        var nextConnectName = "gamma"
        var renamed = false
        var profileRefreshes = 0
        var refreshFailure: HostingUiException? = null

        private fun name(id: String) = id.replaceFirstChar(Char::uppercaseChar) + if (renamed) " (renamed)" else ""

        private fun dashboardFor(id: String) = dashboard(providerId).let { base ->
            base.copy(
                account = HostingAccountUi(
                    id = "$id-profile",
                    displayName = name(id),
                    email = "$id@example.com",
                    savedAccountId = id,
                    firebaseProjectId = "project-$id".takeIf { providerId == "firebase" },
                ),
                resources = base.resources.map { it.copy(id = "$providerId-$id-${it.id.substringAfterLast('-')}") },
            )
        }

        private fun restoredActive(): HostingRestoreUi = active?.let { HostingRestoreUi.Available(dashboardFor(it)) }
            ?: HostingRestoreUi.NotConnected

        override suspend fun restore(): Result<Map<String, HostingRestoreUi>> = Result.success(mapOf(providerId to restoredActive()))

        override suspend fun connect(credentials: HostingCredentials): Result<HostingDashboardUi> {
            connects += credentials
            val id = (credentials as? HostingCredentials.Firebase)?.projectId?.removePrefix("project-") ?: nextConnectName
            if (id !in ids) ids += id
            active = id
            return Result.success(dashboardFor(id))
        }

        override suspend fun refresh(providerId: String): Result<HostingDashboardUi> =
            refreshFailure?.let { Result.failure(it) } ?: Result.success(dashboardFor(checkNotNull(active)))

        override suspend fun loadResource(providerId: String, resource: HostingResourceUi): Result<HostingResourceWorkspaceUi> =
            Result.success(HostingResourceWorkspaceUi(providerId, resource.id, emptyList(), 0))

        override suspend fun performPrimaryAction(
            providerId: String,
            resource: HostingResourceUi,
            latestDeploymentId: String?,
        ): Result<String> = Result.success("ok")

        override suspend fun disconnect(providerId: String): Result<Unit> {
            removedAll += providerId
            ids.clear()
            active = null
            return Result.success(Unit)
        }

        override suspend fun accounts(providerId: String): Result<List<ProviderAccountUi>> = Result.success(
            if (providerId != this.providerId) emptyList() else ids.map { id ->
                ProviderAccountUi(id, name(id), "$id@example.com", isActive = id == active)
            },
        )

        override suspend fun switchAccount(providerId: String, accountId: String): Result<HostingRestoreUi> {
            switches += accountId
            active = accountId
            return Result.success(restoredActive())
        }

        override suspend fun removeAccount(providerId: String, accountId: String): Result<HostingRestoreUi> {
            removed += accountId
            ids.remove(accountId)
            if (active == accountId) active = ids.firstOrNull()
            return Result.success(restoredActive())
        }

        override suspend fun refreshAccountProfiles(providerId: String): Result<List<ProviderAccountUi>> {
            profileRefreshes += 1
            return accounts(providerId)
        }
    }
}
