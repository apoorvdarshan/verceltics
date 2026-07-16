import SwiftUI

@Observable
@MainActor
final class AnalyticsViewModel {
    private struct CachedAnalytics {
        let data: AnalyticsData
        let hasLongAnalyticsHistory: Bool
        let analyticsUnavailableMessage: String?
        let updatedAt: Date
    }

    private struct CachedProjectContext {
        let projectDetails: Project
        let domains: [String]
        let recentDeployments: [RecentDeployment]
        let updatedAt: Date
    }

    private struct LoadedAnalytics {
        let data: AnalyticsData
        let didUnlockLongAnalyticsHistory: Bool
        let error: String?
        let unavailableMessage: String?
    }

    private struct LoadedProjectContext {
        let projectDetails: Project?
        let domains: [String]?
        let recentDeployments: [RecentDeployment]?

        var hasCompleteResponse: Bool {
            projectDetails != nil && domains != nil && recentDeployments != nil
        }
    }

    @ResettableMemoryCache private static var analyticsCache: [String: CachedAnalytics] = [:]
    @ResettableMemoryCache private static var projectContextCache: [String: CachedProjectContext] = [:]
    private static let analyticsCacheLifetime = DashboardRefreshPolicy.reportFreshness
    private static let projectContextCacheLifetime = DashboardRefreshPolicy.inventoryFreshness

    let project: Project

    var data = AnalyticsData()
    var projectDetails: Project
    var domains: [String] = []
    var recentDeployments: [RecentDeployment] = []
    var selectedRange: TimeRange = .week
    var selectedEnvironment: VercelEnvironment = .production
    var displayedRange: TimeRange?
    var displayedEnvironment: VercelEnvironment?
    var hasLongAnalyticsHistory = false
    var isLoading = true
    var error: String?
    var analyticsUnavailableMessage: String?
    private(set) var lastUpdated: Date?
    private var loadGeneration = 0

    init(project: Project, token: String? = nil) {
        self.project = project
        self.projectDetails = project
        self.domains = project.primaryDomain.map { [$0] } ?? []

        guard let token, !token.isEmpty else { return }
        let analyticsKey = Self.analyticsCacheKey(
            token: token,
            project: project,
            range: selectedRange,
            environment: selectedEnvironment
        )
        if let cached = Self.analyticsCache[analyticsKey] {
            data = cached.data
            hasLongAnalyticsHistory = cached.hasLongAnalyticsHistory
            analyticsUnavailableMessage = cached.analyticsUnavailableMessage
            displayedRange = selectedRange
            displayedEnvironment = selectedEnvironment
            lastUpdated = cached.updatedAt
            isLoading = false
        }

        let contextKey = Self.projectContextCacheKey(token: token, project: project)
        if let cached = Self.projectContextCache[contextKey] {
            projectDetails = cached.projectDetails
            domains = cached.domains
            recentDeployments = cached.recentDeployments
        }
    }

    func load(
        token: String,
        hasLongAnalyticsHistory: Bool,
        forceRefresh: Bool = false
    ) async -> (didUnlock: Bool, applied: Bool, succeeded: Bool) {
        let range = selectedRange
        let environment = selectedEnvironment
        let projectContextCacheKey = Self.projectContextCacheKey(token: token, project: project)
        let analyticsCacheKey = Self.analyticsCacheKey(
            token: token,
            project: project,
            range: range,
            environment: environment
        )
        let now = Date.now
        self.hasLongAnalyticsHistory = hasLongAnalyticsHistory || self.hasLongAnalyticsHistory

        var analyticsCacheIsFresh = false
        if let cached = Self.analyticsCache[analyticsCacheKey] {
            data = cached.data
            self.hasLongAnalyticsHistory = hasLongAnalyticsHistory || cached.hasLongAnalyticsHistory
            analyticsUnavailableMessage = cached.analyticsUnavailableMessage
            displayedRange = range
            displayedEnvironment = environment
            lastUpdated = cached.updatedAt
            error = nil
            isLoading = false
            analyticsCacheIsFresh = now.timeIntervalSince(cached.updatedAt) < Self.analyticsCacheLifetime
        }

        var projectContextCacheIsFresh = false
        if let cached = Self.projectContextCache[projectContextCacheKey] {
            projectDetails = cached.projectDetails
            domains = cached.domains
            recentDeployments = cached.recentDeployments
            projectContextCacheIsFresh = now.timeIntervalSince(cached.updatedAt) < Self.projectContextCacheLifetime
        }

        let shouldFetchAnalytics = forceRefresh || !analyticsCacheIsFresh
        let shouldFetchProjectContext = forceRefresh || !projectContextCacheIsFresh
        guard shouldFetchAnalytics || shouldFetchProjectContext else {
            isLoading = false
            error = nil
            return (false, true, true)
        }

        loadGeneration += 1
        let generation = loadGeneration
        isLoading = true
        error = nil
        if displayedRange == nil { analyticsUnavailableMessage = nil }

        var loadedData = AnalyticsData()
        var loadedError: String?
        var loadedUnavailableMessage: String?
        let api = VercelAPI(token: token)
        let pid = project.id
        let tid = project.teamId
        let from = range.fromDate
        let to = range.toDate
        let prevFrom = range.previousFromDate
        let prevTo = range.previousToDate
        let env = environment.queryValue

        async let fetchedAnalytics = Self.fetchAnalyticsIfNeeded(
            shouldFetchAnalytics,
            api: api,
            projectID: pid,
            teamID: tid,
            range: range,
            environment: env,
            from: from,
            to: to,
            previousFrom: prevFrom,
            previousTo: prevTo
        )
        async let fetchedProjectContext = Self.fetchProjectContextIfNeeded(
            shouldFetchProjectContext,
            api: api,
            projectID: pid,
            teamID: tid
        )

        let analyticsResult: LoadedAnalytics?
        let projectContextResult: LoadedProjectContext?
        do {
            (analyticsResult, projectContextResult) = try await (fetchedAnalytics, fetchedProjectContext)
        } catch is CancellationError {
            guard generation == loadGeneration else { return (false, false, false) }
            isLoading = false
            return (false, false, false)
        } catch {
            guard generation == loadGeneration else { return (false, false, false) }
            isLoading = false
            self.error = error.localizedDescription
            return (false, true, false)
        }

        guard !Task.isCancelled, generation == loadGeneration else {
            return (false, false, false)
        }

        if let projectContextResult {
            if let loadedProject = projectContextResult.projectDetails {
                projectDetails = loadedProject
            }
            if let loadedDomains = projectContextResult.domains {
                domains = loadedDomains.isEmpty ? project.primaryDomain.map { [$0] } ?? [] : loadedDomains
            }
            if let loadedDeployments = projectContextResult.recentDeployments {
                recentDeployments = loadedDeployments
            }
            if projectContextResult.hasCompleteResponse {
                Self.projectContextCache[projectContextCacheKey] = CachedProjectContext(
                    projectDetails: projectDetails,
                    domains: domains,
                    recentDeployments: recentDeployments,
                    updatedAt: .now
                )
            }
        }

        let didUnlockLongAnalyticsHistory = analyticsResult?.didUnlockLongAnalyticsHistory ?? false
        if let analyticsResult {
            loadedData = analyticsResult.data
            loadedError = analyticsResult.error
            loadedUnavailableMessage = analyticsResult.unavailableMessage

            if loadedError == nil {
                data = loadedData
                displayedRange = range
                displayedEnvironment = environment
                analyticsUnavailableMessage = loadedUnavailableMessage
                self.hasLongAnalyticsHistory = hasLongAnalyticsHistory
                    || didUnlockLongAnalyticsHistory
                    || self.hasLongAnalyticsHistory

                let updatedAt = Date.now
                lastUpdated = updatedAt
                Self.analyticsCache[analyticsCacheKey] = CachedAnalytics(
                    data: data,
                    hasLongAnalyticsHistory: self.hasLongAnalyticsHistory,
                    analyticsUnavailableMessage: analyticsUnavailableMessage,
                    updatedAt: updatedAt
                )
            }
            error = loadedError
        } else {
            error = nil
        }

        isLoading = false
        return (didUnlockLongAnalyticsHistory, true, loadedError == nil)
    }

    private static func projectContextCacheKey(token: String, project: Project) -> String {
        CredentialCacheScope.fingerprint(fields: [
            "vercel-analytics-project-context",
            token,
            project.id,
            project.teamId ?? ""
        ])
    }

    private static func analyticsCacheKey(
        token: String,
        project: Project,
        range: TimeRange,
        environment: VercelEnvironment
    ) -> String {
        CredentialCacheScope.fingerprint(fields: [
            "vercel-analytics",
            token,
            project.id,
            project.teamId ?? "",
            range.rawValue,
            environment.rawValue
        ])
    }

    private static func fetchAnalyticsIfNeeded(
        _ shouldFetch: Bool,
        api: VercelAPI,
        projectID: String,
        teamID: String?,
        range: TimeRange,
        environment: String?,
        from: String,
        to: String,
        previousFrom: String,
        previousTo: String
    ) async throws -> LoadedAnalytics? {
        guard shouldFetch else { return nil }
        try Task.checkCancellation()

        var loadedData = AnalyticsData()
        var loadedError: String?
        var unavailableMessage: String?

        do {
            async let overview = api.fetchOverview(projectId: projectID, teamId: teamID, from: from, to: to, environment: environment)
            async let previous = api.fetchPreviousOverview(projectId: projectID, teamId: teamID, from: previousFrom, to: previousTo, environment: environment)
            async let timeseries = api.fetchTimeseries(projectId: projectID, teamId: teamID, from: from, to: to, environment: environment)
            async let pages = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "path", environment: environment)
            async let referrers = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "referrer", environment: environment)
            async let countries = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "country", environment: environment)
            async let devices = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "device_type", environment: environment)
            async let browsers = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "client_name", environment: environment)
            async let os = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "os_name", environment: environment)
            async let utmSources = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "utm", environment: environment)
            async let routes = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "route", environment: environment)
            async let hostnames = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "hostname", environment: environment)
            async let events = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "event_name", environment: environment)
            async let flags = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "flags", environment: environment)
            async let queryParams = api.fetchBreakdown(projectId: projectID, teamId: teamID, from: from, to: to, groupBy: "query_params", environment: environment)

            loadedData.overview = try await overview
            loadedData.previousOverview = try? await previous
            loadedData.timeseries = try await timeseries
            loadedData.pages = try await pages
            loadedData.referrers = try await referrers
            loadedData.countries = try await countries
            loadedData.devices = (try? await devices) ?? []
            loadedData.browsers = (try? await browsers) ?? []
            loadedData.os = (try? await os) ?? []
            loadedData.utmSources = (try? await utmSources) ?? []
            loadedData.routes = (try? await routes) ?? []
            loadedData.hostnames = (try? await hostnames) ?? []
            loadedData.events = (try? await events) ?? []
            loadedData.flags = (try? await flags) ?? []
            loadedData.queryParams = (try? await queryParams) ?? []
            try Task.checkCancellation()
        } catch is CancellationError {
            throw CancellationError()
        } catch let apiError as APIError {
            switch apiError {
            case .unauthorized:
                loadedError = apiError.localizedDescription
            case .serverError(let code) where code == 400 || code == 404:
                unavailableMessage = "Vercel Web Analytics is not available through token access right now. Project details, domains, and deployments are still shown below."
            case .serverError(let code):
                unavailableMessage = "Vercel Web Analytics returned HTTP \(code). Project details, domains, and deployments are still shown below."
            default:
                loadedError = apiError.localizedDescription
            }
        } catch {
            loadedError = error.localizedDescription
        }

        try Task.checkCancellation()
        return LoadedAnalytics(
            data: loadedData,
            didUnlockLongAnalyticsHistory: range.isPro
                && loadedError == nil
                && unavailableMessage == nil,
            error: loadedError,
            unavailableMessage: unavailableMessage
        )
    }

    private static func fetchProjectContextIfNeeded(
        _ shouldFetch: Bool,
        api: VercelAPI,
        projectID: String,
        teamID: String?
    ) async throws -> LoadedProjectContext? {
        guard shouldFetch else { return nil }
        try Task.checkCancellation()

        async let fetchedProject: Project? = try? await api.fetchProject(id: projectID, teamId: teamID)
        async let fetchedDomains: [String]? = try? await api.fetchProjectDomains(projectId: projectID, teamId: teamID)
        async let fetchedDeployments: [RecentDeployment]? = try? await api.fetchDeployments(projectId: projectID, teamId: teamID)

        let (projectDetails, domains, recentDeployments) = await (
            fetchedProject,
            fetchedDomains,
            fetchedDeployments
        )
        let result = LoadedProjectContext(
            projectDetails: projectDetails,
            domains: domains,
            recentDeployments: recentDeployments
        )
        try Task.checkCancellation()
        return result
    }
}

struct AnalyticsView: View {
    let project: Project
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var vm: AnalyticsViewModel
    @State private var lastUpdated: Date?
    @State private var refreshSpin: Double = 0
    @State private var loadTask: Task<Void, Never>?

    init(project: Project, initialToken: String? = nil) {
        self.project = project
        _vm = State(wrappedValue: AnalyticsViewModel(project: project, token: initialToken))
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            if vm.isLoading && !hasVisibleContent {
                AnalyticsSkeletonView()
            } else if let error = vm.error, !hasVisibleContent {
                ErrorStateView(message: error) {
                    startLoad(forceRefresh: true)
                }
            } else {
                analyticsContent
            }
        }
        .navigationTitle(project.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    if !reduceMotion {
                        withAnimation(.easeInOut(duration: 0.45)) { refreshSpin += 360 }
                    }
                    startLoad(forceRefresh: true)
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 14, weight: .semibold))
                        .rotationEffect(.degrees(refreshSpin))
                        .foregroundStyle(AppTheme.textSecondary)
                }
                .disabled(vm.isLoading)
                .accessibilityLabel(vm.isLoading ? "Refreshing analytics" : "Refresh analytics")
                .sensoryFeedback(.impact(weight: .light), trigger: refreshSpin)
            }
        }
        .onAppear {
            startLoad()
        }
        .onChange(of: vm.selectedRange) {
            startLoad(debounce: true)
        }
        .onChange(of: vm.selectedEnvironment) {
            startLoad(debounce: true)
        }
        .onDisappear {
            loadTask?.cancel()
            loadTask = nil
        }
        .sensoryFeedback(.selection, trigger: vm.selectedRange)
        .sensoryFeedback(.selection, trigger: vm.selectedEnvironment)
    }

    private var hasVisibleContent: Bool {
        vm.displayedRange != nil
    }

    private var breakdownColumns: [GridItem] {
        hSize == .regular
            ? [GridItem(.adaptive(minimum: 320, maximum: 480), spacing: 16)]
            : [GridItem(.flexible())]
    }

    private var statsColumns: [GridItem] {
        if dynamicTypeSize.isAccessibilitySize {
            return [GridItem(.flexible(minimum: 0), alignment: .top)]
        }
        return Array(
            repeating: GridItem(.flexible(minimum: 0), spacing: 10, alignment: .top),
            count: 3
        )
    }

    private var chartHeight: CGFloat {
        if dynamicTypeSize.isAccessibilitySize { return 340 }
        return hSize == .regular ? 340 : 260
    }

    private var analyticsContent: some View {
        ScrollView {
            VStack(spacing: 16) {
                header

                if let error = vm.error {
                    AppFeedbackBanner(
                        title: "Analytics refresh failed",
                        message: staleAnalyticsMessage(error),
                        tint: AppTheme.warning,
                        actionTitle: "Retry"
                    ) {
                        startLoad(forceRefresh: true)
                    }
                }

                if let analyticsUnavailableMessage = vm.analyticsUnavailableMessage {
                    analyticsUnavailableCard(analyticsUnavailableMessage)
                } else {
                    statsCards
                    analyticsChartCard
                }

                projectExtras

                if vm.analyticsUnavailableMessage == nil {
                    analyticsBreakdowns
                }
            }
            .padding()
            .frame(maxWidth: hSize == .regular ? 1100 : .infinity)
            .frame(maxWidth: .infinity)
        }
        .refreshable {
            let task = startLoad(forceRefresh: true)
            await task.value
        }
    }

    private func analyticsUnavailableCard(_ message: String) -> some View {
        AppFeedbackBanner(
            title: "Analytics unavailable",
            message: message,
            icon: "chart.bar.xaxis",
            tint: AppTheme.signal
        )
    }

    private func staleAnalyticsMessage(_ error: String) -> String {
        guard let range = vm.displayedRange, let environment = vm.displayedEnvironment else { return error }
        return "\(error) Showing the last successful \(range.rawValue) · \(environment.controlLabel) result."
    }

    private var analyticsChartCard: some View {
        AnalyticsChart(data: vm.data.timeseries)
            .frame(height: chartHeight)
            .padding(18)
            .appSurface()
    }

    private var analyticsBreakdowns: some View {
        LazyVGrid(columns: breakdownColumns, spacing: 16) {
            breakdownCard(title: "Pages", icon: "doc.text", items: vm.data.pages, isPath: true)
            breakdownCard(title: "Routes", icon: "arrow.triangle.branch", items: vm.data.routes, isPath: true)
            breakdownCard(title: "Hostnames", icon: "server.rack", items: vm.data.hostnames)

            breakdownCard(title: "Referrers", icon: "link", items: vm.data.referrers, emptyLabel: "Direct")
            breakdownCard(
                title: "UTM Parameters",
                icon: "tag",
                items: vm.data.utmSources,
                lockedTitle: vm.hasLongAnalyticsHistory ? "Upgrade to Web Analytics Plus" : "Requires Pro + Web Analytics Plus",
                lockedSubtitle: "to access this feature"
            )

            breakdownCard(title: "Countries", icon: "globe.americas", items: vm.data.countries, isCountry: true)

            breakdownCard(title: "Devices", icon: "desktopcomputer", items: vm.data.devices)
            breakdownCard(title: "Browsers", icon: "safari", items: vm.data.browsers)
            breakdownCard(title: "Operating Systems", icon: "laptopcomputer", items: vm.data.os)

            breakdownCard(title: "Events", icon: "bolt.fill", items: vm.data.events, proHint: "Pro")
            breakdownCard(title: "Flags", icon: "flag.fill", items: vm.data.flags)
            breakdownCard(title: "Query Parameters", icon: "questionmark.circle", items: vm.data.queryParams)
        }
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 12) {
                ProjectIcon(domain: project.primaryDomain, name: project.name)

                if let domain = project.primaryDomain, let url = URL(string: "https://\(domain)") {
                    Button {
                        UIApplication.shared.open(url)
                    } label: {
                        HStack(spacing: 7) {
                            Text(domain)
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(AppTheme.textPrimary)
                                .lineLimit(1)
                                .truncationMode(.middle)

                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 8, weight: .semibold))
                                .foregroundStyle(AppTheme.signal)
                                .frame(width: 17, height: 17)
                                .background(AppTheme.signal.opacity(0.12))
                                .clipShape(Circle())
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(AppTheme.surfaceRaised)
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(AppTheme.stroke, lineWidth: 0.5))
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .accessibilityLabel("Open \(domain)")
                }
                Spacer()
            }

            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 8) {
                        rangeMenu
                        environmentMenu
                        if vm.isLoading {
                            ProgressView().controlSize(.small).tint(AppTheme.textSecondary)
                                .accessibilityLabel("Refreshing analytics")
                        }
                    }
                } else {
                    HStack(spacing: 8) {
                        rangeMenu
                        environmentMenu
                        Spacer()
                        if vm.isLoading {
                            ProgressView().controlSize(.small).tint(AppTheme.textSecondary)
                                .accessibilityLabel("Refreshing analytics")
                        }
                    }
                }
            }

            if let lastUpdated {
                Label("Updated \(lastUpdated.formatted(.relative(presentation: .named)))", systemImage: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(AppTheme.textSecondary)
                    .transition(.opacity)
            }
        }
        .padding(.bottom, 2)
    }

    private var rangeMenu: some View {
                Menu {
                    ForEach(TimeRange.allCases) { range in
                        Button {
                            vm.selectedRange = range
                        } label: {
                            HStack {
                                Text(range.label)
                                if range.isPro && !vm.hasLongAnalyticsHistory {
                                    Image(systemName: "lock.fill")
                                }
                                if vm.selectedRange == range {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "calendar")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(AppTheme.textSecondary)
                        Text(vm.selectedRange.controlLabel)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Image(systemName: "chevron.down")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(AppTheme.textTertiary)
                    }
                    .padding(.horizontal, 11)
                    .padding(.vertical, 9)
                    .background(AppTheme.surfaceRaised)
                    .foregroundStyle(AppTheme.textPrimary)
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous).strokeBorder(AppTheme.stroke, lineWidth: 0.5))
                }
                .buttonStyle(PressScaleButtonStyle())
                .accessibilityLabel("Analytics range")
                .accessibilityValue(vm.selectedRange.controlLabel)
    }

    private var environmentMenu: some View {
                Menu {
                    ForEach(VercelEnvironment.allCases) { environment in
                        Button {
                            vm.selectedEnvironment = environment
                        } label: {
                            HStack {
                                Text(environment.label)
                                if vm.selectedEnvironment == environment {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "shippingbox")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(AppTheme.textSecondary)
                        Text(vm.selectedEnvironment.controlLabel)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                        Image(systemName: "chevron.down")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(AppTheme.textTertiary)
                    }
                    .padding(.horizontal, 11)
                    .padding(.vertical, 9)
                    .background(AppTheme.surfaceRaised)
                    .foregroundStyle(AppTheme.textPrimary)
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous).strokeBorder(AppTheme.stroke, lineWidth: 0.5))
                }
                .buttonStyle(PressScaleButtonStyle())
                .layoutPriority(1)
                .accessibilityLabel("Deployment environment")
                .accessibilityValue(vm.selectedEnvironment.controlLabel)
    }

    // MARK: - Stats Cards

    private var statsCards: some View {
        LazyVGrid(columns: statsColumns, spacing: 10) {
            StatCard(
                title: "Visitors",
                value: formatNumber(vm.data.overview?.devices ?? 0),
                change: vm.data.visitorsChange,
                icon: "person.2",
                appearDelay: 0.0
            )
            StatCard(
                title: "Page Views",
                value: formatNumber(vm.data.overview?.total ?? 0),
                change: vm.data.pageViewsChange,
                icon: "eye",
                appearDelay: 0.06
            )
            StatCard(
                title: "Bounce Rate",
                value: (vm.data.overview?.bounceRate).map { "\($0)%" } ?? "—",
                change: vm.data.bounceRateChange,
                invertChange: true,
                icon: "arrow.uturn.left",
                appearDelay: 0.12
            )
        }
    }

    // MARK: - Vercel Project Metadata

    private var projectExtras: some View {
        LazyVGrid(columns: breakdownColumns, spacing: 16) {
            projectSnapshotCard
            deploymentsCard
            domainsCard
        }
    }

    private var projectSnapshotCard: some View {
        infoPanel(title: "Project", icon: "folder.fill") {
            VStack(spacing: 0) {
                detailRow(
                    icon: "triangle.fill",
                    title: "Scope",
                    value: vm.projectDetails.sourceScope?.name ?? project.sourceScope?.name ?? "Personal"
                )

                detailRow(
                    icon: "square.stack.3d.up.fill",
                    title: "Framework",
                    value: vm.projectDetails.framework ?? project.framework ?? "Not set"
                )

                if let link = vm.projectDetails.link ?? project.link,
                   let org = link.org,
                   let repo = link.repo {
                    detailRow(
                        icon: "chevron.left.forwardslash.chevron.right",
                        title: "Repository",
                        value: "\(org)/\(repo)"
                    )
                }

                if let deployment = vm.projectDetails.lastDeployment ?? project.lastDeployment {
                    detailRow(
                        icon: "text.bubble.fill",
                        title: "Last Commit",
                        value: deployment.commitMessage ?? "No commit message"
                    )

                    if let date = deployment.date {
                        detailRow(
                            icon: "clock.fill",
                            title: "Last Deploy",
                            value: date.formatted(.relative(presentation: .named))
                        )
                    }
                }
            }
        }
    }

    private var deploymentsCard: some View {
        infoPanel(title: "Recent Deployments", icon: "shippingbox.fill") {
            if vm.recentDeployments.isEmpty {
                emptyInfoState("No deployments returned")
            } else {
                VStack(spacing: 0) {
                    ForEach(vm.recentDeployments.prefix(5)) { deployment in
                        deploymentRow(deployment)
                    }
                }
            }
        }
    }

    private var domainsCard: some View {
        infoPanel(title: "Domains", icon: "globe") {
            if vm.domains.isEmpty {
                emptyInfoState("No verified domains returned")
            } else {
                VStack(spacing: 0) {
                    ForEach(vm.domains.prefix(8), id: \.self) { domain in
                        detailRow(
                            icon: Project.isVercelDomain(domain) ? "triangle.fill" : "checkmark.seal.fill",
                            title: Project.isVercelDomain(domain) ? "Vercel Alias" : "Custom Domain",
                            value: domain,
                            url: URL(string: "https://\(domain)")
                        )
                    }
                }
            }
        }
    }

    private func infoPanel<Content: View>(
        title: String,
        icon: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                AppIconTile(icon: icon, tint: AppTheme.signal, size: 28)

                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)

            Divider().overlay(AppTheme.divider)

            content()
        }
        .appSurface()
    }

    @ViewBuilder
    private func detailRow(icon: String, title: String, value: String, url: URL? = nil) -> some View {
        let content = HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.textTertiary)
                .frame(width: 18)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AppTheme.textSecondary)
                    .textCase(.uppercase)
                    .tracking(0.7)

                Text(value)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppTheme.textPrimary)
                    .lineLimit(2)
                    .truncationMode(.middle)
            }

            Spacer(minLength: 8)

            if url != nil {
                Image(systemName: "arrow.up.right")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(AppTheme.signal)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .contentShape(Rectangle())

        if let url {
            Button {
                UIApplication.shared.open(url)
            } label: {
                content
            }
            .buttonStyle(PressScaleButtonStyle())
        } else {
            content
        }
    }

    private func deploymentRow(_ deployment: RecentDeployment) -> some View {
        NavigationLink {
            DeploymentDetailView(
                project: project,
                deployment: deployment,
                initialToken: authManager.token
            )
        } label: {
            deploymentRowContent(deployment)
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    private func deploymentRowContent(_ deployment: RecentDeployment) -> some View {
        let title = deployment.meta?.githubCommitMessage ?? deployment.name ?? deployment.url ?? "Deployment"
        let branch = deployment.meta?.githubCommitRef
        let sha = deployment.meta?.githubCommitSha.map { String($0.prefix(7)) }
        let date = deployment.date?.formatted(.relative(presentation: .named))

        return HStack(alignment: .top, spacing: 10) {
            Circle()
                .fill(deploymentStatusColor(deployment.displayState))
                .frame(width: 8, height: 8)
                .padding(.top, 5)

            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(title)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(1)

                    AppStatusBadge(text: deployment.displayState.capitalized, tone: .status(deployment.displayState))
                }

                HStack(spacing: 6) {
                    Text(deployment.displayTarget)
                    if let branch {
                        Text("·")
                        Image(systemName: "arrow.triangle.branch")
                        Text(branch)
                    }
                    if let sha {
                        Text("·")
                        Text(sha)
                    }
                    if let date {
                        Text("·")
                        Text(date)
                    }
                }
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(AppTheme.textSecondary)
                .lineLimit(1)
            }

            Spacer(minLength: 8)

            Image(systemName: "chevron.right")
                .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(AppTheme.textTertiary)
                .frame(width: 24, height: 24)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func emptyInfoState(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(AppTheme.textSecondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 28)
    }

    private func deploymentStatusColor(_ state: String) -> Color {
        AppStatusTone.status(state).color
    }

    // MARK: - Breakdown Card

    private func breakdownCard(
        title: String,
        icon: String,
        items: [BreakdownItem],
        emptyLabel: String = "",
        isPath: Bool = false,
        isCountry: Bool = false,
        lockedTitle: String? = nil,
        lockedSubtitle: String? = nil,
        proHint: String? = nil
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            HStack {
                HStack(spacing: 8) {
                    AppIconTile(icon: icon, tint: AppTheme.signal, size: 28)
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                }
                Spacer()
                HStack(spacing: 0) {
                    Text("VIEWS")
                        .frame(width: 54, alignment: .trailing)
                    Text("VISITORS")
                        .frame(width: 64, alignment: .trailing)
                }
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
                .tracking(0.7)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)

            Divider().overlay(AppTheme.divider)

            if items.isEmpty {
                let lockTitle = lockedTitle ?? proHint.map { "Requires \($0)" }
                if let lockTitle {
                    VStack(spacing: 8) {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 18))
                            .foregroundStyle(AppTheme.textTertiary)
                        Text(lockTitle)
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(AppTheme.textPrimary)
                        Text(lockedSubtitle ?? "Upgrade your Vercel plan")
                            .font(.footnote)
                            .foregroundStyle(AppTheme.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
                } else {
                    Text("No data available")
                        .font(.footnote)
                        .foregroundStyle(AppTheme.textSecondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 28)
                }
            } else {
                let maxVal = max(items.first?.visitors ?? 0, 1)
                ForEach(items.prefix(8)) { item in
                    HStack(spacing: 0) {
                        ZStack(alignment: .leading) {
                            GeometryReader { geo in
                                RoundedRectangle(cornerRadius: 5)
                                    .fill(AppTheme.signal.opacity(0.13))
                                    .frame(width: geo.size.width * CGFloat(item.visitors) / CGFloat(maxVal))
                            }
                            HStack(spacing: 7) {
                                if isCountry {
                                    Text(countryFlag(item.key))
                                        .font(.system(size: 13))
                                }
                                Text(displayName(item.key, emptyLabel: emptyLabel, isCountry: isCountry))
                                    .font(.footnote.weight(.medium))
                                    .foregroundStyle(AppTheme.textPrimary)
                                    .lineLimit(1)
                            }
                            .padding(.horizontal, 12)
                        }
                        .frame(height: 36)

                        Text(formatNumber(item.pageViews))
                            .frame(width: 54, alignment: .trailing)

                        Text(formatNumber(item.visitors))
                            .frame(width: 64, alignment: .trailing)
                            .padding(.trailing, 12)
                    }
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .foregroundStyle(AppTheme.textSecondary)
                }
                Spacer().frame(height: 4)
            }
        }
        .appSurface()
    }

    // MARK: - Helpers

    @discardableResult
    private func startLoad(
        forceRefresh: Bool = false,
        debounce: Bool = false
    ) -> Task<Void, Never> {
        loadTask?.cancel()

        let task = Task { @MainActor in
            if debounce {
                do {
                    try await Task.sleep(for: .milliseconds(250))
                } catch {
                    return
                }
            }
            guard !Task.isCancelled else { return }
            await loadData(forceRefresh: forceRefresh)
        }
        loadTask = task
        return task
    }

    private func loadData(forceRefresh: Bool = false) async {
        guard let token = authManager.token else { return }
        let accountId = authManager.activeAccountId
        let result = await vm.load(
            token: token,
            hasLongAnalyticsHistory: authManager.hasLongAnalyticsHistory(for: accountId),
            forceRefresh: forceRefresh
        )
        guard result.applied else { return }
        if result.didUnlock {
            authManager.markLongAnalyticsHistoryAvailable(for: accountId)
        }
        if result.succeeded {
            lastUpdated = vm.lastUpdated ?? Date()
        } else if let cachedDate = vm.lastUpdated {
            lastUpdated = cachedDate
        }
    }

    private func formatNumber(_ value: Int) -> String {
        if value >= 1_000_000 { return String(format: "%.1fM", Double(value) / 1_000_000) }
        if value >= 1_000 { return String(format: "%.1fK", Double(value) / 1_000) }
        return "\(value)"
    }

    private func displayName(_ key: String, emptyLabel: String, isCountry: Bool) -> String {
        if key.isEmpty { return emptyLabel.isEmpty ? "Unknown" : emptyLabel }
        if isCountry { return countryName(key) }
        return key
    }

    private func countryFlag(_ code: String) -> String {
        guard code.count == 2 else { return "" }
        let base: UInt32 = 127397
        return code.uppercased().unicodeScalars.compactMap { UnicodeScalar(base + $0.value) }.map { String($0) }.joined()
    }

    private func countryName(_ code: String) -> String {
        Locale.current.localizedString(forRegionCode: code) ?? code
    }
}

// MARK: - Skeleton

struct AnalyticsSkeletonView: View {
    @Environment(\.horizontalSizeClass) private var hSize

    private var statsColumns: [GridItem] {
        Array(
            repeating: GridItem(.flexible(minimum: 0), spacing: 10, alignment: .top),
            count: 3
        )
    }

    private var panelColumns: [GridItem] {
        hSize == .regular
            ? [GridItem(.adaptive(minimum: 320, maximum: 480), spacing: 16)]
            : [GridItem(.flexible())]
    }

    private var chartHeight: CGFloat {
        hSize == .regular ? 340 : 260
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(AppTheme.skeletonStrong)
                        .frame(width: 100, height: 34)
                    RoundedRectangle(cornerRadius: 8)
                        .fill(AppTheme.skeletonStrong)
                        .frame(width: 120, height: 34)
                    Spacer()
                }

                LazyVGrid(columns: statsColumns, spacing: 10) {
                    ForEach(0..<3, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            RoundedRectangle(cornerRadius: 4)
                                .fill(AppTheme.skeletonStrong)
                                .frame(width: 60, height: 12)
                            RoundedRectangle(cornerRadius: 4)
                                .fill(AppTheme.skeletonStrong)
                                .frame(width: 44, height: 24)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .appSurface()
                    }
                }

                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(AppTheme.surface)
                    .frame(height: chartHeight)

                LazyVGrid(columns: panelColumns, spacing: 16) {
                    ForEach(0..<6, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            RoundedRectangle(cornerRadius: 4)
                                .fill(AppTheme.skeletonStrong)
                                .frame(width: 80, height: 14)
                            ForEach(0..<4, id: \.self) { _ in
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(AppTheme.skeleton)
                                    .frame(height: 36)
                            }
                        }
                        .padding(16)
                        .appSurface()
                    }
                }
            }
            .padding()
            .frame(maxWidth: hSize == .regular ? 1100 : .infinity)
            .frame(maxWidth: .infinity)
            .shimmering()
        }
    }
}
