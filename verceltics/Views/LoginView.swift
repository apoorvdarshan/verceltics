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
                        VStack(spacing: 12) {
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

                            Text("Create a token at vercel.com/account/tokens")
                                .font(.caption2)
                                .foregroundStyle(.gray)

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
                    }
                }

                Spacer()
                    .frame(height: 60)
            }
        }
    }
}
