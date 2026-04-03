import SwiftUI

struct AboutView: View {
    @Environment(AuthManager.self) private var authManager

    var body: some View {
        NavigationStack {
            List {
                // App header
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "triangle.fill")
                            .font(.system(size: 48))
                            .foregroundStyle(.white)

                        Text("Verceltics")
                            .font(.title2.bold())
                            .foregroundStyle(.white)

                        Text("Analytics viewer for Vercel")
                            .font(.subheadline)
                            .foregroundStyle(.gray)

                        Text("v1.0.0")
                            .font(.caption)
                            .foregroundStyle(.gray)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .listRowBackground(Color.clear)
                }

                // Links
                Section("Links") {
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
                }

                // Legal
                Section("Legal") {
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
                }

                // About
                Section("About") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Verceltics is an open-source iOS app that lets you browse your Vercel projects and view web analytics — visitors, page views, bounce rate, referrers, countries, devices, and more.")
                            .font(.caption)
                            .foregroundStyle(.gray)

                        Text("Built with SwiftUI and Swift Charts. No third-party dependencies.")
                            .font(.caption)
                            .foregroundStyle(.gray)
                    }
                    .padding(.vertical, 4)
                    .listRowBackground(Color.white.opacity(0.04))
                }

                // Account
                Section {
                    Button(role: .destructive) {
                        authManager.logout()
                    } label: {
                        HStack {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                            Text("Sign Out")
                        }
                    }
                }
            }
            .navigationTitle("About")
            .scrollContentBackground(.hidden)
            .background(Color.black)
        }
    }
}

// MARK: - Link Row

struct LinkRow: View {
    let icon: String
    let title: String
    let subtitle: String
    let url: String?

    var body: some View {
        if let url, let link = URL(string: url) {
            Button {
                UIApplication.shared.open(link)
            } label: {
                row
            }
            .listRowBackground(Color.white.opacity(0.04))
        } else {
            row
                .listRowBackground(Color.white.opacity(0.04))
        }
    }

    private var row: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 14))
                .foregroundStyle(.gray)
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.gray)
            }

            Spacer()

            if url != nil {
                Image(systemName: "arrow.up.right")
                    .font(.caption2)
                    .foregroundStyle(.gray)
            }
        }
    }
}
