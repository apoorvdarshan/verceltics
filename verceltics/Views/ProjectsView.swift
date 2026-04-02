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
    @State private var showSettings = false

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
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape")
                            .foregroundStyle(.white)
                    }
                }
            }
            .sheet(isPresented: $showSettings) {
                SettingsSheet(onLogout: { authManager.logout() })
                    .presentationDetents([.medium])
            }
            .task { await loadProjects() }
        }
    }

    private var projectsList: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                ForEach(vm.projects) { project in
                    NavigationLink(destination: AnalyticsView(project: project)) {
                        ProjectRow(project: project)
                    }
                }
            }
            .padding()
        }
        .refreshable { await loadProjects() }
    }

    private func loadProjects() async {
        guard let token = authManager.token else { return }
        await vm.load(token: token)
    }
}

// MARK: - Project Row

struct ProjectRow: View {
    let project: Project

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "triangle.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(.white)
                Text(project.name)
                    .font(.headline)
                    .foregroundStyle(.white)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.gray)
            }

            if let domain = project.primaryDomain {
                HStack(spacing: 4) {
                    Image(systemName: "globe")
                        .font(.caption2)
                    Text(domain)
                        .font(.caption)
                }
                .foregroundStyle(.gray)
            }

            if let deployment = project.lastDeployment {
                HStack(spacing: 12) {
                    if let date = deployment.date {
                        HStack(spacing: 4) {
                            Image(systemName: "clock")
                                .font(.caption2)
                            Text(date.formatted(.relative(presentation: .named)))
                                .font(.caption)
                        }
                        .foregroundStyle(.gray)
                    }

                    if let message = deployment.commitMessage {
                        HStack(spacing: 4) {
                            Image(systemName: "text.quote")
                                .font(.caption2)
                            Text(message)
                                .font(.caption)
                                .lineLimit(1)
                        }
                        .foregroundStyle(.gray)
                    }
                }
            }
        }
        .padding(16)
        .background(Color.white.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Skeleton

struct ProjectsSkeletonView: View {
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                ForEach(0..<6, id: \.self) { _ in
                    SkeletonRow()
                }
            }
            .padding()
        }
    }
}

struct SkeletonRow: View {
    @State private var shimmer = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            RoundedRectangle(cornerRadius: 4)
                .fill(Color.white.opacity(shimmer ? 0.1 : 0.05))
                .frame(width: 160, height: 16)
            RoundedRectangle(cornerRadius: 4)
                .fill(Color.white.opacity(shimmer ? 0.08 : 0.03))
                .frame(width: 220, height: 12)
            RoundedRectangle(cornerRadius: 4)
                .fill(Color.white.opacity(shimmer ? 0.08 : 0.03))
                .frame(width: 180, height: 12)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.white.opacity(0.06))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .onAppear {
            withAnimation(.easeInOut(duration: 1.0).repeatForever(autoreverses: true)) {
                shimmer = true
            }
        }
    }
}

// MARK: - Settings Sheet

struct SettingsSheet: View {
    let onLogout: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 24) {
                    VStack(spacing: 8) {
                        Image(systemName: "triangle.fill")
                            .font(.system(size: 32))
                            .foregroundStyle(.white)
                        Text("Verceltics")
                            .font(.title3.bold())
                            .foregroundStyle(.white)
                    }
                    .padding(.top, 24)

                    Spacer()

                    Button(role: .destructive) {
                        onLogout()
                        dismiss()
                    } label: {
                        HStack {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                            Text("Sign Out")
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(Color.red.opacity(0.15))
                        .foregroundStyle(.red)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 32)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(.white)
                }
            }
        }
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
