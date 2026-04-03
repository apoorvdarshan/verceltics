import SwiftUI

struct LoginView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var tokenInput = ""
    @State private var showTokenField = false
    @FocusState private var isTokenFocused: Bool

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    Spacer().frame(height: 80)

                    // Logo + branding
                    VStack(spacing: 20) {
                        if let uiImage = UIImage(named: "AppIcon") {
                            Image(uiImage: uiImage)
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .frame(width: 80, height: 80)
                                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                                .shadow(color: .blue.opacity(0.2), radius: 20, y: 4)
                        }

                        VStack(spacing: 6) {
                            Text("Verceltics")
                                .font(.system(size: 28, weight: .bold))
                                .foregroundStyle(.white)

                            Text("Analytics for your Vercel projects")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundStyle(.white.opacity(0.4))
                        }
                    }

                    if let error = authManager.error {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .font(.system(size: 12))
                            Text(error)
                                .font(.system(size: 12, weight: .medium))
                        }
                        .foregroundStyle(.red)
                        .padding(.top, 24)
                    }

                    if showTokenField {
                        VStack(spacing: 14) {
                            // Steps
                            VStack(alignment: .leading, spacing: 12) {
                                Text("How to get your token")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(.white)

                                StepRow(number: 1, text: "Go to vercel.com/account/tokens")
                                StepRow(number: 2, text: "Tap \"Create Token\"")
                                StepRow(number: 3, text: "Name it anything (e.g. Verceltics)")
                                StepRow(number: 4, text: "Set scope to your account")
                                StepRow(number: 5, text: "Copy and paste below")
                            }
                            .padding(16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(.ultraThinMaterial.opacity(0.5))
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .stroke(Color.white.opacity(0.06), lineWidth: 0.5)
                            )

                            // Open Vercel button
                            Button {
                                if let url = URL(string: "https://vercel.com/account/tokens") {
                                    UIApplication.shared.open(url)
                                }
                            } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: "arrow.up.right")
                                        .font(.system(size: 11, weight: .bold))
                                    Text("Open Vercel Tokens Page")
                                        .font(.system(size: 13, weight: .semibold))
                                }
                                .frame(maxWidth: .infinity)
                                .frame(height: 44)
                                .background(Color.white.opacity(0.06))
                                .foregroundStyle(.white.opacity(0.7))
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .stroke(Color.white.opacity(0.06), lineWidth: 0.5)
                                )
                            }

                            // Token input
                            SecureField("Paste your Vercel token", text: $tokenInput)
                                .textFieldStyle(.plain)
                                .font(.system(size: 15, design: .monospaced))
                                .padding(14)
                                .background(Color.white.opacity(0.06))
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .stroke(Color.white.opacity(isTokenFocused ? 0.15 : 0.06), lineWidth: 0.5)
                                )
                                .foregroundStyle(.white)
                                .focused($isTokenFocused)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)

                            // Connect button
                            Button {
                                Task { await authManager.login(token: tokenInput.trimmingCharacters(in: .whitespacesAndNewlines)) }
                            } label: {
                                HStack(spacing: 10) {
                                    if authManager.isLoading {
                                        ProgressView()
                                            .tint(.black)
                                    } else {
                                        Image(systemName: "arrow.right")
                                            .font(.system(size: 14, weight: .bold))
                                        Text("Connect")
                                            .font(.system(size: 16, weight: .semibold))
                                    }
                                }
                                .frame(maxWidth: .infinity)
                                .frame(height: 52)
                                .background(tokenInput.isEmpty ? Color.white.opacity(0.2) : .white)
                                .foregroundStyle(.black)
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                            .disabled(tokenInput.isEmpty || authManager.isLoading)
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 36)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    } else {
                        // Sign in button
                        Button {
                            withAnimation(.spring(duration: 0.4)) {
                                showTokenField = true
                            }
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "triangle.fill")
                                    .font(.system(size: 13))
                                Text("Sign in with Vercel")
                                    .font(.system(size: 16, weight: .semibold))
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(.white)
                            .foregroundStyle(.black)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 50)
                        .buttonStyle(PressScaleButtonStyle())
                    }

                    Spacer().frame(height: 40)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .animation(.spring(response: 0.45, dampingFraction: 0.82), value: showTokenField)
        }
    }
}

// MARK: - Step Row

struct StepRow: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Text("\(number)")
                .font(.system(size: 11, weight: .bold).monospacedDigit())
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Color.white.opacity(0.1))
                .clipShape(Circle())

            Text(text)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white.opacity(0.45))
        }
    }
}

struct PressScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.22, dampingFraction: 0.72), value: configuration.isPressed)
    }
}
