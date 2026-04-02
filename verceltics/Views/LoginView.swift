import SwiftUI

struct LoginView: View {
    @Environment(AuthManager.self) private var authManager

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

                    Button {
                        Task { await authManager.login() }
                    } label: {
                        HStack(spacing: 10) {
                            if authManager.isLoading {
                                ProgressView()
                                    .tint(.black)
                            } else {
                                Image(systemName: "triangle.fill")
                                    .font(.system(size: 14))
                                Text("Sign in with Vercel")
                                    .fontWeight(.semibold)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(.white)
                        .foregroundStyle(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .disabled(authManager.isLoading)
                    .padding(.horizontal, 24)
                }

                Spacer()
                    .frame(height: 60)
            }
        }
    }
}
