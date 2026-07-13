import SwiftUI

struct LoginView: View {
    @Environment(AuthManager.self) private var authManager
    @Environment(\.horizontalSizeClass) private var hSize
    @Environment(\.dismiss) private var dismiss
    @State private var tokenInput = ""
    @State private var selectedProvider: AccountProvider?
    @State private var cloudflareEmail = ""
    @State private var cloudflareGlobalAPIKey = ""
    @State private var cloudflareAPIToken = ""
    @State private var cloudflareAuthenticationMode: CloudflareAuthenticationMode = .globalAPIKey
    @FocusState private var isTokenFocused: Bool
    @FocusState private var focusedCloudflareField: CloudflareField?

    private enum CloudflareField { case email, key }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            Group {
                switch selectedProvider {
                case .vercel:
                    tokenFieldView
                case .cloudflare:
                    cloudflareCredentialsView
                case nil:
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

            VStack(spacing: 12) {
                providerButton(.vercel)
                providerButton(.cloudflare)
            }
            .padding(.horizontal, 20)

            Spacer().frame(height: 50)
        }
    }

    private func providerButton(_ provider: AccountProvider) -> some View {
        let isCloudflare = provider == .cloudflare
        let accent = Color(red: 0.96, green: 0.45, blue: 0.10)
        return Button {
            authManager.error = nil
            withAnimation(.spring(duration: 0.4)) { selectedProvider = provider }
        } label: {
            HStack(spacing: 13) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(isCloudflare ? accent.opacity(0.16) : Color.black.opacity(0.08))
                    if isCloudflare {
                        Image("CloudflareMark")
                            .resizable()
                            .scaledToFit()
                            .padding(7)
                    } else {
                        Image(systemName: "triangle.fill")
                            .font(.system(size: 13, weight: .heavy))
                    }
                }
                .frame(width: 34, height: 34)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Connect \(provider.displayName)")
                        .font(.system(size: 15, weight: .heavy))
                    Text(isCloudflare ? "Zones, Pages, Workers, DNS and analytics" : "Projects, deployments and Web Analytics")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(isCloudflare ? Color.white.opacity(0.45) : Color.black.opacity(0.5))
                        .lineLimit(1)
                }

                Spacer()
                Image(systemName: "arrow.right")
                    .font(.system(size: 12, weight: .heavy))
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity)
            .frame(height: 62)
            .background(
                LinearGradient(
                    colors: isCloudflare
                        ? [Color.white.opacity(0.08), Color.white.opacity(0.035)]
                        : [.white, Color.white.opacity(0.92)],
                    startPoint: .top, endPoint: .bottom
                )
            )
            .foregroundStyle(isCloudflare ? .white : .black)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(isCloudflare ? accent.opacity(0.28) : Color.white.opacity(0.1), lineWidth: 0.7)
            )
        }
        .buttonStyle(PressScaleButtonStyle())
    }

    // MARK: - Token Field (scrollable)

    private var tokenFieldView: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(spacing: 0) {
                credentialHeader(provider: .vercel)

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
                        Task {
                            await authManager.login(token: tokenInput.trimmingCharacters(in: .whitespacesAndNewlines))
                            if authManager.error == nil {
                                dismiss()
                            }
                        }
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
                    .id("connect-button")
                }
                .padding(.horizontal, 20)
                .padding(.top, 36)

                Spacer().frame(height: 40)
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .onChange(of: isTokenFocused) { _, focused in
            if focused {
                // Wait for the keyboard frame to settle, then scroll the
                // Connect button into view above the keyboard.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                    withAnimation(.easeOut(duration: 0.25)) {
                        proxy.scrollTo("connect-button", anchor: .bottom)
                    }
                }
            }
        }
        }
    }

    private var cloudflareCredentialsView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    credentialHeader(provider: .cloudflare)

                    if let error = authManager.error {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                            Text(error)
                                .font(.system(size: 12, weight: .medium))
                        }
                        .foregroundStyle(.red)
                        .padding(.top, 20)
                    }

                    VStack(spacing: 14) {
                        Picker("Authentication", selection: $cloudflareAuthenticationMode) {
                            ForEach(CloudflareAuthenticationMode.allCases) { mode in
                                Text(mode.displayName).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)

                        VStack(alignment: .leading, spacing: 14) {
                            Text(
                                cloudflareAuthenticationMode == .globalAPIKey
                                    ? "Connect with Global API Key"
                                    : "Connect with scoped API token"
                            )
                                .font(.system(size: 13, weight: .heavy))
                                .foregroundStyle(.white)

                            StepRow(number: 1, text: "Open Cloudflare My Profile → API Tokens")
                            if cloudflareAuthenticationMode == .globalAPIKey {
                                StepRow(number: 2, text: "In API Keys, tap View beside Global API Key")
                                StepRow(number: 3, text: "Complete identity verification")
                                StepRow(number: 4, text: "Paste your login email and key below")
                            } else {
                                StepRow(number: 2, text: "Create a custom token with the product permissions you need")
                                StepRow(number: 3, text: "Include Account Read so the app can discover your accounts")
                                StepRow(number: 4, text: "Paste the token below")
                            }

                            HStack(alignment: .top, spacing: 9) {
                                Image(systemName: "lock.shield.fill")
                                    .foregroundStyle(Color(red: 0.96, green: 0.45, blue: 0.10))
                                Text(
                                    cloudflareAuthenticationMode == .globalAPIKey
                                        ? "Stored only in this iPhone’s Keychain. The Global API Key has the same Cloudflare access as your user, including write access."
                                        : "Stored only in this iPhone’s Keychain. The app can only use permissions and resources included in this token."
                                )
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundStyle(.white.opacity(0.48))
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .padding(18)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(
                            LinearGradient(
                                colors: [Color.white.opacity(0.07), Color.white.opacity(0.02)],
                                startPoint: .topLeading, endPoint: .bottomTrailing
                            )
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .strokeBorder(Color(red: 0.96, green: 0.45, blue: 0.10).opacity(0.24), lineWidth: 0.7)
                        )

                        Button {
                            UIApplication.shared.open(URL(string: "https://dash.cloudflare.com/profile/api-tokens")!)
                        } label: {
                            Label("Open Cloudflare API Tokens", systemImage: "arrow.up.right")
                                .font(.system(size: 13, weight: .bold))
                                .frame(maxWidth: .infinity)
                                .frame(height: 46)
                                .foregroundStyle(.white.opacity(0.8))
                                .background(Color.white.opacity(0.06))
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        .buttonStyle(PressScaleButtonStyle())

                        if cloudflareAuthenticationMode == .globalAPIKey {
                            cloudflareTextField(
                                "Cloudflare login email",
                                text: $cloudflareEmail,
                                field: .email,
                                secure: false
                            )
                            .keyboardType(.emailAddress)

                            cloudflareTextField(
                                "Paste Global API Key",
                                text: $cloudflareGlobalAPIKey,
                                field: .key,
                                secure: true
                            )
                        } else {
                            cloudflareTextField(
                                "Paste scoped API token",
                                text: $cloudflareAPIToken,
                                field: .key,
                                secure: true
                            )
                        }

                        Button {
                            Task {
                                if cloudflareAuthenticationMode == .globalAPIKey {
                                    await authManager.loginCloudflare(
                                        email: cloudflareEmail.trimmingCharacters(in: .whitespacesAndNewlines),
                                        globalAPIKey: cloudflareGlobalAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
                                    )
                                } else {
                                    await authManager.loginCloudflare(
                                        apiToken: cloudflareAPIToken.trimmingCharacters(in: .whitespacesAndNewlines)
                                    )
                                }
                                if authManager.error == nil { dismiss() }
                            }
                        } label: {
                            HStack(spacing: 10) {
                                if authManager.isLoading {
                                    ProgressView().tint(.white)
                                } else {
                                    Text("Connect Cloudflare")
                                        .font(.system(size: 16, weight: .heavy))
                                    Image(systemName: "arrow.right")
                                        .font(.system(size: 14, weight: .heavy))
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .background(
                                LinearGradient(
                                    colors: canConnectCloudflare
                                        ? [Color(red: 1.0, green: 0.48, blue: 0.10), Color(red: 0.91, green: 0.31, blue: 0.06)]
                                        : [Color.white.opacity(0.16), Color.white.opacity(0.09)],
                                    startPoint: .topLeading, endPoint: .bottomTrailing
                                )
                            )
                            .foregroundStyle(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        }
                        .buttonStyle(PressScaleButtonStyle())
                        .disabled(!canConnectCloudflare || authManager.isLoading)
                        .id("cloudflare-connect")
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 28)

                    Spacer().frame(height: 40)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: focusedCloudflareField) { _, field in
                if field != nil {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                        withAnimation(.easeOut(duration: 0.25)) {
                            proxy.scrollTo("cloudflare-connect", anchor: .bottom)
                        }
                    }
                }
            }
        }
    }

    private var canConnectCloudflare: Bool {
        switch cloudflareAuthenticationMode {
        case .globalAPIKey:
            cloudflareEmail.contains("@") && !cloudflareGlobalAPIKey.isEmpty
        case .apiToken:
            !cloudflareAPIToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    @ViewBuilder
    private func cloudflareTextField(
        _ placeholder: String,
        text: Binding<String>,
        field: CloudflareField,
        secure: Bool
    ) -> some View {
        let content = Group {
            if secure {
                SecureField(placeholder, text: text)
            } else {
                TextField(placeholder, text: text)
            }
        }
        content
            .textFieldStyle(.plain)
            .font(.system(size: 14, design: .monospaced))
            .padding(15)
            .background(Color.white.opacity(0.055))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(
                        focusedCloudflareField == field
                            ? Color(red: 0.96, green: 0.45, blue: 0.10).opacity(0.65)
                            : Color.white.opacity(0.08),
                        lineWidth: focusedCloudflareField == field ? 1 : 0.5
                    )
            )
            .foregroundStyle(.white)
            .focused($focusedCloudflareField, equals: field)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
    }

    private func credentialHeader(provider: AccountProvider) -> some View {
        VStack(spacing: 22) {
            HStack {
                Button {
                    authManager.error = nil
                    withAnimation(.spring(duration: 0.35)) { selectedProvider = nil }
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 15, weight: .heavy))
                        .frame(width: 42, height: 42)
                        .background(Color.white.opacity(0.07))
                        .clipShape(Circle())
                }
                .foregroundStyle(.white)
                Spacer()
            }

            ZStack {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(
                        provider == .cloudflare
                            ? Color(red: 0.96, green: 0.45, blue: 0.10).opacity(0.14)
                            : Color.white.opacity(0.08)
                )
                if provider == .cloudflare {
                    Image("CloudflareMark")
                        .resizable()
                        .scaledToFit()
                        .padding(20)
                } else {
                    Image(systemName: "triangle.fill")
                        .font(.system(size: 33, weight: .black))
                }
            }
            .frame(width: 92, height: 92)

            VStack(spacing: 6) {
                Text("Connect \(provider.displayName)")
                    .font(.system(size: 26, weight: .heavy))
                Text(provider == .cloudflare ? "Manage your Cloudflare edge" : "Analytics for your Vercel projects")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.42))
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 18)
    }

    // MARK: - Shared branding

    private var brandingHeader: some View {
        VStack(spacing: 22) {
            Image("AppLogo")
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 120, height: 120)
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))

            VStack(spacing: 7) {
                Text("Verceltics")
                    .font(.system(size: 30, weight: .heavy))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [.white, Color.white.opacity(0.75)],
                            startPoint: .top, endPoint: .bottom
                        )
                    )

                Text("Vercel analytics and Cloudflare control")
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
                        stops: [
                            .init(color: Color.blue.opacity(0.28), location: 0.0),
                            .init(color: Color.blue.opacity(0.10), location: 0.5),
                            .init(color: .clear, location: 1.0),
                        ],
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
                .stroke(
                    LinearGradient(
                        colors: [Color.blue, Color(red: 0.45, green: 0.65, blue: 1.0)],
                        startPoint: .leading, endPoint: .trailing
                    ),
                    style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round)
                )

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
