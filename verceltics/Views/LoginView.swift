import SwiftUI

struct LoginView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var tokenInput = ""
    @State private var showTokenField = false
    @FocusState private var isTokenFocused: Bool

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    .black,
                    Color(red: 0.03, green: 0.04, blue: 0.08),
                    .black
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            Circle()
                .fill(Color.white.opacity(0.08))
                .frame(width: 300, height: 300)
                .blur(radius: 120)
                .offset(x: -140, y: -250)

            Circle()
                .fill(Color.blue.opacity(0.12))
                .frame(width: 260, height: 260)
                .blur(radius: 120)
                .offset(x: 160, y: 180)

            ScrollView(showsIndicators: false) {
                VStack(spacing: 32) {
                    VStack(spacing: 18) {
                        ZStack {
                            Circle()
                                .fill(Color.white.opacity(0.08))
                                .frame(width: 132, height: 132)
                                .blur(radius: 24)

                            Circle()
                                .fill(
                                    LinearGradient(
                                        colors: [
                                            Color.white.opacity(0.14),
                                            Color.white.opacity(0.03)
                                        ],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 110, height: 110)

                            Image(systemName: "triangle.fill")
                                .font(.system(size: 54, weight: .bold))
                                .foregroundStyle(.white)
                        }

                        VStack(spacing: 10) {
                            Text("Verceltics")
                                .font(.system(size: 38, weight: .bold))
                                .foregroundStyle(.white)

                            Text("Analytics for your Vercel projects")
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(.white.opacity(0.58))
                        }
                    }
                    .padding(.top, 36)

                    VStack(spacing: 18) {
                        if let error = authManager.error {
                            HStack(alignment: .center, spacing: 10) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundStyle(.red)

                                Text(error)
                                    .font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(.red.opacity(0.9))
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .background(Color.red.opacity(0.08))
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .stroke(Color.red.opacity(0.16), lineWidth: 1)
                            )
                        }

                        if showTokenField {
                            VStack(spacing: 18) {
                                VStack(alignment: .leading, spacing: 14) {
                                    Text("How to get your token")
                                        .font(.system(size: 13, weight: .semibold))
                                        .foregroundStyle(.white)

                                    StepRow(number: 1, text: "Go to vercel.com/account/tokens")
                                    StepRow(number: 2, text: "Tap \"Create Token\"")
                                    StepRow(number: 3, text: "Name it anything (e.g. Verceltics)")
                                    StepRow(number: 4, text: "Set scope to your account, expiration as needed")
                                    StepRow(number: 5, text: "Copy the token and paste it below")
                                }
                                .padding(18)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(Color.white.opacity(0.05))
                                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                                        .stroke(Color.white.opacity(0.06), lineWidth: 1)
                                )

                                Button {
                                    if let url = URL(string: "https://vercel.com/account/tokens") {
                                        UIApplication.shared.open(url)
                                    }
                                } label: {
                                    HStack(spacing: 8) {
                                        Text("Open Vercel Tokens Page")
                                            .font(.system(size: 14, weight: .semibold))
                                        Image(systemName: "arrow.up.right")
                                            .font(.system(size: 12, weight: .bold))
                                    }
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 48)
                                    .background(Color.white.opacity(0.07))
                                    .foregroundStyle(.white)
                                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                                            .stroke(Color.white.opacity(0.08), lineWidth: 1)
                                    )
                                }
                                .buttonStyle(ScaleOnPressButtonStyle())

                                VStack(alignment: .leading, spacing: 10) {
                                    Text("Vercel API Token")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(.white.opacity(0.56))

                                    HStack(spacing: 12) {
                                        Image(systemName: "key.horizontal.fill")
                                            .font(.system(size: 14, weight: .semibold))
                                            .foregroundStyle(.white.opacity(0.72))

                                        SecureField("Paste your Vercel token", text: $tokenInput)
                                            .textFieldStyle(.plain)
                                            .font(.system(size: 15, weight: .medium, design: .monospaced))
                                            .foregroundStyle(.white)
                                            .focused($isTokenFocused)
                                            .autocorrectionDisabled()
                                            .textInputAutocapitalization(.never)
                                            .onAppear { isTokenFocused = true }
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 15)
                                    .background(Color.white.opacity(0.06))
                                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                                            .stroke(
                                                LinearGradient(
                                                    colors: [
                                                        Color.white.opacity(0.18),
                                                        Color.white.opacity(0.05)
                                                    ],
                                                    startPoint: .topLeading,
                                                    endPoint: .bottomTrailing
                                                ),
                                                lineWidth: 1
                                            )
                                    )
                                }

                                Button {
                                    Task { await authManager.login(token: tokenInput.trimmingCharacters(in: .whitespacesAndNewlines)) }
                                } label: {
                                    HStack(spacing: 10) {
                                        if authManager.isLoading {
                                            ProgressView()
                                                .tint(.black)
                                        } else {
                                            Text("Connect")
                                                .font(.system(size: 16, weight: .semibold))
                                            Image(systemName: "arrow.right")
                                                .font(.system(size: 14, weight: .bold))
                                        }
                                    }
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 54)
                                    .background(tokenInput.isEmpty ? Color.white.opacity(0.24) : .white)
                                    .foregroundStyle(.black)
                                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                                    .shadow(color: .black.opacity(tokenInput.isEmpty ? 0 : 0.32), radius: 16, x: 0, y: 10)
                                }
                                .buttonStyle(ScaleOnPressButtonStyle())
                                .disabled(tokenInput.isEmpty || authManager.isLoading)
                            }
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                        } else {
                            Button {
                                withAnimation(.spring(response: 0.4, dampingFraction: 0.82)) {
                                    showTokenField = true
                                }
                            } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: "triangle.fill")
                                        .font(.system(size: 14, weight: .bold))
                                    Text("Sign in with Vercel")
                                        .font(.system(size: 16, weight: .semibold))
                                }
                                .frame(maxWidth: .infinity)
                                .frame(height: 54)
                                .background(.white)
                                .foregroundStyle(.black)
                                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                                .shadow(color: .black.opacity(0.32), radius: 16, x: 0, y: 10)
                            }
                            .buttonStyle(ScaleOnPressButtonStyle())
                        }
                    }
                    .padding(22)
                    .background(Color.white.opacity(0.045))
                    .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 28, style: .continuous)
                            .stroke(
                                LinearGradient(
                                    colors: [
                                        Color.white.opacity(0.16),
                                        Color.white.opacity(0.03)
                                    ],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 1
                            )
                    )
                    .shadow(color: .black.opacity(0.4), radius: 28, x: 0, y: 18)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 32)
                .frame(maxWidth: 540)
                .frame(maxWidth: .infinity)
            }
        }
    }
}

private struct ScaleOnPressButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.spring(response: 0.26, dampingFraction: 0.74), value: configuration.isPressed)
    }
}

// MARK: - Step Row

struct StepRow: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(number)")
                .font(.system(size: 10, weight: .bold).monospacedDigit())
                .foregroundStyle(.black)
                .frame(width: 20, height: 20)
                .background(.white)
                .clipShape(Circle())

            Text(text)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white.opacity(0.68))
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
