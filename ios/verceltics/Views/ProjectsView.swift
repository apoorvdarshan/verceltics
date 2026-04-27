import SwiftUI

@Observable
@MainActor
final class ProjectsViewModel {
    var projects: [Project] = []
    var isLoading = true
    var error: String?

    private var hasLoaded = false

    func load(token: String, forceRefresh: Bool = false) async {
        if hasLoaded && !forceRefresh { return }
        isLoading = !hasLoaded
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
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var vm = ProjectsViewModel()
    @State private var searchText = ""
    @State private var isSearching = false

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
                } else {
                    projectsList
                }
            }
            .navigationTitle("Projects")
            .searchable(text: $searchText, isPresented: $isSearching, prompt: "Search projects...")
            .task { await loadProjects() }
            .onAppear {
                if startWithSearch { isSearching = true }
            }
        }
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
                    NavigationLink(destination: AnalyticsView(project: project)) {
                        ProjectCard(project: project)
                    }
                    .buttonStyle(PressScaleButtonStyle())
                }
            }
            .padding(.horizontal)
            .padding(.top, 4)
            .frame(maxWidth: hSize == .regular ? 1200 : .infinity)
            .frame(maxWidth: .infinity)
        }
        .refreshable { await refreshProjects() }
    }

    private func loadProjects() async {
        guard let token = authManager.token else { return }
        await vm.load(token: token)
    }

    private func refreshProjects() async {
        guard let token = authManager.token else { return }
        await vm.load(token: token, forceRefresh: true)
    }
}

// MARK: - Project Card (Vercel-style)

struct ProjectCard: View {
    let project: Project

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Top: icon + name + domain + chevron
            HStack(spacing: 14) {
                ProjectIcon(domain: project.primaryDomain, name: project.name)

                VStack(alignment: .leading, spacing: 3) {
                    Text(project.name)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(.white)

                    if let domain = project.displayDomain {
                        Text(domain)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(.white.opacity(0.35))
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.15))
            }

            // Git repo + framework on same line
            HStack(spacing: 8) {
                if let link = project.link, let org = link.org, let repo = link.repo {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left.forwardslash.chevron.right")
                            .font(.system(size: 8, weight: .bold))
                        Text("\(org)/\(repo)")
                            .font(.system(size: 11, weight: .medium))
                    }
                    .foregroundStyle(.white.opacity(0.4))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.white.opacity(0.06))
                    .clipShape(Capsule())
                }

                if let framework = project.framework {
                    HStack(spacing: 4) {
                        Circle()
                            .fill(.green.opacity(0.7))
                            .frame(width: 5, height: 5)
                        Text(framework.capitalized)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.white.opacity(0.35))
                    }
                }
            }

            // Commit message + time
            if let deployment = project.lastDeployment {
                VStack(alignment: .leading, spacing: 3) {
                    if let message = deployment.commitMessage {
                        Text(message)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(.white.opacity(0.6))
                            .lineLimit(1)
                    }

                    if let date = deployment.date {
                        HStack(spacing: 4) {
                            Text(date.formatted(.relative(presentation: .named)))
                            Text("on")
                            Image(systemName: "arrow.triangle.branch")
                                .font(.system(size: 8))
                            Text("main")
                        }
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.white.opacity(0.25))
                    }
                }
            }
        }
        .padding(16)
        .background(
            LinearGradient(
                colors: [Color.white.opacity(0.06), Color.white.opacity(0.02)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.white.opacity(0.08), lineWidth: 0.5)
        )
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
    private enum ScrapedFavicon {
        case remote(URL)
        case inlineSVG(UIImage)
    }

    let domain: String?
    let name: String
    @State private var loadedImage: Image?
    @State private var didFail = false

    private var directFaviconURLs: [URL] {
        guard let domain else { return [] }
        // Try www.* and bare host so single-host sites still resolve
        let hosts: [String] = {
            if domain.hasPrefix("www.") {
                return [domain, String(domain.dropFirst(4))]
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
        ]
        return hosts.flatMap { host in
            paths.compactMap { URL(string: "https://\(host)\($0)") }
        }
    }

    private var fallbackServiceURLs: [URL] {
        guard let domain else { return [] }
        return [
            URL(string: "https://icons.duckduckgo.com/ip3/\(domain).ico"),
            URL(string: "https://www.google.com/s2/favicons?domain=\(domain)&sz=256"),
            URL(string: "https://unavatar.io/\(domain)?ttl=1d"),
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
            // Race: load favicon vs 12s timeout
            await withTaskGroup(of: Void.self) { group in
                group.addTask { @MainActor in await loadFavicon() }
                group.addTask { @MainActor in
                    try? await Task.sleep(for: .seconds(12))
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
            for favicon in scraped {
                switch favicon {
                case .remote(let url):
                    if let image = await fetchImage(from: url) {
                        loadedImage = image
                        return
                    }
                case .inlineSVG(let uiImage):
                    if hasVisiblePixels(uiImage) {
                        loadedImage = Image(uiImage: removeWhiteBackground(uiImage))
                        return
                    }
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
                group.addTask {
                    guard let data = await fetchImageData(from: url),
                          let uiImage = UIImage(data: data),
                          uiImage.size.width >= minSize || uiImage.size.height >= minSize else { return nil }
                    return Image(uiImage: removeWhiteBackground(uiImage))
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

    private func hasVisiblePixels(_ image: UIImage) -> Bool {
        guard let cgImage = image.cgImage else { return false }
        let width = cgImage.width
        let height = cgImage.height
        guard width > 0, height > 0 else { return false }
        let bytesPerPixel = 4
        let bytesPerRow = bytesPerPixel * width
        var pixelData = [UInt8](repeating: 0, count: width * height * bytesPerPixel)
        guard let context = CGContext(
            data: &pixelData, width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return false }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        // Check if any pixel has alpha > 10
        for i in stride(from: 3, to: min(pixelData.count, 10000 * 4), by: bytesPerPixel) {
            if pixelData[i] > 10 { return true }
        }
        return false
    }

    nonisolated private func fetchImageData(from url: URL) async -> Data? {
        var request = URLRequest(url: url)
        request.timeoutInterval = 5
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse,
              (200...299).contains(http.statusCode),
              data.count > 50 else { return nil }
        return data
    }

    private func fetchImage(from url: URL) async -> Image? {
        guard let data = await fetchImageData(from: url) else { return nil }
        guard let uiImage = UIImage(data: data) else { return nil }
        guard uiImage.size.width >= 32 || uiImage.size.height >= 32 else { return nil }
        let cleaned = removeWhiteBackground(uiImage)
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

    @MainActor
    private func renderSVGDataURI(_ dataURI: String) -> UIImage? {
        guard dataURI.lowercased().hasPrefix("data:image/svg+xml"),
              let commaIndex = dataURI.firstIndex(of: ",") else { return nil }

        let metadata = String(dataURI[..<commaIndex]).lowercased()
        let payload = String(dataURI[dataURI.index(after: commaIndex)...])

        let svgMarkup: String
        if metadata.contains(";base64") {
            guard let decoded = Data(base64Encoded: payload.removingPercentEncoding ?? payload),
                  let string = String(data: decoded, encoding: .utf8) else { return nil }
            svgMarkup = string
        } else {
            guard let decoded = payload.removingPercentEncoding else { return nil }
            svgMarkup = decoded
        }

        let canvasSize = CGSize(width: 128, height: 128)
        let html = """
        <html>
        <head>
        <style>
        html, body {
            margin: 0;
            padding: 0;
            width: \(Int(canvasSize.width))px;
            height: \(Int(canvasSize.height))px;
            background: transparent;
            overflow: hidden;
        }
        svg {
            display: block;
            width: 100%;
            height: 100%;
        }
        </style>
        </head>
        <body>\(svgMarkup)</body>
        </html>
        """

        guard let htmlData = html.data(using: .utf8),
              let attributed = try? NSAttributedString(
                  data: htmlData,
                  options: [
                      .documentType: NSAttributedString.DocumentType.html,
                      .characterEncoding: String.Encoding.utf8.rawValue,
                  ],
                  documentAttributes: nil
              ) else { return nil }

        let format = UIGraphicsImageRendererFormat.default()
        format.opaque = false

        let renderer = UIGraphicsImageRenderer(size: canvasSize, format: format)
        return renderer.image { _ in
            UIColor.clear.setFill()
            UIRectFill(CGRect(origin: .zero, size: canvasSize))
            attributed.draw(
                with: CGRect(origin: .zero, size: canvasSize),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                context: nil
            )
        }
    }

    private func scrapeFaviconURLs(domain: String) async -> [ScrapedFavicon]? {
        let pageURL = URL(string: "https://\(domain)")!
        var request = URLRequest(url: pageURL)
        request.timeoutInterval = 5
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let html = String(data: data, encoding: .utf8) else { return nil }

        var favicons: [ScrapedFavicon] = []
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

            if href.lowercased().hasPrefix("data:image/svg+xml") {
                if let image = renderSVGDataURI(href) {
                    favicons.append(.inlineSVG(image))
                }
                continue
            }

            if let url = URL(string: href, relativeTo: pageURL)?.absoluteURL {
                favicons.append(.remote(url))
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
