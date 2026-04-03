import SwiftUI

struct AboutView: View {
    @Environment(AuthManager.self) private var authManager

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 14) {
                        ZStack {
                            Circle()
                                .fill(Color.white.opacity(0.08))
                                .frame(width: 128, height: 128)
                                .blur(radius: 22)

                            Circle()
                                .fill(Color.blue.opacity(0.18))
                                .frame(width: 112, height: 112)
                                .blur(radius: 26)

                            Circle()
                                .fill(
                                    LinearGradient(
                                        colors: [
                                            Color.white.opacity(0.12),
                                            Color.white.opacity(0.03)
                                        ],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 94, height: 94)

                            Image(systemName: "triangle.fill")
                                .font(.system(size: 42, weight: .bold))
                                .foregroundStyle(.white)
                        }

                        Text("Verceltics")
                            .font(.system(size: 28, weight: .bold))
                            .foregroundStyle(.white)

                        Text("Analytics viewer for Vercel")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(.white.opacity(0.6))

                        Text("v1.0.0")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.42))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
                    .padding(.horizontal, 18)
                    .background(
                        RoundedRectangle(cornerRadius: 28, style: .continuous)
                            .fill(Color.white.opacity(0.045))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 28, style: .continuous)
                            .stroke(
                                LinearGradient(
                                    colors: [
                                        Color.white.opacity(0.16),
                                        Color.white.opacity(0.04)
                                    ],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 1
                            )
                    )
                }
                .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 10, trailing: 0))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)

                Section {
                    LinkRow(
                        icon: "globe",
                        title: "Source Code",
                        subtitle: "github.com/apoorvdarshan/verceltics",
                        url: "https://github.com/apoorvdarshan/verceltics"
                    )

                    LinkRow(
                        icon: "exclamationmark.triangle",
                        title: "Report an Issue",
                        subtitle: "Open a GitHub issue",
                        url: "https://github.com/apoorvdarshan/verceltics/issues"
                    )

                    LinkRow(
                        icon: "envelope",
                        title: "Contact",
                        subtitle: "ad13dtu@gmail.com",
                        url: "mailto:ad13dtu@gmail.com"
                    )
                } header: {
                    sectionHeader(title: "Links", subtitle: "Source, support, and contact")
                }

                Section {
                    LinkRow(
                        icon: "doc.text",
                        title: "Privacy Policy",
                        subtitle: "Coming soon",
                        url: nil
                    )

                    LinkRow(
                        icon: "doc.plaintext",
                        title: "Terms of Service",
                        subtitle: "Coming soon",
                        url: nil
                    )

                    LinkRow(
                        icon: "checkmark.seal",
                        title: "License",
                        subtitle: "MIT License",
                        url: "https://github.com/apoorvdarshan/verceltics/blob/main/LICENSE"
                    )
                } header: {
                    sectionHeader(title: "Legal", subtitle: "Policies and licensing")
                }

                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Verceltics is an open-source iOS app that lets you browse your Vercel projects and view web analytics including visitors, page views, bounce rate, referrers, countries, devices, and more.")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(.white.opacity(0.72))

                        Text("Built with SwiftUI and Swift Charts. No third-party dependencies.")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(.white.opacity(0.52))
                    }
                    .padding(18)
                    .background(
                        RoundedRectangle(cornerRadius: 22, style: .continuous)
                            .fill(Color.white.opacity(0.045))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 22, style: .continuous)
                            .stroke(Color.white.opacity(0.06), lineWidth: 1)
                    )
                } header: {
                    sectionHeader(title: "About", subtitle: "What the app includes")
                }
                .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)

                Section {
                    Button(role: .destructive) {
                        authManager.logout()
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                                .font(.system(size: 14, weight: .semibold))
                            Text("Sign Out")
                                .font(.system(size: 15, weight: .semibold))
                            Spacer()
                        }
                        .foregroundStyle(.red)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 16)
                        .background(
                            RoundedRectangle(cornerRadius: 22, style: .continuous)
                                .fill(Color.red.opacity(0.08))
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 22, style: .continuous)
                                .stroke(Color.red.opacity(0.16), lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                } header: {
                    sectionHeader(title: "Account", subtitle: "Manage your session")
                }
                .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 12, trailing: 0))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
            .listStyle(.plain)
            .listSectionSpacing(20)
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .scrollContentBackground(.hidden)
            .background(Color.black)
        }
    }

    private func sectionHeader(title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title.uppercased())
                .font(.system(size: 11, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(.white.opacity(0.44))

            Text(subtitle)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white.opacity(0.58))
        }
        .textCase(nil)
        .padding(.top, 4)
    }
}

// MARK: - Link Row

struct LinkRow: View {
    let icon: String
    let title: String
    let subtitle: String
    let url: String?

    var body: some View {
        Group {
            if let url, let link = URL(string: url) {
                Button {
                    UIApplication.shared.open(link)
                } label: {
                    row
                }
                .buttonStyle(.plain)
            } else {
                row
            }
        }
        .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0))
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }

    private var row: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color.white.opacity(0.07))
                    .frame(width: 42, height: 42)

                Image(systemName: icon)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.82))
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white.opacity(0.5))
            }

            Spacer()

            Image(systemName: url == nil ? "circle.dashed" : "arrow.up.right")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(url == nil ? .white.opacity(0.28) : .white.opacity(0.78))
                .frame(width: 28, height: 28)
                .background(
                    Circle()
                        .fill(Color.white.opacity(url == nil ? 0.03 : 0.08))
                )
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(Color.white.opacity(0.045))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(
                    LinearGradient(
                        colors: [
                            Color.white.opacity(0.12),
                            Color.white.opacity(0.03)
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 1
                )
        )
    }
}
