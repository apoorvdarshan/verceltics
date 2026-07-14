import SwiftUI

@Observable
@MainActor
final class AnalyticsViewModel {
    private struct CachedAnalytics {
        let data: AnalyticsData
        let projectDetails: Project
        let domains: [String]
        let recentDeployments: [RecentDeployment]
        let hasLongAnalyticsHistory: Bool
        let analyticsUnavailableMessage: String?
    }

    private static var cache: [String: CachedAnalytics] = [:]

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
    private var loadGeneration = 0

    init(project: Project) {
        self.project = project
        self.projectDetails = project
        self.domains = project.primaryDomain.map { [$0] } ?? []
    }

    func load(
        token: String,
        hasLongAnalyticsHistory: Bool,
        forceRefresh: Bool = false
    ) async -> (didUnlock: Bool, applied: Bool, succeeded: Bool) {
        let cacheKey = [
            token.hashValue.description,
            project.id,
            project.teamId ?? "",
            selectedRange.rawValue,
            selectedEnvironment.rawValue
        ].joined(separator: "|")

        if !forceRefresh, let cached = Self.cache[cacheKey] {
            data = cached.data
            projectDetails = cached.projectDetails
            domains = cached.domains
            recentDeployments = cached.recentDeployments
            self.hasLongAnalyticsHistory = hasLongAnalyticsHistory || cached.hasLongAnalyticsHistory
            analyticsUnavailableMessage = cached.analyticsUnavailableMessage
            displayedRange = selectedRange
            displayedEnvironment = selectedEnvironment
            error = nil
            isLoading = false
            return (false, true, true)
        }

        loadGeneration += 1
        let generation = loadGeneration
        isLoading = true
        error = nil
        analyticsUnavailableMessage = nil
        var didUnlockLongAnalyticsHistory = false
        var loadedData = AnalyticsData()
        var loadedError: String?
        var loadedUnavailableMessage: String?
        let api = VercelAPI(token: token)
        let pid = project.id
        let tid = project.teamId
        
        let range = selectedRange
        
        let from = range.fromDate
        let to = range.toDate
        let prevFrom = range.previousFromDate
        let prevTo = range.previousToDate
        
        let env = selectedEnvironment.queryValue

        async let fetchedProject: Project? = try? await api.fetchProject(id: pid, teamId: tid)
        async let fetchedDomains: [String] = (try? await api.fetchProjectDomains(projectId: pid, teamId: tid)) ?? []
        async let fetchedDeployments: [RecentDeployment] = (try? await api.fetchDeployments(projectId: pid, teamId: tid)) ?? []

        do {
            async let overview = api.fetchOverview(projectId: pid, teamId: tid, from: from, to: to, environment: env)
            async let previous = api.fetchPreviousOverview(projectId: pid, teamId: tid, from: prevFrom, to: prevTo, environment: env)
            async let timeseries = api.fetchTimeseries(projectId: pid, teamId: tid, from: from, to: to, environment: env)
            async let pages = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "path", environment: env)
            async let referrers = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "referrer", environment: env)
            async let countries = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "country", environment: env)
            async let devices = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "device_type", environment: env)
            async let browsers = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "client_name", environment: env)
            async let os = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "os_name", environment: env)
            async let utmSources = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "utm", environment: env)
            async let routes = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "route", environment: env)
            async let hostnames = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "hostname", environment: env)
            async let events = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "event_name", environment: env)
            async let flags = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "flags", environment: env)
            async let queryParams = api.fetchBreakdown(projectId: pid, teamId: tid, from: from, to: to, groupBy: "query_params", environment: env)

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
            if range.isPro {
                didUnlockLongAnalyticsHistory = true
            }

        } catch let apiError as APIError {
            switch apiError {
            case .unauthorized:
                loadedError = apiError.localizedDescription
            case .serverError(let code) where code == 400 || code == 404:
                loadedUnavailableMessage = "Vercel Web Analytics is not available through token access right now. Project details, domains, and deployments are still shown below."
            case .serverError(let code):
                loadedUnavailableMessage = "Vercel Web Analytics returned HTTP \(code). Project details, domains, and deployments are still shown below."
            default:
                loadedError = apiError.localizedDescription
            }
        } catch {
            loadedError = error.localizedDescription
        }

        let loadedProject = await fetchedProject
        let domains = await fetchedDomains
        let deployments = await fetchedDeployments

        guard generation == loadGeneration else { return (false, false, false) }
        if loadedError == nil || data.overview == nil {
            data = loadedData
        }
        if loadedError == nil {
            displayedRange = range
            displayedEnvironment = selectedEnvironment
        }
        error = loadedError
        analyticsUnavailableMessage = loadedUnavailableMessage
        self.hasLongAnalyticsHistory = hasLongAnalyticsHistory || didUnlockLongAnalyticsHistory
        if let loadedProject { projectDetails = loadedProject }
        self.domains = domains.isEmpty ? project.primaryDomain.map { [$0] } ?? [] : domains
        recentDeployments = deployments

        isLoading = false
        if loadedError == nil {
            Self.cache[cacheKey] = CachedAnalytics(
                data: data,
                projectDetails: projectDetails,
                domains: self.domains,
                recentDeployments: recentDeployments,
                hasLongAnalyticsHistory: self.hasLongAnalyticsHistory,
                analyticsUnavailableMessage: analyticsUnavailableMessage
            )
        }
        return (didUnlockLongAnalyticsHistory, true, loadedError == nil)
    }
}

struct AnalyticsView: View {
    let project: Project
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var vm: AnalyticsViewModel
    @State private var lastUpdated: Date?
    @State private var refreshSpin: Double = 0
    @State private var hasLoadedInitialData = false

    init(project: Project) {
        self.project = project
        _vm = State(wrappedValue: AnalyticsViewModel(project: project))
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            if vm.isLoading && !hasVisibleContent {
                AnalyticsSkeletonView()
            } else if let error = vm.error, !hasVisibleContent {
                ErrorStateView(message: error) {
                    Task { await loadData(forceRefresh: true) }
                }
            } else {
                analyticsContent
            }
        }
        .navigationTitle(project.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    if !reduceMotion {
                        withAnimation(.easeInOut(duration: 0.45)) { refreshSpin += 360 }
                    }
                    Task { await loadData(forceRefresh: true) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 14, weight: .semibold))
                        .rotationEffect(.degrees(refreshSpin))
                        .foregroundStyle(.white.opacity(0.7))
                }
                .disabled(vm.isLoading)
                .sensoryFeedback(.impact(weight: .light), trigger: refreshSpin)
            }
        }
        .task {
            guard !hasLoadedInitialData else { return }
            hasLoadedInitialData = true
            await loadData()
        }
        .onChange(of: vm.selectedRange) {
            Task { await loadData() }
        }
        .onChange(of: vm.selectedEnvironment) {
            Task { await loadData() }
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
        Array(
            repeating: GridItem(.flexible(minimum: 0), spacing: 10, alignment: .top),
            count: 3
        )
    }

    private var chartHeight: CGFloat {
        hSize == .regular ? 340 : 260
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
                        Task { await loadData(forceRefresh: true) }
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
        .refreshable { await loadData(forceRefresh: true) }
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
                                .foregroundStyle(.white.opacity(0.72))
                                .lineLimit(1)
                                .truncationMode(.middle)

                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 8, weight: .semibold))
                                .foregroundStyle(Color.blue.opacity(0.75))
                                .frame(width: 17, height: 17)
                                .background(Color.blue.opacity(0.12))
                                .clipShape(Circle())
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(AppTheme.surfaceRaised)
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(Color.white.opacity(0.09), lineWidth: 0.5))
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .accessibilityLabel("Open \(domain)")
                }
                Spacer()
            }

            HStack(spacing: 8) {
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
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.white.opacity(0.5))
                        Text(vm.selectedRange.controlLabel)
                            .font(.system(size: 13, weight: .bold))
                            .lineLimit(1)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.4))
                    }
                    .padding(.horizontal, 11)
                    .padding(.vertical, 9)
                    .background(AppTheme.surfaceRaised)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous).strokeBorder(AppTheme.stroke, lineWidth: 0.5))
                }
                .buttonStyle(PressScaleButtonStyle())

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
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.white.opacity(0.5))
                        Text(vm.selectedEnvironment.controlLabel)
                            .font(.system(size: 13, weight: .bold))
                            .lineLimit(1)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.4))
                    }
                    .padding(.horizontal, 11)
                    .padding(.vertical, 9)
                    .background(AppTheme.surfaceRaised)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous).strokeBorder(AppTheme.stroke, lineWidth: 0.5))
                }
                .buttonStyle(PressScaleButtonStyle())
                .layoutPriority(1)

                Spacer()
                if vm.isLoading {
                    ProgressView().controlSize(.small).tint(AppTheme.textSecondary)
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

            Divider().overlay(Color.white.opacity(0.06))

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
            DeploymentDetailView(project: project, deployment: deployment)
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
                        .foregroundStyle(.white.opacity(0.82))
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
                .foregroundStyle(.white.opacity(0.38))
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

            Divider().overlay(Color.white.opacity(0.06))

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
            lastUpdated = Date()
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
                        .fill(Color.white.opacity(0.06))
                        .frame(width: 100, height: 34)
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color.white.opacity(0.06))
                        .frame(width: 120, height: 34)
                    Spacer()
                }

                LazyVGrid(columns: statsColumns, spacing: 10) {
                    ForEach(0..<3, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.white.opacity(0.06))
                                .frame(width: 60, height: 12)
                            RoundedRectangle(cornerRadius: 4)
                                .fill(Color.white.opacity(0.08))
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
                                .fill(Color.white.opacity(0.06))
                                .frame(width: 80, height: 14)
                            ForEach(0..<4, id: \.self) { _ in
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(Color.white.opacity(0.04))
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
