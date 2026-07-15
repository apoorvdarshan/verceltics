import SwiftUI
import StoreKit
import ImageIO
import Darwin

private final class FaviconConnectionGuard: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let expectedHost: String
    private let expectedPort: Int
    private let lock = NSLock()
    private var publicEndpointResult: Bool?

    init(url: URL) {
        expectedHost = url.host?.lowercased() ?? ""
        expectedPort = url.port ?? 443
    }

    func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 8
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard let url = request.url,
              url.scheme?.lowercased() == "https",
              url.host?.lowercased() == expectedHost,
              (url.port ?? 443) == expectedPort,
              url.user == nil,
              url.password == nil else {
            completionHandler(nil)
            return
        }
        completionHandler(request)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didFinishCollecting metrics: URLSessionTaskMetrics
    ) {
        let remoteAddresses = metrics.transactionMetrics.compactMap(\.remoteAddress)
        let isPublic = !remoteAddresses.isEmpty
            && remoteAddresses.allSatisfy(FaviconHostSafety.isPublicIPAddress)
        lock.withLock { publicEndpointResult = isPublic }
    }

    func waitForPublicEndpoint() async -> Bool {
        // Metrics are delivered at task completion, immediately after the final
        // response bytes. Poll briefly so validation is tied to the connection
        // URLSession actually used rather than to an earlier DNS lookup.
        for _ in 0..<25 {
            let result = lock.withLock { publicEndpointResult }
            if let result { return result }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return false
    }
}

@Observable
@MainActor
final class ProjectsViewModel {
    private struct CachedProjects {
        let projects: [Project]
        let warning: String?
        let updatedAt: Date
    }

    private static var cachedProjects: [String: CachedProjects] = [:]
    private static let cacheLifetime: TimeInterval = 3 * 60

    var projects: [Project] = []
    var isLoading = true
    var isRefreshing = false
    var error: String?
    var warning: String?

    private var loadedCacheKey: String?
    private var lastUpdatedAt: Date?
    private var activeRequestKey: String?
    private var loadGeneration = 0

    init(token: String? = nil) {
        guard let token, !token.isEmpty else { return }
        let cacheKey = Self.cacheKey(for: token)
        guard let cached = Self.cachedProjects[cacheKey] else { return }

        projects = cached.projects
        warning = cached.warning
        lastUpdatedAt = cached.updatedAt
        loadedCacheKey = cacheKey
        isLoading = false
    }

    func load(token: String, forceRefresh: Bool = false) async {
        let cacheKey = Self.cacheKey(for: token)
        guard activeRequestKey != cacheKey else { return }

        if loadedCacheKey != cacheKey {
            if let cached = Self.cachedProjects[cacheKey] {
                projects = cached.projects
                warning = cached.warning
                lastUpdatedAt = cached.updatedAt
                isLoading = false
            } else {
                // Never carry one credential's projects into another account.
                projects = []
                warning = nil
                lastUpdatedAt = nil
                isLoading = true
            }
            loadedCacheKey = cacheKey
            error = nil
        }

        if !forceRefresh,
           let lastUpdatedAt,
           Date.now.timeIntervalSince(lastUpdatedAt) < Self.cacheLifetime {
            return
        }

        loadGeneration += 1
        let generation = loadGeneration
        activeRequestKey = cacheKey
        // Cached and previously loaded content stays visible during stale or
        // manual refreshes; only a first load presents a skeleton.
        isLoading = lastUpdatedAt == nil
        isRefreshing = !isLoading
        error = nil
        defer {
            if generation == loadGeneration {
                activeRequestKey = nil
                isLoading = false
                isRefreshing = false
            }
        }

        do {
            let api = VercelAPI(token: token)
            let loadedProjects = try await api.fetchProjects()
            let loadWarning = await api.lastProjectLoadWarning
            guard generation == loadGeneration else { return }
            let updatedAt = Date.now
            projects = loadedProjects
            warning = loadWarning
            lastUpdatedAt = updatedAt
            loadedCacheKey = cacheKey
            Self.cachedProjects[cacheKey] = CachedProjects(
                projects: loadedProjects,
                warning: loadWarning,
                updatedAt: updatedAt
            )
        } catch is CancellationError {
            // Tab switch — ignore, don't show error
        } catch {
            guard generation == loadGeneration else { return }
            self.error = error.localizedDescription
        }
    }

    private static func cacheKey(for token: String) -> String {
        CredentialCacheScope.fingerprint(fields: ["vercel-projects", token])
    }
}

struct ProjectsView: View {
    var startWithSearch = false
    var searchRequestID = 0
    var backgroundRefreshRequestID = 0
    @Environment(AuthManager.self) private var authManager
    @Environment(PaywallManager.self) private var paywallManager
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.requestReview) private var requestReview
    @AppStorage("hasShownOnboardingRatePrompt") private var hasShownOnboardingRatePrompt = false
    @State private var vm: ProjectsViewModel
    @State private var searchText = ""
    @State private var isSearching = false
    @State private var showPaywall = false
    @State private var navigationProjectId: String?
    @State private var pendingProjectId: String?

    init(
        startWithSearch: Bool = false,
        searchRequestID: Int = 0,
        backgroundRefreshRequestID: Int = 0,
        initialToken: String? = nil
    ) {
        self.startWithSearch = startWithSearch
        self.searchRequestID = searchRequestID
        self.backgroundRefreshRequestID = backgroundRefreshRequestID
        _vm = State(initialValue: ProjectsViewModel(token: initialToken))
    }

    private var filteredProjects: [Project] {
        let sorted = vm.projects.sorted {
            ($0.lastDeployment?.createdAt ?? $0.updatedAt ?? 0) >
            ($1.lastDeployment?.createdAt ?? $1.updatedAt ?? 0)
        }
        if searchText.isEmpty { return sorted }
        return sorted.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            ($0.primaryDomain ?? "").localizedCaseInsensitiveContains(searchText) ||
            ($0.framework ?? "").localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AppTheme.canvas.ignoresSafeArea()

                if vm.isLoading {
                    ProjectsSkeletonView()
                } else if let error = vm.error, vm.projects.isEmpty {
                    ErrorStateView(message: error) {
                        Task { await loadProjects() }
                    }
                } else if let warning = vm.warning, vm.projects.isEmpty {
                    ErrorStateView(message: warning) {
                        Task { await refreshProjects() }
                    }
                } else if vm.projects.isEmpty {
                    EmptyStateView(
                        icon: "folder",
                        title: "No projects",
                        subtitle: "Create a Vercel project, then refresh this screen.",
                        actionTitle: "Open Vercel"
                    ) {
                        if let url = URL(string: "https://vercel.com/new") {
                            UIApplication.shared.open(url)
                        }
                    }
                } else if filteredProjects.isEmpty {
                    EmptyStateView(
                        icon: "magnifyingglass",
                        title: "No matches",
                        subtitle: "Nothing in your projects matches \u{201C}\(searchText)\u{201D}.",
                        actionTitle: "Clear search"
                    ) { searchText = "" }
                } else {
                    projectsList
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, isPresented: $isSearching, prompt: "Search projects...")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    ProviderAccountMenu()
                }
            }
            .task { await loadProjects() }
            .onChange(of: backgroundRefreshRequestID) { _, _ in
                Task { await loadProjects() }
            }
            .onAppear {
                if startWithSearch { isSearching = true }
            }
            .onChange(of: searchRequestID) { _, _ in
                isSearching = true
            }
            .navigationDestination(item: $navigationProjectId) { id in
                if let project = vm.projects.first(where: { $0.id == id }) {
                    AnalyticsView(project: project, initialToken: authManager.token)
                }
            }
            .sheet(isPresented: $showPaywall, onDismiss: handlePaywallDismiss) {
                PaywallView()
                    .presentationSizing(.form)
            }
        }
    }

    private func handlePaywallDismiss() {
        // If the user just subscribed (or owns lifetime), continue into the
        // analytics they originally tapped.
        if paywallManager.hasActiveSubscription, let id = pendingProjectId {
            navigationProjectId = id
        }
        pendingProjectId = nil
    }

    private var gridColumns: [GridItem] {
        hSize == .regular
            ? [GridItem(.adaptive(minimum: 340, maximum: 520), spacing: 12)]
            : [GridItem(.flexible())]
    }

    private var projectsList: some View {
        ScrollView {
            VStack(spacing: 12) {
                if let error = authManager.error {
                    AppFeedbackBanner(
                        title: "Saved account change failed",
                        message: error,
                        icon: "lock.trianglebadge.exclamationmark.fill",
                        tint: AppTheme.danger
                    )
                }
                if let error = vm.error {
                    AppFeedbackBanner(
                        title: "Couldn’t refresh projects",
                        message: error,
                        actionTitle: "Try again"
                    ) {
                        Task { await refreshProjects() }
                    }
                }
                if let warning = vm.warning {
                    AppFeedbackBanner(
                        title: "Some project scopes did not load",
                        message: warning,
                        icon: "exclamationmark.triangle.fill",
                        tint: AppTheme.warning
                    )
                }

                LazyVGrid(columns: gridColumns, spacing: 12) {
                    ForEach(filteredProjects) { project in
                        Button {
                            openProject(project)
                        } label: {
                            ProjectCard(project: project)
                        }
                        .buttonStyle(PressScaleButtonStyle())
                        .hoverEffect(.highlight)
                        .contextMenu {
                            projectContextMenu(project)
                        }
                    }
                }
            }
            .padding(.horizontal, AppLayout.pagePadding(for: hSize))
            .padding(.top, 4)
            .appContentWidth(AppLayout.dashboardMaxWidth, horizontalSizeClass: hSize)
        }
        .refreshable { await refreshProjects() }
    }

    @ViewBuilder
    private func projectContextMenu(_ project: Project) -> some View {
        if let domain = project.primaryDomain, let url = URL(string: "https://\(domain)") {
            Button { UIApplication.shared.open(url) } label: {
                Label("Open website", systemImage: "globe")
            }
            Button { UIPasteboard.general.string = "https://\(domain)" } label: {
                Label("Copy URL", systemImage: "doc.on.doc")
            }
        }

        if let url = URL(string: "https://vercel.com/\(authManager.activeAccount?.name ?? "")/\(project.name)") {
            Button { UIApplication.shared.open(url) } label: {
                Label("View on Vercel", systemImage: "triangle.fill")
            }
        }

        Divider()
        Button { openProject(project) } label: {
            Label("View analytics", systemImage: "chart.bar.fill")
        }
    }

    private func openProject(_ project: Project) {
        if paywallManager.hasActiveSubscription {
            navigationProjectId = project.id
        } else {
            pendingProjectId = project.id
            showPaywall = true
        }
    }

    private func loadProjects() async {
        guard let token = authManager.token else { return }
        await vm.load(token: token)
        await maybeRequestReview()
    }

    /// Fire the native rating prompt once after the user's projects first
    /// successfully load — fires for free users too. The system caps to ~3
    /// prompts per user per year regardless.
    private func maybeRequestReview() async {
        guard !hasShownOnboardingRatePrompt,
              vm.error == nil,
              !vm.projects.isEmpty else { return }
        do {
            try await Task.sleep(for: .seconds(3))
        } catch {
            return
        }
        guard !Task.isCancelled else { return }
        requestReview()
        hasShownOnboardingRatePrompt = true
    }

    private func refreshProjects() async {
        guard let token = authManager.token else { return }
        await vm.load(token: token, forceRefresh: true)
    }
}

// MARK: - Project Card (Vercel-style)

struct ProjectCard: View {
    let project: Project
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    private var isFreshDeploy: Bool {
        guard let date = project.lastDeployment?.date else { return false }
        return Date().timeIntervalSince(date) < 1800 // 30 min
    }

    private static let frameworkColors: [String: Color] = [
        "nextjs": AppTheme.textPrimary,
        "next.js": AppTheme.textPrimary,
        "astro": Color(red: 1.00, green: 0.45, blue: 0.30),
        "vite": Color(red: 0.60, green: 0.50, blue: 0.95),
        "gatsby": Color(red: 0.85, green: 0.40, blue: 0.95),
        "nuxtjs": Color(red: 0.30, green: 0.85, blue: 0.55),
        "nuxt": Color(red: 0.30, green: 0.85, blue: 0.55),
        "sveltekit": Color(red: 1.00, green: 0.45, blue: 0.30),
        "svelte": Color(red: 1.00, green: 0.45, blue: 0.30),
        "remix": Color(red: 0.30, green: 0.75, blue: 0.95),
        "create-react-app": Color(red: 0.30, green: 0.75, blue: 0.95),
        "vue": Color(red: 0.30, green: 0.85, blue: 0.55),
        "angular": Color(red: 0.95, green: 0.30, blue: 0.40),
        "hugo": Color(red: 0.95, green: 0.45, blue: 0.95),
        "hexo": Color(red: 0.30, green: 0.85, blue: 0.55),
        "blitzjs": Color(red: 0.55, green: 0.50, blue: 0.95),
        "eleventy": Color(red: 1.00, green: 0.85, blue: 0.30),
    ]

    private func frameworkColor(_ framework: String) -> Color {
        Self.frameworkColors[framework.lowercased()] ?? Color(red: 0.30, green: 0.85, blue: 0.55)
    }

    private func frameworkName(_ framework: String) -> String {
        switch framework.lowercased() {
        case "nextjs", "next.js": "Next.js"
        case "nuxtjs", "nuxt": "Nuxt"
        case "sveltekit": "SvelteKit"
        case "create-react-app": "React"
        case "blitzjs": "Blitz.js"
        default: framework.prefix(1).uppercased() + framework.dropFirst()
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Top: icon + name + domain + chevron
            HStack(spacing: 14) {
                ProjectIcon(domain: project.primaryDomain, name: project.name)

                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 7) {
                        Text(project.name)
                            .font(.headline)
                            .foregroundStyle(AppTheme.textPrimary)
                            .lineLimit(1)

                        if isFreshDeploy {
                            Circle()
                                .fill(AppTheme.success)
                                .frame(width: 6, height: 6)
                                .opacity(reduceMotion ? 1 : (pulse ? 0.45 : 1.0))
                                .animation(reduceMotion ? nil : .easeInOut(duration: 1.2).repeatForever(autoreverses: true), value: pulse)
                                .onAppear { if !reduceMotion { pulse = true } }
                        }
                    }

                    if let domain = project.primaryDomain {
                        Text(domain)
                            .font(.footnote)
                            .foregroundStyle(AppTheme.textSecondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textTertiary)
            }

            if let link = project.link, let org = link.org, let repo = link.repo {
                Label("\(org)/\(repo)", systemImage: "chevron.left.forwardslash.chevron.right")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(AppTheme.textSecondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            HStack(spacing: 14) {
                if let framework = project.framework {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(frameworkColor(framework))
                            .frame(width: 6, height: 6)
                        Text(frameworkName(framework))
                    }
                }
                if let scope = project.sourceScope, scope.isTeam {
                    Label(scope.name, systemImage: "person.3.fill")
                        .lineLimit(1)
                }
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(AppTheme.textSecondary)

            // Commit message + time
            if let deployment = project.lastDeployment {
                AppInsetDivider(leading: 0)

                VStack(alignment: .leading, spacing: 4) {
                    if let message = deployment.commitMessage {
                        Text(message)
                            .font(.subheadline)
                            .foregroundStyle(AppTheme.textPrimary)
                            .lineLimit(1)
                    }

                    if let date = deployment.date {
                        HStack(spacing: 5) {
                            Image(systemName: "clock")
                                .font(.system(size: 8, weight: .semibold))
                            Text(date.formatted(.relative(presentation: .named)))
                        }
                        .font(.caption)
                        .foregroundStyle(AppTheme.textSecondary)
                    }
                }
            }
        }
        .padding(16)
        .frame(minHeight: horizontalSizeClass == .regular ? 184 : nil, alignment: .top)
        .appSurface()
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Skeleton

struct ProjectsSkeletonView: View {
    @Environment(\.horizontalSizeClass) private var hSize

    private var gridColumns: [GridItem] {
        hSize == .regular
            ? [GridItem(.adaptive(minimum: 340, maximum: 520), spacing: 12)]
            : [GridItem(.flexible())]
    }

    var body: some View {
        ScrollView {
            LazyVGrid(columns: gridColumns, spacing: 12) {
                ForEach(0..<6, id: \.self) { _ in
                    SkeletonCard()
                }
            }
            .padding(.horizontal, AppLayout.pagePadding(for: hSize))
            .appContentWidth(AppLayout.dashboardMaxWidth, horizontalSizeClass: hSize)
        }
    }
}

struct SkeletonCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 10)
                    .fill(AppTheme.skeletonStrong)
                    .frame(width: 40, height: 40)

                VStack(alignment: .leading, spacing: 6) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(AppTheme.skeletonStrong)
                        .frame(width: 120, height: 14)
                    RoundedRectangle(cornerRadius: 4)
                        .fill(AppTheme.skeleton)
                        .frame(width: 180, height: 10)
                }
            }

            RoundedRectangle(cornerRadius: 10)
                .fill(AppTheme.skeleton)
                .frame(width: 140, height: 20)

            RoundedRectangle(cornerRadius: 4)
                .fill(AppTheme.skeleton)
                .frame(width: 220, height: 10)

            RoundedRectangle(cornerRadius: 4)
                .fill(AppTheme.skeleton)
                .frame(width: 100, height: 10)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .appSurface()
        .shimmering()
    }
}

// MARK: - Project Icon (favicon from domain)

struct ProjectIcon: View {
    let domain: String?
    let name: String
    @State private var loadedImage: UIImage?
    @State private var didFail = false

    @MainActor private static let imageCache = NSCache<NSString, UIImage>()
    @MainActor private static var failedAt: [String: Date] = [:]

    private var safeDomain: String? {
        guard let domain else { return nil }
        return Self.publicHost(from: domain)
    }

    private var directFaviconURLs: [URL] {
        guard let safeDomain else { return [] }
        return ["/apple-touch-icon.png", "/favicon.ico"].compactMap {
            URL(string: "https://\(safeDomain)\($0)")
        }
    }

    var body: some View {
        Group {
            if let loadedImage {
                Image(uiImage: loadedImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 40, height: 40)
                    .background(AppTheme.surfaceRaised)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            } else if didFail {
                letterFallback
            } else {
                loadingPlaceholder
            }
        }
        .frame(width: 40, height: 40)
        .task(id: domain) {
            loadedImage = nil
            didFail = false
            guard let safeDomain else {
                didFail = true
                return
            }
            if let cached = Self.imageCache.object(forKey: safeDomain as NSString) {
                loadedImage = cached
                return
            }
            if let failedAt = Self.failedAt[safeDomain], Date().timeIntervalSince(failedAt) < 300 {
                didFail = true
                return
            }

            // Race direct/same-origin favicon discovery against a bounded timeout.
            await withTaskGroup(of: Void.self) { group in
                group.addTask { @MainActor in await loadFavicon() }
                group.addTask { @MainActor in
                    try? await Task.sleep(for: .seconds(8))
                    if loadedImage == nil {
                        didFail = true
                        Self.failedAt[safeDomain] = Date()
                    }
                }
                await group.next()
                group.cancelAll()
            }
        }
    }

    private var letterFallback: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10)
                .fill(colorForName(name))
                .frame(width: 40, height: 40)

            Text(String(name.prefix(1)).uppercased())
                .font(.system(size: 18, weight: .bold, design: .default))
                .foregroundStyle(.white)
        }
    }

    private var loadingPlaceholder: some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(AppTheme.surfaceRaised)
            .overlay {
                Image(systemName: "globe")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(AppTheme.textTertiary)
            }
            .shimmering()
    }

    private func colorForName(_ name: String) -> Color {
        let hash = name.unicodeScalars.reduce(UInt64(5381)) { value, scalar in
            ((value << 5) &+ value) &+ UInt64(scalar.value)
        }
        let colors: [Color] = [
            Color(red: 0.33, green: 0.50, blue: 0.76),
            Color(red: 0.29, green: 0.61, blue: 0.56),
            Color(red: 0.62, green: 0.44, blue: 0.73),
            Color(red: 0.73, green: 0.45, blue: 0.34),
            Color(red: 0.48, green: 0.53, blue: 0.65),
            Color(red: 0.65, green: 0.43, blue: 0.55),
        ]
        return colors[Int(hash % UInt64(colors.count))]
    }

    private func loadFavicon() async {
        guard let safeDomain else { didFail = true; return }

        // 1. Race all direct paths in parallel — first valid image wins
        if let image = await raceForFirstImage(directFaviconURLs, minSize: 32) {
            store(image, for: safeDomain)
            return
        }

        // 2. Scrape HTML <link> tags for any explicit icon paths
        if let scraped = await scrapeFaviconURLs(domain: safeDomain) {
            for url in scraped {
                if let image = await fetchImage(from: url) {
                    store(image, for: safeDomain)
                    return
                }
            }
        }

        didFail = true
        Self.failedAt[safeDomain] = Date()
    }

    private func store(_ image: UIImage, for domain: String) {
        Self.imageCache.setObject(image, forKey: domain as NSString)
        Self.failedAt[domain] = nil
        loadedImage = image
        didFail = false
    }

    private func raceForFirstImage(_ urls: [URL], minSize: CGFloat) async -> UIImage? {
        guard !urls.isEmpty else { return nil }
        return await withTaskGroup(of: UIImage?.self) { group in
            for url in urls.prefix(2) {
                group.addTask {
                    guard let (data, contentType) = await fetchImageData(from: url),
                          !looksLikeSVG(data: data, contentType: contentType) else { return nil }
                    return await Self.decodeImage(data, minSize: minSize)
                }
            }
            for await result in group {
                if let result {
                    group.cancelAll()
                    return result
                }
            }
            return nil
        }
    }

    nonisolated private func fetchImageData(from url: URL) async -> (Data, String?)? {
        guard url.scheme?.lowercased() == "https",
              url.host != nil,
              url.user == nil,
              url.password == nil else { return nil }
        var request = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad)
        request.timeoutInterval = 5
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")
        request.setValue("image/png,image/jpeg,image/webp,image/*;q=0.8", forHTTPHeaderField: "Accept")
        let connectionGuard = FaviconConnectionGuard(url: url)
        let session = connectionGuard.makeSession()
        defer { session.finishTasksAndInvalidate() }
        guard await FaviconHostSafety.resolvesOnlyToPublicAddresses(host: url.host ?? ""),
              let (data, response) = try? await ProviderRequestSecurity.data(
                for: request,
                using: session,
                maximumResponseBytes: 2_000_000
              ),
              await connectionGuard.waitForPublicEndpoint(),
              let http = response as? HTTPURLResponse,
              (200...299).contains(http.statusCode),
              data.count > 50 else { return nil }
        return (data, http.value(forHTTPHeaderField: "Content-Type"))
    }

    nonisolated private func looksLikeSVG(data: Data, contentType: String?) -> Bool {
        if let ct = contentType?.lowercased(), ct.contains("svg") { return true }
        let prefix = data.prefix(400)
        if let s = String(data: prefix, encoding: .utf8)?.lowercased() {
            return s.contains("<svg")
        }
        return false
    }

    private func fetchImage(from url: URL) async -> UIImage? {
        guard let (data, contentType) = await fetchImageData(from: url),
              !looksLikeSVG(data: data, contentType: contentType) else { return nil }
        return await Self.decodeImage(data, minSize: 32)
    }

    nonisolated private static func decodeImage(_ data: Data, minSize: CGFloat) async -> UIImage? {
        await Task.detached(priority: .utility) {
            guard let source = CGImageSourceCreateWithData(
                data as CFData,
                [kCGImageSourceShouldCache: false] as CFDictionary
            ),
            let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
            let pixelWidth = properties[kCGImagePropertyPixelWidth] as? NSNumber,
            let pixelHeight = properties[kCGImagePropertyPixelHeight] as? NSNumber,
            max(pixelWidth.doubleValue, pixelHeight.doubleValue) >= Double(minSize) else { return nil }

            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 256,
                kCGImageSourceShouldCacheImmediately: true,
            ]
            guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(
                source,
                0,
                options as CFDictionary
            ) else { return nil }
            let image = UIImage(cgImage: thumbnail)
            return removeWhiteBackground(image)
        }.value
    }

    nonisolated private static func removeWhiteBackground(_ image: UIImage) -> UIImage {
        guard let cgImage = image.cgImage else { return image }
        let width = cgImage.width
        let height = cgImage.height
        guard width > 0, height > 0 else { return image }

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bytesPerPixel = 4
        let bytesPerRow = bytesPerPixel * width
        let bitmapInfo = CGBitmapInfo.byteOrder32Big.union(
            CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        )
        var pixelData = [UInt8](repeating: 0, count: width * height * bytesPerPixel)

        guard let context = CGContext(
            data: &pixelData,
            width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: bitmapInfo.rawValue
        ) else { return image }

        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        let borderThreshold = 245
        let cleanupThreshold = 230
        let neutralSpreadThreshold = 18

        func offset(forX x: Int, y: Int) -> Int {
            (y * width + x) * bytesPerPixel
        }

        func isNearWhiteBackground(at offset: Int, threshold: Int) -> Bool {
            let r = Int(pixelData[offset])
            let g = Int(pixelData[offset + 1])
            let b = Int(pixelData[offset + 2])
            let a = Int(pixelData[offset + 3])
            let minChannel = min(r, g, b)
            let maxChannel = max(r, g, b)
            return a > 240 && minChannel >= threshold && (maxChannel - minChannel) <= neutralSpreadThreshold
        }

        // Only strip the matte when the outer border is mostly white.
        var whiteBorderPixels = 0
        var totalBorderPixels = 0

        for x in 0..<width {
            totalBorderPixels += 1
            if isNearWhiteBackground(at: offset(forX: x, y: 0), threshold: borderThreshold) {
                whiteBorderPixels += 1
            }

            if height > 1 {
                totalBorderPixels += 1
                if isNearWhiteBackground(at: offset(forX: x, y: height - 1), threshold: borderThreshold) {
                    whiteBorderPixels += 1
                }
            }
        }

        if height > 2 {
            for y in 1..<(height - 1) {
                totalBorderPixels += 1
                if isNearWhiteBackground(at: offset(forX: 0, y: y), threshold: borderThreshold) {
                    whiteBorderPixels += 1
                }
                if width > 1 {
                    totalBorderPixels += 1
                    if isNearWhiteBackground(at: offset(forX: width - 1, y: y), threshold: borderThreshold) {
                        whiteBorderPixels += 1
                    }
                }
            }
        }

        guard totalBorderPixels > 0 else { return image }
        let whiteBorderRatio = Double(whiteBorderPixels) / Double(totalBorderPixels)
        guard whiteBorderRatio >= 0.65 else { return image }

        // Flood-fill only the border-connected matte so we keep any internal white artwork.
        var stack: [(x: Int, y: Int)] = []
        var visited = [Bool](repeating: false, count: width * height)

        func enqueue(_ x: Int, _ y: Int) {
            let index = y * width + x
            guard !visited[index] else { return }
            let pixelOffset = offset(forX: x, y: y)
            guard isNearWhiteBackground(at: pixelOffset, threshold: cleanupThreshold) else { return }
            visited[index] = true
            stack.append((x, y))
        }

        for x in 0..<width {
            enqueue(x, 0)
            if height > 1 {
                enqueue(x, height - 1)
            }
        }

        if height > 2 {
            for y in 1..<(height - 1) {
                enqueue(0, y)
                if width > 1 {
                    enqueue(width - 1, y)
                }
            }
        }

        var removedPixels = 0
        while let (x, y) = stack.popLast() {
            let pixelOffset = offset(forX: x, y: y)
            pixelData[pixelOffset] = 0
            pixelData[pixelOffset + 1] = 0
            pixelData[pixelOffset + 2] = 0
            pixelData[pixelOffset + 3] = 0
            removedPixels += 1

            if x > 0 { enqueue(x - 1, y) }
            if x + 1 < width { enqueue(x + 1, y) }
            if y > 0 { enqueue(x, y - 1) }
            if y + 1 < height { enqueue(x, y + 1) }
        }

        guard removedPixels > 0 else { return image }

        guard let newContext = CGContext(
            data: &pixelData,
            width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: bitmapInfo.rawValue
        ), let newCGImage = newContext.makeImage() else { return image }

        return UIImage(cgImage: newCGImage, scale: image.scale, orientation: image.imageOrientation)
    }

    private func scrapeFaviconURLs(domain: String) async -> [URL]? {
        guard let pageURL = URL(string: "https://\(domain)"),
              pageURL.scheme == "https",
              let pageHost = pageURL.host?.lowercased() else { return nil }
        var request = URLRequest(url: pageURL)
        request.timeoutInterval = 5
        request.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")
        let connectionGuard = FaviconConnectionGuard(url: pageURL)
        let session = connectionGuard.makeSession()
        defer { session.finishTasksAndInvalidate() }
        guard await FaviconHostSafety.resolvesOnlyToPublicAddresses(host: pageHost),
              let (data, response) = try? await ProviderRequestSecurity.data(
                for: request,
                using: session,
                maximumResponseBytes: 512_000
              ),
              await connectionGuard.waitForPublicEndpoint(),
              let http = response as? HTTPURLResponse,
              (200...299).contains(http.statusCode),
              let html = String(data: data, encoding: .utf8) else { return nil }

        var favicons: [URL] = []
        // Find all <link> tags with rel containing "icon"
        let pattern = #"<link[^>]*rel=[\"'][^\"']*icon[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"']|<link[^>]*href=[\"']([^\"']+)[\"'][^>]*rel=[\"'][^\"']*icon[^\"']*[\"']"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return nil }
        let range = NSRange(html.startIndex..., in: html)
        let matches = regex.matches(in: html, range: range)

        for match in matches.prefix(8) {
            let href: String
            if let r1 = Range(match.range(at: 1), in: html), !html[r1].isEmpty {
                href = String(html[r1])
            } else if let r2 = Range(match.range(at: 2), in: html) {
                href = String(html[r2])
            } else { continue }

            if href.lowercased().hasPrefix("data:") { continue }

            // Percent-encode characters like spaces that the spec allows in
            // hrefs but URL(string:) rejects (e.g. "assets/calorie logo.png").
            let allowed = CharacterSet.urlQueryAllowed.union(.urlPathAllowed)
            let encoded = href.addingPercentEncoding(withAllowedCharacters: allowed) ?? href
            if let url = URL(string: encoded, relativeTo: pageURL)?.absoluteURL
                ?? URL(string: href, relativeTo: pageURL)?.absoluteURL,
               url.scheme?.lowercased() == "https",
               url.host?.lowercased() == pageHost,
               (url.port ?? 443) == 443,
               url.user == nil,
               url.password == nil,
               !url.pathExtension.lowercased().contains("svg") {
                favicons.append(url)
            }
            if favicons.count == 2 { break }
        }

        return favicons.isEmpty ? nil : favicons
    }

    nonisolated private static func publicHost(from rawValue: String) -> String? {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !trimmed.isEmpty else { return nil }

        let candidate: URL?
        if trimmed.contains("://") {
            candidate = URL(string: trimmed)
        } else {
            candidate = URL(string: "https://\(trimmed)")
        }

        guard let candidate,
              candidate.scheme?.lowercased() == "https",
              let host = candidate.host?.lowercased(),
              candidate.user == nil,
              candidate.password == nil,
              candidate.port == nil || candidate.port == 443,
              host.contains("."),
              !host.hasSuffix(".local"),
              !host.hasSuffix(".localhost"),
              !host.hasSuffix(".internal"),
              !host.hasSuffix(".lan"),
              !host.hasSuffix(".home"),
              !host.contains(":") else { return nil }

        let octets = host.split(separator: ".").compactMap { Int($0) }
        if octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) {
            let first = octets[0]
            let second = octets[1]
            let isPrivate = first == 0 || first == 10 || first == 127 || first >= 224
                || (first == 100 && (64...127).contains(second))
                || (first == 169 && second == 254)
                || (first == 172 && (16...31).contains(second))
                || (first == 192 && second == 168)
            guard !isPrivate else { return nil }
        }

        return host
    }
}

nonisolated enum FaviconHostSafety {
    static func resolvesOnlyToPublicAddresses(host: String) async -> Bool {
        let normalized = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return false }

        return await Task.detached(priority: .utility) {
            guard !Task.isCancelled else { return false }
            var hints = addrinfo(
                ai_flags: AI_ADDRCONFIG,
                ai_family: AF_UNSPEC,
                ai_socktype: SOCK_STREAM,
                ai_protocol: Int32(IPPROTO_TCP),
                ai_addrlen: 0,
                ai_canonname: nil,
                ai_addr: nil,
                ai_next: nil
            )
            var results: UnsafeMutablePointer<addrinfo>?
            guard getaddrinfo(normalized, nil, &hints, &results) == 0,
                  let firstResult = results else { return false }
            defer { freeaddrinfo(firstResult) }

            var foundAddress = false
            var current: UnsafeMutablePointer<addrinfo>? = firstResult
            while let entry = current {
                guard !Task.isCancelled else { return false }
                let address = entry.pointee
                current = address.ai_next
                guard address.ai_family == AF_INET || address.ai_family == AF_INET6,
                      let socketAddress = address.ai_addr else { continue }

                var numericHost = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                guard getnameinfo(
                    socketAddress,
                    address.ai_addrlen,
                    &numericHost,
                    socklen_t(numericHost.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                ) == 0 else { return false }
                foundAddress = true
                guard isPublicIPAddress(String(cString: numericHost)) else { return false }
            }
            return foundAddress
        }.value
    }

    static func isPublicIPAddress(_ rawAddress: String) -> Bool {
        var ipv4 = in_addr()
        if inet_pton(AF_INET, rawAddress, &ipv4) == 1 {
            let value = UInt32(bigEndian: ipv4.s_addr)
            let octets = [
                UInt8((value >> 24) & 0xff),
                UInt8((value >> 16) & 0xff),
                UInt8((value >> 8) & 0xff),
                UInt8(value & 0xff),
            ]
            return isPublicIPv4(octets)
        }

        var ipv6 = in6_addr()
        if inet_pton(AF_INET6, rawAddress, &ipv6) == 1 {
            let bytes = withUnsafeBytes(of: &ipv6) { Array($0) }
            return isPublicIPv6(bytes)
        }
        return false
    }

    private static func isPublicIPv4(_ bytes: [UInt8]) -> Bool {
        guard bytes.count == 4 else { return false }
        let first = Int(bytes[0])
        let second = Int(bytes[1])
        let third = Int(bytes[2])

        if first == 0 || first == 10 || first == 127 || first >= 224 { return false }
        if first == 100 && (64...127).contains(second) { return false }
        if first == 169 && second == 254 { return false }
        if first == 172 && (16...31).contains(second) { return false }
        if first == 192 && second == 168 { return false }
        if first == 192 && second == 0 && third <= 2 { return false }
        if first == 198 && (second == 18 || second == 19 || second == 51) { return false }
        if first == 203 && second == 0 && third == 113 { return false }
        return true
    }

    private static func isPublicIPv6(_ bytes: [UInt8]) -> Bool {
        guard bytes.count == 16 else { return false }
        if bytes.allSatisfy({ $0 == 0 }) { return false }
        if bytes.dropLast().allSatisfy({ $0 == 0 }) && bytes.last == 1 { return false }
        if bytes[0] & 0xfe == 0xfc { return false } // Unique-local fc00::/7.
        if bytes[0] == 0xfe && bytes[1] & 0xc0 == 0x80 { return false } // Link-local fe80::/10.
        if bytes[0] == 0xfe && bytes[1] & 0xc0 == 0xc0 { return false } // Deprecated site-local.
        if bytes[0] == 0xff { return false } // Multicast.
        if bytes[0...3].elementsEqual([0x20, 0x01, 0x0d, 0xb8]) { return false } // Documentation.

        let isIPv4Mapped = bytes[0..<10].allSatisfy({ $0 == 0 })
            && bytes[10] == 0xff && bytes[11] == 0xff
        let isIPv4Compatible = bytes[0..<12].allSatisfy({ $0 == 0 })
        let isWellKnownNAT64 = bytes[0...11].elementsEqual([
            0x00, 0x64, 0xff, 0x9b, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ])
        if isIPv4Mapped || isIPv4Compatible || isWellKnownNAT64 {
            return isPublicIPv4(Array(bytes.suffix(4)))
        }
        return true
    }
}

// MARK: - Reusable States

struct ErrorStateView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        AppEmptyState(
            icon: "exclamationmark.triangle.fill",
            title: "Couldn’t load data",
            message: message,
            actionTitle: "Try again",
            action: retry
        )
    }
}

struct EmptyStateView: View {
    let icon: String
    let title: String
    let subtitle: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        AppEmptyState(
            icon: icon,
            title: title,
            message: subtitle,
            actionTitle: actionTitle,
            action: action
        )
    }
}
