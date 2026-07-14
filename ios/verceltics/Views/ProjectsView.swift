import SwiftUI
import StoreKit

@Observable
@MainActor
final class ProjectsViewModel {
    private static var cachedProjects: [Int: [Project]] = [:]

    var projects: [Project] = []
    var isLoading = true
    var error: String?

    private var loadedCacheKey: Int?

    func load(token: String, forceRefresh: Bool = false) async {
        let cacheKey = token.hashValue
        if !forceRefresh, loadedCacheKey == cacheKey { return }
        if !forceRefresh, let cached = Self.cachedProjects[cacheKey] {
            projects = cached
            loadedCacheKey = cacheKey
            isLoading = false
            error = nil
            return
        }
        
        // Keep already loaded content visible while an explicit refresh runs.
        isLoading = projects.isEmpty
        error = nil
        
        do {
            projects = try await VercelAPI(token: token).fetchProjects()
            loadedCacheKey = cacheKey
            Self.cachedProjects[cacheKey] = projects
        } catch is CancellationError {
            // Tab switch — ignore, don't show error
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}

struct ProjectsView: View {
    var startWithSearch = false
    @Environment(AuthManager.self) private var authManager
    @Environment(PaywallManager.self) private var paywallManager
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.requestReview) private var requestReview
    @AppStorage("hasShownOnboardingRatePrompt") private var hasShownOnboardingRatePrompt = false
    @State private var vm = ProjectsViewModel()
    @State private var searchText = ""
    @State private var isSearching = false
    @State private var showPaywall = false
    @State private var navigationProjectId: String?
    @State private var pendingProjectId: String?

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
            .onAppear {
                if startWithSearch { isSearching = true }
            }
            .navigationDestination(item: $navigationProjectId) { id in
                if let project = vm.projects.first(where: { $0.id == id }) {
                    AnalyticsView(project: project)
                }
            }
            .sheet(isPresented: $showPaywall, onDismiss: handlePaywallDismiss) {
                PaywallView()
            }
            .onChange(of: authManager.activeAccountId) { _, _ in
                Task { await refreshProjects() }
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
                if let error = vm.error {
                    AppFeedbackBanner(
                        title: "Couldn’t refresh projects",
                        message: error,
                        actionTitle: "Try again"
                    ) {
                        Task { await refreshProjects() }
                    }
                }

                LazyVGrid(columns: gridColumns, spacing: 12) {
                    ForEach(filteredProjects) { project in
                        Button {
                            openProject(project)
                        } label: {
                            ProjectCard(project: project)
                        }
                        .buttonStyle(PressScaleButtonStyle())
                        .contextMenu {
                            projectContextMenu(project)
                        }
                    }
                }
            }
            .padding(.horizontal)
            .padding(.top, 4)
            .frame(maxWidth: hSize == .regular ? 1200 : .infinity)
            .frame(maxWidth: .infinity)
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
        try? await Task.sleep(for: .seconds(3))
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

    private var isFreshDeploy: Bool {
        guard let date = project.lastDeployment?.date else { return false }
        return Date().timeIntervalSince(date) < 1800 // 30 min
    }

    private static let frameworkColors: [String: Color] = [
        "nextjs": Color(white: 0.95),
        "next.js": Color(white: 0.95),
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
                                .fill(Color(red: 0.30, green: 0.85, blue: 0.55))
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
                        Text(framework.capitalized)
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
        .appSurface()
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
            .padding(.horizontal)
            .frame(maxWidth: hSize == .regular ? 1200 : .infinity)
            .frame(maxWidth: .infinity)
        }
    }
}

struct SkeletonCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.white.opacity(0.06))
                    .frame(width: 40, height: 40)

                VStack(alignment: .leading, spacing: 6) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.white.opacity(0.06))
                        .frame(width: 120, height: 14)
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.white.opacity(0.04))
                        .frame(width: 180, height: 10)
                }
            }

            RoundedRectangle(cornerRadius: 10)
                .fill(Color.white.opacity(0.04))
                .frame(width: 140, height: 20)

            RoundedRectangle(cornerRadius: 4)
                .fill(Color.white.opacity(0.04))
                .frame(width: 220, height: 10)

            RoundedRectangle(cornerRadius: 4)
                .fill(Color.white.opacity(0.04))
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

    private var directFaviconURLs: [URL] {
        guard let domain else { return [] }
        // Try www.* and bare host so single-host sites still resolve.
        // Skip the www variant when the host is already a subdomain — many
        // wildcard certs (e.g. *.vercel.app) only cover one level, so
        // www.<sub>.vercel.app fails ATS and stalls the connection pool.
        let dotCount = domain.filter { $0 == "." }.count
        let hosts: [String] = {
            if domain.hasPrefix("www.") {
                return [domain, String(domain.dropFirst(4))]
            }
            if dotCount >= 2 {
                return [domain]
            }
            return [domain, "www.\(domain)"]
        }()
        let paths = [
            "/apple-touch-icon.png",
            "/apple-touch-icon-precomposed.png",
            "/favicon-192x192.png",
            "/favicon-96x96.png",
            "/favicon.png",
            "/favicon.ico",
            "/icon.png",
            "/icon.svg",
        ]
        return hosts.flatMap { host in
            paths.compactMap { URL(string: "https://\(host)\($0)") }
        }
    }

    private var fallbackServiceURLs: [URL] {
        guard let domain else { return [] }
        // Pre-rasterise common favicon paths through weserv as additional race
        // entrants — covers SVG-only sites whose scrape→rasterize chain might
        // otherwise be the only way to land a PNG. Manually fully-encode so
        // URLSession sees an unambiguous URL.
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let weservRasterised = ["icon.svg", "favicon.svg", "favicon.ico", "favicon.png", "apple-touch-icon.png"]
            .compactMap { path -> URL? in
                let inner = "https://\(domain)/\(path)"
                guard let escaped = inner.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
                return URL(string: "https://images.weserv.nl/?url=\(escaped)&output=png&w=128&h=128&fit=contain")
            }
        return weservRasterised + [
            URL(string: "https://icons.duckduckgo.com/ip3/\(domain).ico"),
            URL(string: "https://www.google.com/s2/favicons?domain=\(domain)&sz=256"),
            URL(string: "https://icon.horse/icon/\(domain)"),
        ].compactMap { $0 }
    }

    var body: some View {
        Group {
            if let loadedImage {
                Image(uiImage: loadedImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 40, height: 40)
                    .background(Color.white.opacity(0.04))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            } else if didFail {
                letterFallback
            } else {
                letterFallback
            }
        }
        .frame(width: 40, height: 40)
        .task(id: domain) {
            loadedImage = nil
            didFail = false
            guard let domain else {
                didFail = true
                return
            }
            if let cached = Self.imageCache.object(forKey: domain as NSString) {
                loadedImage = cached
                return
            }
            if let failedAt = Self.failedAt[domain], Date().timeIntervalSince(failedAt) < 300 {
                didFail = true
                return
            }

            // Race: load favicon vs a bounded timeout (SVG rasterisation chain
            // can be: fetch HTML -> fetch SVG -> proxy fetch -> render).
            await withTaskGroup(of: Void.self) { group in
                group.addTask { @MainActor in await loadFavicon() }
                group.addTask { @MainActor in
                    try? await Task.sleep(for: .seconds(8))
                    if loadedImage == nil {
                        didFail = true
                        Self.failedAt[domain] = Date()
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

    private func colorForName(_ name: String) -> Color {
        let hash = abs(name.hashValue)
        let colors: [Color] = [
            .red, .orange, .yellow, .green, .mint,
            .teal, .cyan, .blue, .indigo, .purple, .pink
        ]
        return colors[hash % colors.count].opacity(0.7)
    }

    private func loadFavicon() async {
        guard let domain else { didFail = true; return }

        // 1. Race all direct paths in parallel — first valid image wins
        if let image = await raceForFirstImage(directFaviconURLs, minSize: 32) {
            store(image, for: domain)
            return
        }

        // 2. Scrape HTML <link> tags for any explicit icon paths
        if let scraped = await scrapeFaviconURLs(domain: domain) {
            for url in scraped {
                if let image = await fetchImage(from: url) {
                    store(image, for: domain)
                    return
                }
            }
        }

        // 3. Third-party services in parallel — these almost always return something
        if let image = await raceForFirstImage(fallbackServiceURLs, minSize: 16) {
            store(image, for: domain)
            return
        }

        didFail = true
        Self.failedAt[domain] = Date()
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
            for url in urls {
                group.addTask { @MainActor in
                    guard let (data, contentType) = await fetchImageData(from: url) else { return nil }
                    let uiImage: UIImage?
                    if looksLikeSVG(data: data, contentType: contentType) {
                        uiImage = await rasterizeRemoteSVG(originalURL: url)
                    } else {
                        uiImage = UIImage(data: data)
                    }
                    guard let image = uiImage,
                          image.size.width >= minSize || image.size.height >= minSize else { return nil }
                    return await Task.detached(priority: .utility) {
                        Self.removeWhiteBackground(image)
                    }.value
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
        var request = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad)
        request.timeoutInterval = 10
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")
        request.setValue("image/png,image/jpeg,image/svg+xml,image/*,*/*;q=0.8", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
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

    /// Rasterise a remote SVG via images.weserv.nl. UIImage can't decode SVG
    /// natively, so we route SVG URLs through a public proxy that renders
    /// them server-side and returns PNG bytes. Inner URL is fully percent-
    /// encoded so URLSession sees an unambiguous query string even when the
    /// original has its own ?<hash>.
    nonisolated private func rasterizeRemoteSVG(originalURL: URL) async -> UIImage? {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let escaped = originalURL.absoluteString.addingPercentEncoding(withAllowedCharacters: allowed),
              let proxy = URL(string: "https://images.weserv.nl/?url=\(escaped)&output=png&w=128&h=128&fit=contain"),
              let (data, ct) = await fetchImageData(from: proxy),
              !looksLikeSVG(data: data, contentType: ct),
              let uiImage = UIImage(data: data) else { return nil }
        return uiImage
    }

    private func fetchImage(from url: URL) async -> UIImage? {
        guard let (data, contentType) = await fetchImageData(from: url) else { return nil }

        let uiImage: UIImage?
        if looksLikeSVG(data: data, contentType: contentType) {
            uiImage = await rasterizeRemoteSVG(originalURL: url)
        } else {
            uiImage = UIImage(data: data)
        }
        guard let image = uiImage,
              image.size.width >= 32 || image.size.height >= 32 else { return nil }
        return await Task.detached(priority: .utility) {
            Self.removeWhiteBackground(image)
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
              pageURL.host != nil else { return nil }
        var request = URLRequest(url: pageURL)
        request.timeoutInterval = 5
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let html = String(data: data, encoding: .utf8) else { return nil }

        var favicons: [URL] = []
        // Find all <link> tags with rel containing "icon"
        let pattern = #"<link[^>]*rel=[\"'][^\"']*icon[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"']|<link[^>]*href=[\"']([^\"']+)[\"'][^>]*rel=[\"'][^\"']*icon[^\"']*[\"']"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return nil }
        let range = NSRange(html.startIndex..., in: html)
        let matches = regex.matches(in: html, range: range)

        for match in matches {
            let href: String
            if let r1 = Range(match.range(at: 1), in: html), !html[r1].isEmpty {
                href = String(html[r1])
            } else if let r2 = Range(match.range(at: 2), in: html) {
                href = String(html[r2])
            } else { continue }

            // Inline data:URI SVG — UIImage can't decode SVG and we have no
            // remote URL to feed the rasterizer. Skip; the fallback chain
            // picks up the slack.
            if href.lowercased().hasPrefix("data:image/svg+xml") { continue }

            // Percent-encode characters like spaces that the spec allows in
            // hrefs but URL(string:) rejects (e.g. "assets/calorie logo.png").
            let allowed = CharacterSet.urlQueryAllowed.union(.urlPathAllowed)
            let encoded = href.addingPercentEncoding(withAllowedCharacters: allowed) ?? href
            if let url = URL(string: encoded, relativeTo: pageURL)?.absoluteURL
                ?? URL(string: href, relativeTo: pageURL)?.absoluteURL {
                favicons.append(url)
            }
        }

        return favicons.isEmpty ? nil : favicons
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
