import SwiftUI

@main
struct VercelticsApp: App {
    @State private var authManager = AuthManager()

    var body: some Scene {
        WindowGroup {
            Group {
                if authManager.isAuthenticated {
                    ProjectsView()
                } else {
                    LoginView()
                }
            }
            .environment(authManager)
            .preferredColorScheme(.dark)
        }
    }
}
