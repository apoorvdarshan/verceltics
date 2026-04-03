import SwiftUI

struct MainTabView: View {
    private enum AppTab: String, CaseIterable {
        case projects
        case about

        var title: String {
            switch self {
            case .projects: "Projects"
            case .about: "About"
            }
        }

        var icon: String {
            switch self {
            case .projects: "triangle.fill"
            case .about: "info.circle"
            }
        }
    }

    @State private var selectedTab: AppTab = .projects
    @Namespace private var tabSelection

    var body: some View {
        TabView(selection: $selectedTab) {
            ProjectsView()
                .tag(AppTab.projects)
                .tabItem { Label("Projects", systemImage: "triangle.fill") }
                .toolbar(.hidden, for: .tabBar)

            AboutView()
                .tag(AppTab.about)
                .tabItem { Label("About", systemImage: "info.circle") }
                .toolbar(.hidden, for: .tabBar)
        }
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 10) {
                ForEach(AppTab.allCases, id: \.rawValue) { tab in
                    Button {
                        withAnimation(.spring(response: 0.38, dampingFraction: 0.8)) {
                            selectedTab = tab
                        }
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: tab.icon)
                                .font(.system(size: 14, weight: .semibold))

                            if selectedTab == tab {
                                Text(tab.title)
                                    .font(.system(size: 14, weight: .semibold))
                                    .transition(.opacity.combined(with: .scale(scale: 0.95)))
                            }
                        }
                        .foregroundStyle(selectedTab == tab ? .black : .white.opacity(0.72))
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background {
                            if selectedTab == tab {
                                Capsule()
                                    .fill(.white)
                                    .matchedGeometryEffect(id: "selected_tab", in: tabSelection)
                            } else {
                                Capsule()
                                    .fill(.clear)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial, in: Capsule())
            .overlay(
                Capsule()
                    .stroke(Color.white.opacity(0.1), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.38), radius: 18, x: 0, y: 12)
            .padding(.horizontal, 22)
            .padding(.bottom, 12)
        }
        .tint(.white)
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }
}
