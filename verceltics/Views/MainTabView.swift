import SwiftUI

struct MainTabView: View {
    @State private var selectedTab: AppTab = .projects

    var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $selectedTab) {
                ProjectsView()
                    .tag(AppTab.projects)
                    .tabItem {
                        Label("Projects", systemImage: "triangle.fill")
                    }

                AboutView()
                    .tag(AppTab.about)
                    .tabItem {
                        Label("About", systemImage: "info.circle")
                    }
            }
            .toolbar(.hidden, for: .tabBar)

            floatingTabBar
                .padding(.horizontal, 24)
                .padding(.bottom, 14)
        }
        .safeAreaInset(edge: .bottom) {
            Color.clear
                .frame(height: 84)
        }
        .tint(.white)
    }

    private var floatingTabBar: some View {
        HStack(spacing: 10) {
            tabButton(for: .projects, title: "Projects", systemImage: "triangle.fill")
            tabButton(for: .about, title: "About", systemImage: "info.circle")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(
            Capsule()
                .stroke(Color.white.opacity(0.12), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.22), radius: 20, y: 10)
        .animation(.spring(response: 0.34, dampingFraction: 0.8), value: selectedTab)
    }

    private func tabButton(for tab: AppTab, title: String, systemImage: String) -> some View {
        let isSelected = selectedTab == tab

        return Button {
            withAnimation(.spring(response: 0.34, dampingFraction: 0.8)) {
                selectedTab = tab
            }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.system(size: 15, weight: isSelected ? .bold : .regular))
                Text(title)
                    .font(.system(size: 14, weight: isSelected ? .semibold : .regular))
            }
            .foregroundStyle(isSelected ? .white : .white.opacity(0.72))
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(
                Capsule()
                    .fill(isSelected ? Color.white.opacity(0.12) : Color.clear)
            )
        }
        .buttonStyle(.plain)
    }
}

private enum AppTab: Hashable {
    case projects
    case about
}
