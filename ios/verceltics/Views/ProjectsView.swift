import SwiftUI
import StoreKit

@Observable
@MainActor
final class ProjectsViewModel {
    var projects: [Project] = []
    var isLoading = true
    var error: String?

    private var hasLoaded = false

    func load(token: String, forceRefresh: Bool = false) async {
        if hasLoaded && !forceRefresh { return }
        
        // Show skeleton when it's the first load OR when we're switching accounts/forcing a refresh
        isLoading = true
        error = nil
        
        do {
            projects = try await VercelAPI(token: token).fetchProjects()
            hasLoaded = true
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
    @State private var showingAddAccount = false
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
                Color.black.ignoresSafeArea()

                if vm.isLoading {
                    ProjectsSkeletonView()
                } else if let error = vm.error {
                    ErrorStateView(message: error) {
                        Task { await loadProjects() }
                    }
                } else if vm.projects.isEmpty {
                    EmptyStateView(
                        icon: "folder",
                        title: "No Projects",
                        subtitle: "You don't have any Vercel projects yet."
                    )
                } else if filteredProjects.isEmpty {
                    EmptyStateView(
                        icon: "magnifyingglass",
                        title: "No matches",
                        subtitle: "Nothing in your projects matches \u{201C}\(searchText)\u{201D}."
                    )
                } else {
                    projectsList
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, isPresented: $isSearching, prompt: "Search projects...")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    accountSwitcherMenu
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
            .sheet(isPresented: $showingAddAccount) {
                LoginView()
            }
            .onChange(of: authManager.activeAccountId) { _, _ in
                Task { await refreshProjects() }
            }
        }
    }

    private var accountSwitcherMenu: some View {
        Menu {
            if authManager.accounts.isEmpty {
                Text("No accounts connected")
            } else {
                Section("Switch Account") {
                    ForEach(authManager.accounts) { account in
                        Button {
                            authManager.switchAccount(to: account.id)
                        } label: {
                            Label(
                                account.name,
                                systemImage: authManager.activeAccountId == account.id
                                    ? "checkmark.circle.fill"
                                    : "person.crop.circle"
                            )
                        }
                    }
                }
            }

            Section {
                Button {
                    showingAddAccount = true
                } label: {
                    Label("Add Account", systemImage: "plus.circle.fill")
                }
            }

            if authManager.activeAccount != nil {
                Section {
                    Button(role: .destructive) {
                        authManager.logout()
                    } label: {
                        Label("Sign Out Current", systemImage: "rectangle.portrait.and.arrow.right")
                    }

                    if authManager.accounts.count > 1 {
                        Button(role: .destructive) {
                            authManager.logoutAll()
                        } label: {
                            Label("Remove All Accounts", systemImage: "trash.fill")
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 5) {
                activeAccountAvatar
                Image(systemName: "chevron.down")
                    .font(.system(size: 8, weight: .heavy))
                    .foregroundStyle(.white.opacity(0.45))
            }
            .foregroundStyle(.white.opacity(0.82))
            .frame(height: 30)
            .accessibilityLabel("Switch Vercel account")
        }
    }

    @ViewBuilder
    private var activeAccountAvatar: some View {
        if let avatarURL = authManager.activeAccount?.avatarURL,
           let url = URL(string: avatarURL) {
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image
                        .resizable()
                        .scaledToFill()
                } else {
                    accountAvatarFallback
                }
            }
            .frame(width: 21, height: 21)
            .clipShape(Circle())
        } else {
            accountAvatarFallback
        }
    }

    private var accountAvatarFallback: some View {
        Image(systemName: "person.crop.circle.fill")
            .font(.system(size: 18, weight: .heavy))
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
            LazyVGrid(columns: gridColumns, spacing: 12) {
                ForEach(filteredProjects) { project in
                    let idx = filteredProjects.firstIndex(where: { $0.id == project.id }) ?? 0
                    Button {
                        openProject(project)
                    } label: {
                        ProjectCard(project: project, appearDelay: min(Double(idx), 11) * 0.04)
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .contextMenu {
                        if let domain = project.primaryDomain, let url = URL(string: "https://\(domain)") {
                            Button {
                                UIApplication.shared.open(url)
                            } label: {
                                Label("Open Website", systemImage: "globe")
                            }
                        }
                        
                        if let url = URL(string: "https://vercel.com/\(authManager.activeAccount?.name ?? "")/\(project.name)") {
                            Button {
                                UIApplication.shared.open(url)
                            } label: {
                                Label("View on Vercel", systemImage: "triangle.fill")
                            }
                        }
                        
                        Divider()
                        
                        Button {
                            openProject(project)
                        } label: {
                            Label("View Analytics", systemImage: "chart.bar.fill")
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
    var appearDelay: Double = 0
    @State private var pulse = false
    @State private var hasAppeared = false

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
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(1)

                        if isFreshDeploy {
                            Circle()
                                .fill(Color(red: 0.30, green: 0.85, blue: 0.55))
                                .frame(width: 6, height: 6)
                                .shadow(color: Color(red: 0.30, green: 0.85, blue: 0.55).opacity(0.5), radius: 3)
                                .opacity(pulse ? 0.45 : 1.0)
                                .animation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true), value: pulse)
                                .onAppear { pulse = true }
                        }
                    }

                    if let domain = project.primaryDomain {
                        Text(domain)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.white.opacity(0.4))
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .heavy))
                    .foregroundStyle(.white.opacity(0.18))
            }

            // Git repo + framework on same line
            HStack(spacing: 8) {
                if let link = project.link, let org = link.org, let repo = link.repo {
                    HStack(spacing: 5) {
                        Image(systemName: "chevron.left.forwardslash.chevron.right")
                            .font(.system(size: 8, weight: .heavy))
                        Text("\(org)/\(repo)")
                            .font(.system(size: 11, weight: .semibold))
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    .foregroundStyle(.white.opacity(0.5))
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(Color.white.opacity(0.06))
                    .clipShape(Capsule())
                    .overlay(Capsule().strokeBorder(Color.white.opacity(0.05), lineWidth: 0.5))
                }

                if let framework = project.framework {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(frameworkColor(framework))
                            .frame(width: 5, height: 5)
                            .shadow(color: frameworkColor(framework).opacity(0.5), radius: 2)
                        Text(framework.capitalized)
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.4))
                    }
                }
            }

            // Commit message + time
            if let deployment = project.lastDeployment {
                VStack(alignment: .leading, spacing: 4) {
                    if let message = deployment.commitMessage {
                        Text(message)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(.white.opacity(0.6))
                            .lineLimit(1)
                    }

                    if let date = deployment.date {
                        HStack(spacing: 5) {
                            Image(systemName: "clock")
                                .font(.system(size: 8, weight: .heavy))
                            Text(date.formatted(.relative(presentation: .named)))
                            Text("·")
                            Image(systemName: "arrow.triangle.branch")
                                .font(.system(size: 8, weight: .heavy))
                            Text("main")
                        }
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.3))
                    }
                }
            }
        }
        .padding(16)
        .background(
            ZStack {
                LinearGradient(
                    colors: [Color.white.opacity(0.07), Color.white.opacity(0.02)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                LinearGradient(
                    colors: [Color.white.opacity(0.04), .clear],
                    startPoint: .top,
                    endPoint: .center
                )
            }
        )
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(
                    LinearGradient(
                        colors: [Color.white.opacity(0.12), Color.white.opacity(0.04)],
                        startPoint: .top, endPoint: .bottom
                    ),
                    lineWidth: 0.5
                )
        )
        .opacity(hasAppeared ? 1 : 0)
        .offset(y: hasAppeared ? 0 : 10)
        .onAppear {
            withAnimation(.spring(response: 0.55, dampingFraction: 0.85).delay(appearDelay)) {
                hasAppeared = true
            }
        }
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
        .background(
            LinearGradient(
                colors: [Color.white.opacity(0.05), Color.white.opacity(0.02)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.white.opacity(0.06), lineWidth: 0.5)
        )
        .shimmering()
    }
}

// MARK: - Project Icon (favicon from domain)

struct ProjectIcon: View {
    let domain: String?
    let name: String
    @State private var loadedImage: Image?
    @State private var didFail = false

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
                loadedImage
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 40, height: 40)
                    .background(Color.white.opacity(0.04))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            } else if didFail {
                letterFallback
            } else {
                letterFallback
                    .opacity(0.5)
                    .overlay(
                        ProgressView()
                            .scaleEffect(0.4)
                    )
            }
        }
        .frame(width: 40, height: 40)
        .task {
            // Race: load favicon vs 18s timeout (SVG rasterisation chain
            // can be: fetch HTML -> fetch SVG -> proxy fetch -> render).
            await withTaskGroup(of: Void.self) { group in
                group.addTask { @MainActor in await loadFavicon() }
                group.addTask { @MainActor in
                    try? await Task.sleep(for: .seconds(18))
                    if loadedImage == nil { didFail = true }
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
                .font(.system(size: 18, weight: .bold, design: .rounded))
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
            loadedImage = image
            return
        }

        // 2. Scrape HTML <link> tags for any explicit icon paths
        if let scraped = await scrapeFaviconURLs(domain: domain) {
            for url in scraped {
                if let image = await fetchImage(from: url) {
                    loadedImage = image
                    return
                }
            }
        }

        // 3. Third-party services in parallel — these almost always return something
        if let image = await raceForFirstImage(fallbackServiceURLs, minSize: 16) {
            loadedImage = image
            return
        }

        didFail = true
    }

    private func raceForFirstImage(_ urls: [URL], minSize: CGFloat) async -> Image? {
        guard !urls.isEmpty else { return nil }
        return await withTaskGroup(of: Image?.self) { group in
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
                    return Image(uiImage: removeWhiteBackground(image))
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

    private func fetchImage(from url: URL) async -> Image? {
        guard let (data, contentType) = await fetchImageData(from: url) else { return nil }

        let uiImage: UIImage?
        if looksLikeSVG(data: data, contentType: contentType) {
            uiImage = await rasterizeRemoteSVG(originalURL: url)
        } else {
            uiImage = UIImage(data: data)
        }
        guard let image = uiImage,
              image.size.width >= 32 || image.size.height >= 32 else { return nil }
        let cleaned = removeWhiteBackground(image)
        return Image(uiImage: cleaned)
    }

    nonisolated private func removeWhiteBackground(_ image: UIImage) -> UIImage {
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
        let pageURL = URL(string: "https://\(domain)")!
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
        VStack(spacing: 18) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 38))
                .foregroundStyle(.white.opacity(0.18))
            Text(message)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(.white.opacity(0.5))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Button(action: retry) {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 11, weight: .bold))
                    Text("Retry")
                        .font(.system(size: 13, weight: .semibold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(Color.white.opacity(0.08))
                .clipShape(Capsule())
                .overlay(Capsule().stroke(Color.white.opacity(0.08), lineWidth: 0.5))
            }
            .buttonStyle(PressScaleButtonStyle())
        }
    }
}

struct EmptyStateView: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 38))
                .foregroundStyle(.white.opacity(0.18))
            Text(title)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
            Text(subtitle)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white.opacity(0.4))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
    }
}
