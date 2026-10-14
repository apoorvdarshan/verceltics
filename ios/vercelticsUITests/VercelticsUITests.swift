import XCTest

/// Main user journeys through the real app in UI-test mode (`-uiTesting`).
///
/// UI-test mode is compiled only into Debug builds and works only in the
/// Simulator: accounts stay in memory, RevenueCat and App Store update checks
/// are never contacted, and `-uiTestingReset` clears the app's UserDefaults, so
/// every test starts from the same state without network access or accounts.
final class VercelticsUITests: XCTestCase {
    private enum Argument {
        static let uiTesting = "-uiTesting"
        static let reset = "-uiTestingReset"
        static let developerWelcome = "-uiTestingDeveloperWelcome"
        static let sampleSite = "-uiTestingSampleSite"
    }

    private let launchTimeout: TimeInterval = 20
    private let stepTimeout: TimeInterval = 10

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - First launch and onboarding

    @MainActor
    func testFirstLaunchWalksFromDeveloperWelcomeToProviderCatalog() {
        let app = launch(Argument.developerWelcome)

        XCTAssertTrue(app.staticTexts["Meet the developer"].waitForExistence(timeout: launchTimeout))
        for link in ["x", "instagram", "productHunt", "github", "discord"] {
            XCTAssertTrue(app.buttons["developerWelcome.link.\(link)"].exists, "missing \(link) link")
        }
        app.buttons["developerWelcome.close"].tap()

        XCTAssertTrue(app.staticTexts["Check your whole stack. Close the laptop."].waitForExistence(timeout: stepTimeout))
        XCTAssertFalse(app.staticTexts["Meet the developer"].exists)
        app.buttons["firstConnection.continue"].tap()

        XCTAssertTrue(app.staticTexts["CONNECT A HOSTING PLATFORM"].waitForExistence(timeout: stepTimeout))
        XCTAssertTrue(app.buttons["connect.hosting.vercel"].exists)
        XCTAssertFalse(app.tabBars.firstMatch.exists, "the workspace tabs need a connection first")
    }

    @MainActor
    func testWelcomeScreensStayDismissedAfterRelaunch() {
        let app = launch(Argument.developerWelcome)
        XCTAssertTrue(app.buttons["developerWelcome.close"].waitForExistence(timeout: launchTimeout))
        app.buttons["developerWelcome.close"].tap()
        XCTAssertTrue(app.buttons["firstConnection.continue"].waitForExistence(timeout: stepTimeout))
        app.buttons["firstConnection.continue"].tap()
        XCTAssertTrue(app.buttons["connect.hosting.vercel"].waitForExistence(timeout: stepTimeout))

        relaunchKeepingData(app)

        XCTAssertTrue(app.buttons["connect.hosting.vercel"].waitForExistence(timeout: launchTimeout))
        XCTAssertFalse(app.staticTexts["Meet the developer"].exists)
        XCTAssertFalse(app.buttons["firstConnection.continue"].exists)
    }

    @MainActor
    func testProviderCatalogOffersEveryConnectionCategory() {
        let app = launch()
        openProviderCatalog(app)

        let categories = app.segmentedControls.firstMatch
        XCTAssertTrue(categories.buttons["Hosting"].isSelected)
        XCTAssertTrue(app.buttons["connect.hosting.cloudflare"].exists)

        categories.buttons["Registrars"].tap()
        XCTAssertTrue(app.staticTexts["CONNECT A REGISTRAR"].waitForExistence(timeout: stepTimeout))
        XCTAssertTrue(app.buttons["connect.registrar.nameDotCom"].exists)
        XCTAssertTrue(app.buttons["connect.registrar.namecheap"].exists)
        XCTAssertFalse(app.buttons["connect.hosting.vercel"].exists)

        categories.buttons["Sites"].tap()
        XCTAssertTrue(app.staticTexts["CONNECT A SITE SERVICE"].waitForExistence(timeout: stepTimeout))
        XCTAssertTrue(app.buttons["connect.site.googleSearchConsole"].exists)
        XCTAssertTrue(app.buttons["connect.site.pageSpeed"].exists)

        categories.buttons["Hosting"].tap()
        XCTAssertTrue(app.buttons["connect.hosting.vercel"].waitForExistence(timeout: stepTimeout))
    }

    @MainActor
    func testVercelConnectionNeedsATokenBeforeConnecting() {
        let app = launch()
        openProviderCatalog(app)
        app.buttons["connect.hosting.vercel"].tap()

        let token = app.secureTextFields["vercel.token"]
        XCTAssertTrue(token.waitForExistence(timeout: stepTimeout))
        XCTAssertTrue(app.staticTexts["How to get your token"].exists)
        let connect = app.buttons["vercel.connect"]
        XCTAssertTrue(connect.exists)
        XCTAssertFalse(connect.isEnabled, "Connect must stay disabled without a token")

        token.tap()
        token.typeText("fixture-token")
        // The token is never submitted: connecting would call the Vercel API.
        waitUntil(connect, is: "isEnabled == true")

        app.buttons["connect.back"].tap()
        XCTAssertTrue(app.buttons["connect.hosting.vercel"].waitForExistence(timeout: stepTimeout))
        XCTAssertFalse(token.exists)
    }

    // MARK: - Workspaces

    @MainActor
    func testWorkspaceTabsShowEachWorkspace() {
        let app = launch(Argument.sampleSite)

        // Hosting is the default workspace; nothing is connected there yet.
        XCTAssertTrue(app.staticTexts["No hosting account"].waitForExistence(timeout: launchTimeout))
        XCTAssertTrue(app.buttons["Connect hosting"].exists)

        tab("Registrars", in: app)
        XCTAssertTrue(app.staticTexts["No registrar account"].waitForExistence(timeout: stepTimeout))
        XCTAssertTrue(app.buttons["Connect registrar"].exists)

        tab("Sites", in: app)
        XCTAssertTrue(app.buttons["sites.resource.verceltics.com"].waitForExistence(timeout: stepTimeout))

        tab("About", in: app)
        XCTAssertTrue(app.segmentedControls["about.appearancePicker"].waitForExistence(timeout: stepTimeout))
        let version = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Version ")).firstMatch
        XCTAssertTrue(version.exists, "About shows the installed version")
    }

    @MainActor
    func testSearchTabSearchesTheLastWorkspace() {
        let app = launch(Argument.sampleSite)
        XCTAssertTrue(app.staticTexts["No hosting account"].waitForExistence(timeout: launchTimeout))
        tab("Sites", in: app)
        let resource = app.buttons["sites.resource.verceltics.com"]
        XCTAssertTrue(resource.waitForExistence(timeout: stepTimeout))

        // The system Search tab opens the last workspace's own search field.
        tab("Search", in: app)
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: stepTimeout))
        XCTAssertTrue(resource.exists, "Search stays on Sites instead of opening an empty tab")

        let query = "no-such-site"
        search.tap()
        search.typeText(query)
        waitUntil(resource, is: "exists == false")

        search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: query.count))
        XCTAssertTrue(resource.waitForExistence(timeout: stepTimeout))
    }

    @MainActor
    func testConnectHostingOpensTheHostingCatalog() {
        let app = launch(Argument.sampleSite)
        XCTAssertTrue(app.buttons["Connect hosting"].waitForExistence(timeout: launchTimeout))

        app.buttons["Connect hosting"].tap()

        XCTAssertTrue(app.staticTexts["CONNECT A HOSTING PLATFORM"].waitForExistence(timeout: stepTimeout))
        XCTAssertTrue(app.buttons["connect.hosting.vercel"].exists)
        XCTAssertTrue(app.segmentedControls.firstMatch.buttons["Hosting"].isSelected)
    }

    @MainActor
    func testSiteDetailsAskForProWithoutAnEntitlement() {
        let app = launch(Argument.sampleSite)
        XCTAssertTrue(app.staticTexts["No hosting account"].waitForExistence(timeout: launchTimeout))
        tab("Sites", in: app)

        let resource = app.buttons["sites.resource.verceltics.com"]
        XCTAssertTrue(resource.waitForExistence(timeout: stepTimeout))
        resource.tap()

        // The soft paywall opens instead of the detail; UI tests never load plans.
        XCTAssertTrue(app.staticTexts["Open the whole stack."].waitForExistence(timeout: stepTimeout))
        XCTAssertTrue(app.staticTexts["Plans unavailable"].exists)
        XCTAssertTrue(app.buttons["Restore purchases"].exists)

        app.buttons["Close"].tap()

        XCTAssertTrue(resource.waitForExistence(timeout: stepTimeout))
        waitUntil(app.staticTexts["Open the whole stack."], is: "exists == false")
    }

    @MainActor
    func testWorkspaceAndAppearanceChoicesPersistAcrossRelaunch() {
        let app = launch(Argument.sampleSite)
        XCTAssertTrue(app.staticTexts["No hosting account"].waitForExistence(timeout: launchTimeout))
        tab("Sites", in: app)
        XCTAssertTrue(app.buttons["sites.resource.verceltics.com"].waitForExistence(timeout: stepTimeout))

        tab("About", in: app)
        let appearance = app.segmentedControls["about.appearancePicker"]
        XCTAssertTrue(appearance.waitForExistence(timeout: stepTimeout))
        XCTAssertTrue(appearance.buttons["System"].isSelected)
        appearance.buttons["Dark"].tap()
        waitUntil(appearance.buttons["Dark"], is: "isSelected == true")

        relaunchKeepingData(app, extraArguments: [Argument.sampleSite])

        // Sites was the last workspace, so it opens first.
        XCTAssertTrue(app.buttons["sites.resource.verceltics.com"].waitForExistence(timeout: launchTimeout))
        tab("About", in: app)
        let restored = app.segmentedControls["about.appearancePicker"]
        XCTAssertTrue(restored.waitForExistence(timeout: stepTimeout))
        XCTAssertTrue(restored.buttons["Dark"].isSelected)
        XCTAssertFalse(restored.buttons["System"].isSelected)
    }

    // MARK: - Helpers

    @MainActor
    private func launch(_ extraArguments: String...) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = baseArguments + [Argument.reset] + extraArguments
        app.launch()
        return app
    }

    private var baseArguments: [String] {
        [Argument.uiTesting, "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    }

    /// Relaunches with the same app data, as a user reopening the app would.
    @MainActor
    private func relaunchKeepingData(_ app: XCUIApplication, extraArguments: [String] = []) {
        app.terminate()
        app.launchArguments = baseArguments + extraArguments
        app.launch()
    }

    @MainActor
    private func openProviderCatalog(_ app: XCUIApplication) {
        let continueButton = app.buttons["firstConnection.continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: launchTimeout))
        continueButton.tap()
        XCTAssertTrue(app.buttons["connect.hosting.vercel"].waitForExistence(timeout: stepTimeout))
    }

    @MainActor
    private func tab(_ name: String, in app: XCUIApplication) {
        let button = app.tabBars.buttons[name]
        XCTAssertTrue(button.waitForExistence(timeout: stepTimeout), "missing \(name) tab")
        button.tap()
    }

    @MainActor
    private func waitUntil(_ element: XCUIElement, is condition: String) {
        let expectation = XCTNSPredicateExpectation(predicate: NSPredicate(format: condition), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: stepTimeout), .completed, condition)
    }
}
