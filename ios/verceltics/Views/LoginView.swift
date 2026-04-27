import SwiftUI

struct LoginView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var tokenInput = ""
    @State private var showTokenField = false
    @FocusState private var isTokenFocused: Bool

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            Group {
                if showTokenField {
                    tokenFieldView
                } else {
                    welcomeView
                }
            }
            .frame(maxWidth: hSize == .regular ? 480 : .infinity)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Welcome (centered)

    private var welcomeView: some View {
        VStack(spacing: 0) {
            Spacer()

            brandingHeader

            Spacer().frame(height: 40)

            DemoChart()
                .frame(height: 100)
                .padding(.horizontal, 40)

            Spacer()

            Button {
                withAnimation(.spring(duration: 0.4)) {
                    showTokenField = true
                }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "triangle.fill")
                        .font(.system(size: 13, weight: .heavy))
                    Text("Sign in with Vercel")
                        .font(.system(size: 16, weight: .heavy))
                    Image(systemName: "arrow.right")
                        .font(.system(size: 13, weight: .heavy))
                }
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .background(
                    LinearGradient(
                        colors: [.white, Color.white.opacity(0.92)],
                        startPoint: .top, endPoint: .bottom
                    )
                )
                .foregroundStyle(.black)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.1), lineWidth: 0.5)
                )
                .shadow(color: Color.white.opacity(0.08), radius: 18, x: 0, y: 4)
            }
            .padding(.horizontal, 20)
            .buttonStyle(PressScaleButtonStyle())

            Spacer().frame(height: 50)
        }
    }

    // MARK: - Token Field (scrollable)

    private var tokenFieldView: some View {
        ScrollView {
            VStack(spacing: 0) {
                Spacer().frame(height: 60)

                brandingHeader

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

                VStack(spacing: 14) {
                    // Steps
                    VStack(alignment: .leading, spacing: 14) {
                        Text("How to get your token")
                            .font(.system(size: 13, weight: .heavy))
                            .foregroundStyle(.white)

                        StepRow(number: 1, text: "Go to vercel.com/account/tokens")
                        StepRow(number: 2, text: "Tap \"Create Token\"")
                        StepRow(number: 3, text: "Name it anything (e.g. Verceltics)")
                        StepRow(number: 4, text: "Set scope to your account")
                        StepRow(number: 5, text: "Copy and paste below")
                    }
                    .padding(18)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        ZStack {
                            LinearGradient(
                                colors: [Color.white.opacity(0.07), Color.white.opacity(0.02)],
                                startPoint: .topLeading, endPoint: .bottomTrailing
                            )
                            LinearGradient(
                                colors: [Color.white.opacity(0.04), .clear],
                                startPoint: .top, endPoint: .center
                            )
                        }
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .strokeBorder(
                                LinearGradient(
                                    colors: [Color.white.opacity(0.12), Color.white.opacity(0.04)],
                                    startPoint: .top, endPoint: .bottom
                                ),
                                lineWidth: 0.5
                            )
                    )

                    // Open Vercel
                    Button {
                        if let url = URL(string: "https://vercel.com/account/tokens") {
                            UIApplication.shared.open(url)
                        }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 11, weight: .heavy))
                            Text("Open Vercel Tokens Page")
                                .font(.system(size: 13, weight: .bold))
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .background(
                            LinearGradient(
                                colors: [Color.white.opacity(0.08), Color.white.opacity(0.04)],
                                startPoint: .top, endPoint: .bottom
                            )
                        )
                        .foregroundStyle(.white.opacity(0.75))
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.08), lineWidth: 0.5)
                        )
                    }
                    .buttonStyle(PressScaleButtonStyle())

                    // Token input
                    SecureField("Paste your Vercel token", text: $tokenInput)
                        .textFieldStyle(.plain)
                        .font(.system(size: 15, design: .monospaced))
                        .padding(15)
                        .background(
                            LinearGradient(
                                colors: [Color.white.opacity(0.07), Color.white.opacity(0.03)],
                                startPoint: .top, endPoint: .bottom
                            )
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .strokeBorder(isTokenFocused ? Color.blue.opacity(0.4) : Color.white.opacity(0.08), lineWidth: isTokenFocused ? 1.0 : 0.5)
                        )
                        .foregroundStyle(.white)
                        .focused($isTokenFocused)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .animation(.easeOut(duration: 0.18), value: isTokenFocused)

                    // Connect
                    Button {
                        Task { await authManager.login(token: tokenInput.trimmingCharacters(in: .whitespacesAndNewlines)) }
                    } label: {
                        HStack(spacing: 10) {
                            if authManager.isLoading {
                                ProgressView().tint(.black)
                            } else {
                                Text("Connect")
                                    .font(.system(size: 16, weight: .heavy))
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 14, weight: .heavy))
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                        .background(
                            LinearGradient(
                                colors: tokenInput.isEmpty
                                    ? [Color.white.opacity(0.2), Color.white.opacity(0.12)]
                                    : [.white, Color.white.opacity(0.92)],
                                startPoint: .top, endPoint: .bottom
                            )
                        )
                        .foregroundStyle(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.1), lineWidth: 0.5)
                        )
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .disabled(tokenInput.isEmpty || authManager.isLoading)
                }
                .padding(.horizontal, 20)
                .padding(.top, 36)

                Spacer().frame(height: 40)
            }
        }
        .scrollDismissesKeyboard(.interactively)
    }

    // MARK: - Shared branding

    private var brandingHeader: some View {
        VStack(spacing: 22) {
            Image("AppLogo")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 120, height: 120)
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.08), lineWidth: 0.5)
                )
                .shadow(color: Color.blue.opacity(0.18), radius: 30, x: 0, y: 8)

            VStack(spacing: 7) {
                Text("Verceltics")
                    .font(.system(size: 30, weight: .heavy))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [.white, Color.white.opacity(0.75)],
                            startPoint: .top, endPoint: .bottom
                        )
                    )

                Text("Analytics for your Vercel projects")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.45))
            }
        }
    }
}

// MARK: - Demo Chart (animated line that draws itself)

struct DemoChart: View {
    @State private var drawProgress: CGFloat = 0
    @State private var dotIndex = 0

    private let points: [CGFloat] = [0.3, 0.5, 0.25, 0.7, 0.4, 0.85, 0.6, 0.9, 0.55, 0.95]

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height

            ZStack {
                // Grid lines
                ForEach(0..<4, id: \.self) { i in
                    let y = h * CGFloat(i) / 3
                    Path { path in
                        path.move(to: CGPoint(x: 0, y: y))
                        path.addLine(to: CGPoint(x: w, y: y))
                    }
                    .stroke(Color.white.opacity(0.04), lineWidth: 0.5)
                }

                // Gradient fill under line
                Path { path in
                    for (i, point) in points.enumerated() {
                        let x = w * CGFloat(i) / CGFloat(points.count - 1)
                        let y = h * (1 - point)
                        if i == 0 { path.move(to: CGPoint(x: x, y: y)) }
                        else { path.addLine(to: CGPoint(x: x, y: y)) }
                    }
                    path.addLine(to: CGPoint(x: w, y: h))
                    path.addLine(to: CGPoint(x: 0, y: h))
                    path.closeSubpath()
                }
                .fill(
                    LinearGradient(
                        colors: [.blue.opacity(0.15), .clear],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .mask(
                    Rectangle()
                        .frame(width: w * drawProgress)
                        .frame(maxWidth: .infinity, alignment: .leading)
                )

                // Line
                Path { path in
                    for (i, point) in points.enumerated() {
                        let x = w * CGFloat(i) / CGFloat(points.count - 1)
                        let y = h * (1 - point)
                        if i == 0 { path.move(to: CGPoint(x: x, y: y)) }
                        else { path.addLine(to: CGPoint(x: x, y: y)) }
                    }
                }
                .trim(from: 0, to: drawProgress)
                .stroke(Color.blue, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))

                // Animated dots
                ForEach(0..<points.count, id: \.self) { i in
                    let x = w * CGFloat(i) / CGFloat(points.count - 1)
                    let y = h * (1 - points[i])
                    let visible = CGFloat(i) / CGFloat(points.count - 1) <= drawProgress

                    Circle()
                        .fill(.blue)
                        .frame(width: 6, height: 6)
                        .scaleEffect(i == dotIndex && visible ? 1.5 : 1)
                        .opacity(visible ? 1 : 0)
                        .position(x: x, y: y)
                }
            }
        }
        .onAppear {
            // Animate line drawing
            withAnimation(.easeInOut(duration: 2.5)) {
                drawProgress = 1.0
            }
            // Pulse dots sequentially
            for i in 0..<points.count {
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 0.28) {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.5)) {
                        dotIndex = i
                    }
                }
            }
            // Loop the animation
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                drawProgress = 0
                dotIndex = 0
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    withAnimation(.easeInOut(duration: 2.5)) {
                        drawProgress = 1.0
                    }
                    for i in 0..<points.count {
                        DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * 0.28) {
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.5)) {
                                dotIndex = i
                            }
                        }
                    }
                }
            }
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
                .font(.system(size: 11, weight: .heavy).monospacedDigit())
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(
                    LinearGradient(
                        colors: [Color.white.opacity(0.16), Color.white.opacity(0.06)],
                        startPoint: .top, endPoint: .bottom
                    )
                )
                .clipShape(Circle())
                .overlay(Circle().strokeBorder(Color.white.opacity(0.06), lineWidth: 0.5))

            Text(text)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white.opacity(0.55))
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
