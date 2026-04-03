import SwiftUI

struct LoginView: View {
    @Environment(AuthManager.self) private var authManager
    @State private var tokenInput = ""
    @State private var showTokenField = false
    @FocusState private var isTokenFocused: Bool

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 40) {
                Spacer()

                VStack(spacing: 16) {
                    Image(systemName: "triangle.fill")
                        .font(.system(size: 64))
                        .foregroundStyle(.white)
                        .shadow(color: .white.opacity(0.15), radius: 30)

                    Text("Verceltics")
                        .font(.system(size: 34, weight: .bold, design: .default))
                        .foregroundStyle(.white)

                    Text("Analytics for your Vercel projects")
                        .font(.subheadline)
                        .foregroundStyle(.gray)
                }

                Spacer()

                VStack(spacing: 16) {
                    if let error = authManager.error {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.red)
                            Text(error)
                                .font(.caption)
                                .foregroundStyle(.red)
                        }
                        .padding(.horizontal)
                    }

                    if showTokenField {
                        VStack(spacing: 16) {
                            // Steps guide
                            VStack(alignment: .leading, spacing: 10) {
                                Text("How to get your token")
                                    .font(.caption.bold())
                                    .foregroundStyle(.white)

                                StepRow(number: 1, text: "Go to vercel.com/account/tokens")
                                StepRow(number: 2, text: "Tap \"Create Token\"")
                                StepRow(number: 3, text: "Name it anything (e.g. Verceltics)")
                                StepRow(number: 4, text: "Set scope to your account, expiration as needed")
                                StepRow(number: 5, text: "Copy the token and paste it below")
                            }
                            .padding(14)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.white.opacity(0.06))
                            .clipShape(RoundedRectangle(cornerRadius: 12))

                            Button {
                                if let url = URL(string: "https://vercel.com/account/tokens") {
                                    UIApplication.shared.open(url)
                                }
                            } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: "arrow.up.right")
                                        .font(.caption2.bold())
                                    Text("Open Vercel Tokens Page")
                                        .font(.caption.bold())
                                }
                                .frame(maxWidth: .infinity)
                                .frame(height: 40)
                                .background(Color.white.opacity(0.1))
                                .foregroundStyle(.white)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                            }

                            SecureField("Paste your Vercel token", text: $tokenInput)
                                .textFieldStyle(.plain)
                                .font(.system(.body, design: .monospaced))
                                .padding(14)
                                .background(Color.white.opacity(0.06))
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                                .foregroundStyle(.white)
                                .focused($isTokenFocused)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .onAppear { isTokenFocused = true }

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
                                            .fontWeight(.semibold)
                                    }
                                }
                                .frame(maxWidth: .infinity)
                                .frame(height: 50)
                                .background(tokenInput.isEmpty ? Color.white.opacity(0.3) : .white)
                                .foregroundStyle(.black)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            }
                            .disabled(tokenInput.isEmpty || authManager.isLoading)
                        }
                        .padding(.horizontal, 24)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    } else {
                        Button {
                            withAnimation(.spring(duration: 0.4)) {
                                showTokenField = true
                            }
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "triangle.fill")
                                    .font(.system(size: 14))
                                Text("Sign in with Vercel")
                                    .fontWeight(.semibold)
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 50)
                            .background(.white)
                            .foregroundStyle(.black)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                        .padding(.horizontal, 24)
                        .buttonStyle(PressScaleButtonStyle())
                    }
                }
                .animation(.spring(response: 0.45, dampingFraction: 0.82), value: showTokenField)

                Spacer()
                    .frame(height: 40)
            }
        }
    }
}

// MARK: - Step Row

struct StepRow: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(number)")
                .font(.caption2.bold().monospacedDigit())
                .foregroundStyle(.white)
                .frame(width: 18, height: 18)
                .background(Color.blue)
                .clipShape(Circle())

            Text(text)
                .font(.caption)
                .foregroundStyle(.gray)
        }
    }
}

struct PressScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.spring(response: 0.22, dampingFraction: 0.72), value: configuration.isPressed)
    }
}
