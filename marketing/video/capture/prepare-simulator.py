#!/usr/bin/env python3
"""Prepare an isolated copy for recording. Never edits the shipping iOS project."""
from pathlib import Path
import shutil, sys
repo = Path(__file__).resolve().parents[3]
dest = Path(sys.argv[1]).expanduser().resolve()
if dest == repo or repo in dest.parents:
    raise SystemExit('Destination must be outside the repository.')
if dest.exists():
    raise SystemExit('Choose a new destination; existing projects are never overwritten.')
shutil.copytree(repo / 'ios', dest)
app = dest / 'verceltics'
def replace(file, old, new):
    path = app / file
    text = path.read_text()
    if text.count(old) != 1:
        raise SystemExit(f'Expected exactly one patch location in {file}: {old[:80]}')
    path.write_text(text.replace(old, new))
shutil.copyfile(Path(__file__).with_name('AppDebugFixtures.swift'), app / 'App/AppDebugFixtures.swift')
replace('App/VercelticsApp.swift', 'AuthManager(ephemeralAccounts: [])', 'AppDebugFixtures.makeAuthManager()')
replace('App/VercelticsApp.swift', 'RegistrarStore(ephemeralAccounts: [])', 'AppDebugFixtures.makeRegistrarStore()')
replace('Paywall/PaywallManager.swift', '        configureRevenueCat()\n        Task {\n            await checkEntitlements()\n            await loadProducts()\n        }', '        hasActiveEntitlement = true\n        hasCheckedEntitlements = true\n        isLoading = false')
replace('Views/ProjectsView.swift', '    func load(token: String, forceRefresh: Bool = false) async {', '    func load(token: String, forceRefresh: Bool = false) async {\n        projects = AppDebugFixtures.projects\n        isLoading = false\n        return ()\n')
replace('Views/AnalyticsView.swift', '    ) async -> (didUnlock: Bool, applied: Bool, succeeded: Bool) {\n        let range', '    ) async -> (didUnlock: Bool, applied: Bool, succeeded: Bool) {\n        data = AppDebugFixtures.analytics\n        displayedRange = selectedRange\n        displayedEnvironment = selectedEnvironment\n        lastUpdated = .now\n        isLoading = false\n        return (false, true, true)\n        let range')
replace('Views/RegistrarsView.swift', '    func load(refresh: Bool = false) async {', '    func load(refresh: Bool = false) async {\n        domains = AppDebugFixtures.domains\n        isLoading = false\n        return ()\n')
replace('Views/CloudflareDashboardView.swift', '        let sameCredential = cacheKey == loadedCacheKey', '        accounts = AppDebugFixtures.cloudAccounts\n        selectedAccountID = accounts.first?.id\n        zones = AppDebugFixtures.zones\n        pagesProjects = AppDebugFixtures.pages\n        workers = AppDebugFixtures.workers\n        isLoading = false\n        return ()\n        let sameCredential = cacheKey == loadedCacheKey')
replace('Views/CloudflareZoneDetailView.swift', '    func load(forceRefresh: Bool = false) async {', '    func load(forceRefresh: Bool = false) async {\n        analytics = AppDebugFixtures.cloudAnalytics(zoneID: zoneID)\n        dnsRecords = AppDebugFixtures.dns\n        return ()\n')
replace('Auth/SiteStore.swift', '    func refresh(accountID: UUID? = nil, force: Bool = false) async {', '    func refresh(accountID: UUID? = nil, force: Bool = false) async {\n        return ()\n')
# Protect capture work from ever installing over the real app.
project = dest / 'verceltics.xcodeproj/project.pbxproj'
text = project.read_text().replace('PRODUCT_BUNDLE_IDENTIFIER = com.apoorvdarshan.verceltics;', 'PRODUCT_BUNDLE_IDENTIFIER = com.apoorvdarshan.verceltics.marketingdebug;').replace('INFOPLIST_KEY_CFBundleDisplayName = Verceltics;', 'INFOPLIST_KEY_CFBundleDisplayName = "Verceltics Debug";')
project.write_text(text)
# Keep an explicit marker: this copy is not a release input.
(dest / 'MARKETING-SIMULATOR-ONLY.txt').write_text('Fictional capture build. Debug + iOS Simulator only. Do not archive or install on devices.\n')
print(dest)
# Optional timed navigation records native transitions without depending on taps.
replace('Views/ProjectsView.swift', '.task { await loadProjects() }', '''.task {
                await loadProjects()
                if ProcessInfo.processInfo.arguments.contains("-autoCapture") {
                    try? await Task.sleep(for: .seconds(4))
                    withAnimation { navigationProjectId = vm.projects.first?.id }
                }
            }''')
replace('Views/CloudflareDashboardView.swift', '''                    preferredAccountID: persistedAccountID.isEmpty ? nil : persistedAccountID
                )
            }
            .onChange(of: backgroundRefreshRequestID)''', '''                    preferredAccountID: persistedAccountID.isEmpty ? nil : persistedAccountID
                )
                if ProcessInfo.processInfo.arguments.contains("-autoCapture") {
                    try? await Task.sleep(for: .seconds(4))
                    withAnimation { navigationRoute = .zone("demo-zone") }
                }
            }
            .onChange(of: backgroundRefreshRequestID)''')
replace('Views/RegistrarsView.swift', '.task { await viewModel.load() }', '''.task {
                await viewModel.load()
                if ProcessInfo.processInfo.arguments.contains("-autoCapture") && account.provider == .namecheap {
                    try? await Task.sleep(for: .seconds(4))
                    if let other = store.accounts.first(where: { $0.provider == .nameDotCom }) {
                        withAnimation { store.switchAccount(to: other.id) }
                    }
                }
            }''')
replace('Views/SitesView.swift', '''                await loadActive(force: false)
            }
            .onChange(of: backgroundRefreshRequestID)''', '''                await loadActive(force: false)
                if ProcessInfo.processInfo.arguments.contains("-autoCapture"), store.activeAccount?.provider == .googleSearchConsole {
                    try? await Task.sleep(for: .seconds(4))
                    if let other = store.accounts.first(where: { $0.provider == .googleAnalytics }) {
                        withAnimation { store.switchAccount(to: other.id) }
                    }
                }
            }
            .onChange(of: backgroundRefreshRequestID)''')
