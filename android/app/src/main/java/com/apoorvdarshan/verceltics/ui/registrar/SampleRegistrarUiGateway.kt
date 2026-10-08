package com.apoorvdarshan.verceltics.ui.registrar

import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline fixtures for the app's sample-data preview. Uses the fictional `.example` portfolios
 * from `SampleRegistrarScreen`; it never reaches a registrar API or saved credentials. Name.com has
 * two sample accounts so the account menu can be explored; switching and removing only change this
 * in-memory preview, and [restore] (entering sample data) resets it.
 */
object SampleRegistrarUiGateway : RegistrarUiGateway {
    private data class SampleDomain(
        val name: String,
        val expiresInDays: Int,
        val autoRenew: Boolean,
        val privacy: Boolean = true,
    )

    private data class SampleAccount(
        val id: String,
        val name: String,
        val domains: List<SampleDomain>,
    )

    private val accounts = linkedMapOf(
        RegistrarProvider.NAME_DOT_COM to listOf(
            SampleAccount(
                id = "studio",
                name = "Studio · Name.com",
                domains = listOf(
                    SampleDomain("studio.example", 284, true),
                    SampleDomain("studio-design.example", 18, false),
                    SampleDomain("docs-studio.example", 196, true),
                    SampleDomain("edge-tools.example", 92, true),
                ),
            ),
            SampleAccount(
                id = "personal",
                name = "Personal · Name.com",
                domains = listOf(
                    SampleDomain("journal.example", 41, true),
                    SampleDomain("side-project.example", 9, false),
                ),
            ),
        ),
        RegistrarProvider.NAMECHEAP to listOf(
            SampleAccount(
                id = "studio",
                name = "Studio · Namecheap",
                domains = listOf(
                    SampleDomain("commerce.example", 347, true),
                    SampleDomain("launch-kit.example", 12, false),
                    SampleDomain("portfolio.example", 162, true),
                    SampleDomain("newsletter.example", 68, true),
                    SampleDomain("old-project.example", 24, false, false),
                ),
            ),
        ),
    )

    private val activeIds = ConcurrentHashMap<RegistrarProvider, String>()
    private val removedIds = ConcurrentHashMap<RegistrarProvider, Set<String>>()

    internal val sampleProviderIds: Set<String> = accounts.keys.map(RegistrarProvider::id).toSet()

    override suspend fun restore(): Result<RegistrarRestoreUi> {
        activeIds.clear()
        removedIds.clear()
        return Result.success(
            RegistrarRestoreUi(RegistrarProvider.entries.associate { provider -> provider.id to entry(provider) }),
        )
    }

    override suspend fun connect(request: RegistrarConnectRequest): Result<RegistrarDashboardUi> =
        unavailable(request.providerId)

    override suspend fun refresh(providerId: String): Result<RegistrarDashboardUi> =
        RegistrarProvider.fromId(providerId)?.let(::dashboard)?.let { Result.success(it) }
            ?: unavailable(providerId)

    override suspend fun disconnect(providerId: String): Result<Unit> {
        RegistrarProvider.fromId(providerId)?.let { provider ->
            removedIds[provider] = savedAccounts(provider).mapTo(HashSet()) { it.id }
        }
        return Result.success(Unit)
    }

    override suspend fun switchAccount(providerId: String, accountId: String): Result<RegistrarProviderRestoreUi> {
        val provider = RegistrarProvider.fromId(providerId) ?: return unavailable(providerId)
        if (savedAccounts(provider).none { it.id == accountId }) return unavailable(providerId)
        activeIds[provider] = accountId
        return Result.success(entry(provider))
    }

    override suspend fun removeAccount(providerId: String, accountId: String): Result<RegistrarProviderRestoreUi> {
        val provider = RegistrarProvider.fromId(providerId) ?: return unavailable(providerId)
        removedIds[provider] = removedIds[provider].orEmpty() + accountId
        if (activeIds[provider] == accountId) activeIds.remove(provider)
        return Result.success(entry(provider))
    }

    override suspend fun detectPublicIpv4(): Result<String> =
        Result.failure(RegistrarUiException("Exit sample data to detect this network."))

    private fun savedAccounts(provider: RegistrarProvider): List<SampleAccount> =
        accounts[provider].orEmpty().filter { it.id !in removedIds[provider].orEmpty() }

    private fun entry(provider: RegistrarProvider): RegistrarProviderRestoreUi =
        dashboard(provider)?.let(RegistrarProviderRestoreUi::Available) ?: RegistrarProviderRestoreUi.NotConnected

    private fun dashboard(provider: RegistrarProvider): RegistrarDashboardUi? {
        val saved = savedAccounts(provider)
        val active = saved.firstOrNull { it.id == activeIds[provider] } ?: saved.firstOrNull() ?: return null
        val now = System.currentTimeMillis()
        val accountUis = saved.map { RegistrarAccountUi(provider.id, it.name, it.id) }
        return RegistrarDashboardUi(
            account = accountUis.first { it.id == active.id },
            domains = active.domains
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SampleDomain::name))
                .map { domain ->
                    RegistrarDomainUi(
                        id = domain.name,
                        name = domain.name,
                        status = "Active",
                        createdAtMillis = now - 2L * 365 * DAY_MILLIS,
                        // Half a day of headroom keeps the displayed whole-day count stable.
                        expiresAtMillis = now + domain.expiresInDays * DAY_MILLIS + DAY_MILLIS / 2,
                        autoRenew = domain.autoRenew,
                        locked = true,
                        privacyEnabled = domain.privacy,
                        nameservers = listOf("maya.ns.cloudflare.com", "rick.ns.cloudflare.com"),
                    )
                },
            inventoryComplete = true,
            warnings = emptyList(),
            fetchedAtMillis = now,
            cacheState = RegistrarCacheState.LIVE,
            accounts = accountUis,
        )
    }

    private fun <T> unavailable(providerId: String): Result<T> {
        val name = RegistrarProvider.fromId(providerId)?.displayName ?: "a registrar"
        return Result.failure(RegistrarUiException("Exit sample data to connect $name."))
    }

    private const val DAY_MILLIS = 86_400_000L
}
