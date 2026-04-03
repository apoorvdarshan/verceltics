import SwiftUI

@Observable
@MainActor
final class ProjectsViewModel {
    var projects: [Project] = []
    var isLoading = true
    var error: String?

    func load(token: String) async {
        isLoading = true
        error = nil
        do {
            projects = try await VercelAPI(token: token).fetchProjects()
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}

struct ProjectsView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var vm = ProjectsViewModel()

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
            .task { await loadProjects() }
        }
    }

    private var projectsList: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                ForEach(vm.projects) { project in
                    NavigationLink(destination: AnalyticsView(project: project)) {
                        ProjectCard(project: project)
                    }
                }
            }
            .padding(.horizontal)
            .padding(.top, 4)
        }
        .refreshable { await loadProjects() }
    }

    private func loadProjects() async {
        guard let token = authManager.token else { return }
        await vm.load(token: token)
    }
}

// MARK: - Project Card (Vercel-style)

struct ProjectCard: View {
    let project: Project

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Top: icon + name + domain
            HStack(alignment: .top, spacing: 12) {
                // Project favicon from domain
                ProjectIcon(domain: project.primaryDomain, name: project.name)

                VStack(alignment: .leading, spacing: 3) {
                    Text(project.name)
                        .font(.headline)
                        .foregroundStyle(.white)

                    if let domain = project.primaryDomain {
                        Text(domain)
                            .font(.caption)
                            .foregroundStyle(.gray)
                    }
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.gray)
            }

            // Git repo badge
            if let link = project.link, let org = link.org, let repo = link.repo {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.left.forwardslash.chevron.right")
                        .font(.system(size: 9))
                    Text("\(org)/\(repo)")
                        .font(.caption2)
                }
                .foregroundStyle(.gray)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.white.opacity(0.06))
                .clipShape(Capsule())
            }

            // Commit message + time
            if let deployment = project.lastDeployment {
                VStack(alignment: .leading, spacing: 4) {
                    if let message = deployment.commitMessage {
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.8))
                            .lineLimit(1)
                    }

                    if let date = deployment.date {
                        HStack(spacing: 4) {
                            Text(date.formatted(.relative(presentation: .named)))
                            Text("on")
                            Image(systemName: "arrow.triangle.branch")
                                .font(.system(size: 9))
                            Text("main")
                        }
                        .font(.caption2)
                        .foregroundStyle(.gray)
                    }
                }
            }

            // Framework badge
            if let framework = project.framework {
                HStack(spacing: 4) {
                    Circle()
                        .fill(.green)
                        .frame(width: 6, height: 6)
                    Text(framework.capitalized)
                        .font(.caption2)
                        .foregroundStyle(.gray)
                }
            }
        }
        .padding(16)
        .background(Color.white.opacity(0.04))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(Color.white.opacity(0.06), lineWidth: 1)
        )
    }
}

// MARK: - Skeleton

struct ProjectsSkeletonView: View {
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                ForEach(0..<6, id: \.self) { _ in
                    SkeletonCard()
                }
            }
            .padding(.horizontal)
        }
    }
}

struct SkeletonCard: View {
    @State private var shimmer = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.white.opacity(shimmer ? 0.1 : 0.05))
                    .frame(width: 40, height: 40)

                VStack(alignment: .leading, spacing: 6) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.white.opacity(shimmer ? 0.1 : 0.05))
                        .frame(width: 120, height: 14)
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.white.opacity(shimmer ? 0.08 : 0.03))
                        .frame(width: 180, height: 10)
                }
            }

            RoundedRectangle(cornerRadius: 10)
                .fill(Color.white.opacity(shimmer ? 0.06 : 0.03))
                .frame(width: 140, height: 20)

            RoundedRectangle(cornerRadius: 4)
                .fill(Color.white.opacity(shimmer ? 0.08 : 0.03))
                .frame(width: 220, height: 10)

            RoundedRectangle(cornerRadius: 4)
                .fill(Color.white.opacity(shimmer ? 0.06 : 0.03))
                .frame(width: 100, height: 10)
        }
        .padding(16)
        .background(Color.white.opacity(0.04))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(Color.white.opacity(0.06), lineWidth: 1)
        )
        .onAppear {
            withAnimation(.easeInOut(duration: 1.0).repeatForever(autoreverses: true)) {
                shimmer = true
            }
        }
    }
}

// MARK: - Project Icon (favicon from domain)

struct ProjectIcon: View {
    let domain: String?
    let name: String
    @State private var loadedImage: Image?
    @State private var didFail = false

    private var faviconURLs: [URL] {
        guard let domain else { return [] }
        // icon.horse first (best quality, preserves transparency), then direct sources
        return [
            URL(string: "https://icon.horse/icon/\(domain)"),
            URL(string: "https://\(domain)/favicon.ico"),
            URL(string: "https://\(domain)/favicon.png"),
            URL(string: "https://\(domain)/icon.png"),
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
                ZStack {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Color.white.opacity(0.08))
                        .frame(width: 40, height: 40)
                    ProgressView()
                        .scaleEffect(0.5)
                }
            }
        }
        .frame(width: 40, height: 40)
        .task { await loadFavicon() }
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

        // First try known good sources
        for url in faviconURLs {
            if let image = await fetchImage(from: url) {
                loadedImage = image
                return
            }
        }

        // Scrape HTML <link> tags for favicon URLs
        if let scraped = await scrapeFaviconURLs(domain: domain) {
            for url in scraped {
                if let image = await fetchImage(from: url) {
                    loadedImage = image
                    return
                }
            }
        }

        // Last resort: Google favicon API (converts SVGs to PNG)
        // Strip white background Google may add to transparent favicons
        if let googleURL = URL(string: "https://www.google.com/s2/favicons?domain=\(domain)&sz=128"),
           let data = await fetchImageData(from: googleURL),
           let uiImage = UIImage(data: data),
           uiImage.size.width >= 32 {
            let cleaned = removeWhiteBackground(uiImage)
            loadedImage = Image(uiImage: cleaned)
            return
        }

        didFail = true
    }

    private func fetchImageData(from url: URL) async -> Data? {
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

    private func removeWhiteBackground(_ image: UIImage) -> UIImage {
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

        var urls: [URL] = []
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

            // Skip data: URIs (inline SVGs — can't render without WebView)
            if href.hasPrefix("data:") { continue }

            if href.hasPrefix("http") {
                if let url = URL(string: href) { urls.append(url) }
            } else {
                // Relative URL
                let path = href.hasPrefix("/") ? href : "/\(href)"
                if let url = URL(string: "https://\(domain)\(path)") { urls.append(url) }
            }
        }

        return urls.isEmpty ? nil : urls
    }
}

// MARK: - Reusable States

struct ErrorStateView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 40))
                .foregroundStyle(.gray)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.gray)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Button("Retry", action: retry)
                .font(.subheadline.bold())
                .foregroundStyle(.white)
                .padding(.horizontal, 24)
                .padding(.vertical, 10)
                .background(Color.white.opacity(0.1))
                .clipShape(Capsule())
        }
    }
}

struct EmptyStateView: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 40))
                .foregroundStyle(.gray)
            Text(title)
                .font(.headline)
                .foregroundStyle(.white)
            Text(subtitle)
                .font(.subheadline)
                .foregroundStyle(.gray)
        }
    }
}
