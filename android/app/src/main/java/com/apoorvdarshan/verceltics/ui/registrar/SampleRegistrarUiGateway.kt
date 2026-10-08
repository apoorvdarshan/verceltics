package com.apoorvdarshan.verceltics.ui.registrar

import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider

/**
 * Offline fixtures for the app's sample-data preview. Uses the fictional `.example` portfolios
 * from `SampleRegistrarScreen`; it never reaches a registrar API or saved credentials.
 */
object SampleRegistrarUiGateway : RegistrarUiGateway {
    private data class SampleDomain(
        val name: String,
        val expiresInDays: Int,
        val autoRenew: Boolean,
        val privacy: Boolean = true,
    )

    private val portfolios = linkedMapOf(
        RegistrarProvider.NAME_DOT_COM to listOf(
            SampleDomain("studio.example", 284, true),
            SampleDomain("studio-design.example", 18, false),
            SampleDomain("docs-studio.example", 196, true),
            SampleDomain("edge-tools.example", 92, true),
        ),
        RegistrarProvider.NAMECHEAP to listOf(
            SampleDomain("commerce.example", 347, true),
            SampleDomain("launch-kit.example", 12, false),
            SampleDomain("portfolio.example", 162, true),
            SampleDomain("newsletter.example", 68, true),
            SampleDomain("old-project.example", 24, false, false),
        ),
    )

    internal val sampleProviderIds: Set<String> = portfolios.keys.map(RegistrarProvider::id).toSet()

    override suspend fun restore(): Result<RegistrarRestoreUi> = Result.success(
        RegistrarRestoreUi(
            RegistrarProvider.entries.associate { provider ->
                provider.id to (
                    dashboard(provider)?.let(RegistrarProviderRestoreUi::Available)
                        ?: RegistrarProviderRestoreUi.NotConnected
                    )
            },
        ),
    )

    override suspend fun connect(request: RegistrarConnectRequest): Result<RegistrarDashboardUi> =
        unavailable(request.providerId)

    override suspend fun refresh(providerId: String): Result<RegistrarDashboardUi> =
        RegistrarProvider.fromId(providerId)?.let(::dashboard)?.let { Result.success(it) }
            ?: unavailable(providerId)

    override suspend fun disconnect(providerId: String): Result<Unit> = Result.success(Unit)

    override suspend fun detectPublicIpv4(): Result<String> =
        Result.failure(RegistrarUiException("Exit sample data to detect this network."))

    private fun dashboard(provider: RegistrarProvider): RegistrarDashboardUi? {
        val domains = portfolios[provider] ?: return null
        val now = System.currentTimeMillis()
        return RegistrarDashboardUi(
            account = RegistrarAccountUi(provider.id, "Studio · ${provider.displayName}"),
            domains = domains
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
        )
    }

    private fun <T> unavailable(providerId: String): Result<T> {
        val name = RegistrarProvider.fromId(providerId)?.displayName ?: "a registrar"
        return Result.failure(RegistrarUiException("Exit sample data to connect $name."))
    }

    private const val DAY_MILLIS = 86_400_000L
}
