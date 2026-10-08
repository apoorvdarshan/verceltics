plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Same public Google OAuth client as iOS (ios/verceltics-Info.plist). Its custom-scheme redirect
// works for the browser PKCE flow on Android too. Forks can override it with the Gradle property.
val googleOAuthClientId = providers.gradleProperty("VERCELTICS_GOOGLE_OAUTH_CLIENT_ID")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: "804271028953-rc53qhcvdki0rpe2pe98gsahs8dimem7.apps.googleusercontent.com"
val configuredGoogleOAuthRedirectScheme = providers.gradleProperty("VERCELTICS_GOOGLE_OAUTH_REDIRECT_SCHEME")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
val derivedGoogleOAuthRedirectScheme = googleOAuthClientId
    .takeIf { it.endsWith(".apps.googleusercontent.com") }
    ?.removeSuffix(".apps.googleusercontent.com")
    ?.takeIf(String::isNotEmpty)
    ?.let { "com.googleusercontent.apps.$it" }
if (configuredGoogleOAuthRedirectScheme != null &&
    configuredGoogleOAuthRedirectScheme != derivedGoogleOAuthRedirectScheme
) {
    throw GradleException("VERCELTICS_GOOGLE_OAUTH_REDIRECT_SCHEME must be the reverse Google client id.")
}
val googleOAuthRedirectScheme = derivedGoogleOAuthRedirectScheme
    ?: "verceltics-oauth-unconfigured"

// RevenueCat public SDK key for the Google Play app (goog_...). Debug builds may use a Test Store
// key (test_...); the SDK deliberately crashes release builds that ship one.
val revenueCatApiKey = providers.gradleProperty("VERCELTICS_REVENUECAT_API_KEY")
    .orNull
    ?.trim()
    .orEmpty()
if (revenueCatApiKey.isNotEmpty() &&
    !revenueCatApiKey.startsWith("goog_") &&
    !revenueCatApiKey.startsWith("test_")
) {
    throw GradleException("VERCELTICS_REVENUECAT_API_KEY must be a RevenueCat Google Play (goog_) or Test Store (test_) key.")
}

// Play upload key, kept outside the repository. Builds without these properties stay unsigned.
val uploadKeystorePath = providers.gradleProperty("VERCELTICS_UPLOAD_KEYSTORE")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
val uploadKeystorePassword = providers.gradleProperty("VERCELTICS_UPLOAD_KEYSTORE_PASSWORD_FILE")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let { providers.fileContents(layout.projectDirectory.file(it)).asText.get().trim() }

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.apoorvdarshan.verceltics"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.apoorvdarshan.verceltics"
        minSdk = 28
        targetSdk = 36
        versionCode = 43
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField(
            "String",
            "GOOGLE_OAUTH_CLIENT_ID",
            googleOAuthClientId.asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "GOOGLE_OAUTH_REDIRECT_SCHEME",
            googleOAuthRedirectScheme.asBuildConfigString(),
        )
        manifestPlaceholders["googleOAuthRedirectScheme"] = googleOAuthRedirectScheme
        buildConfigField(
            "String",
            "REVENUECAT_API_KEY",
            revenueCatApiKey.asBuildConfigString(),
        )
    }

    signingConfigs {
        if (uploadKeystorePath != null && uploadKeystorePassword != null) {
            create("upload") {
                storeFile = file(uploadKeystorePath)
                storePassword = uploadKeystorePassword
                keyAlias = "upload"
                keyPassword = uploadKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.revenuecat.purchases:purchases:10.26.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// Release artifacts must never carry a Test Store key, and the Play upload bundle needs the real
// Google Play key so purchases work for testers and customers.
val validateRevenueCatReleaseKey by tasks.registering {
    val key = revenueCatApiKey
    doLast {
        if (key.startsWith("test_")) {
            throw GradleException("RevenueCat Test Store keys crash release builds. Use the goog_ key.")
        }
    }
}
val validateRevenueCatPlayKey by tasks.registering {
    val key = revenueCatApiKey
    doLast {
        if (!key.startsWith("goog_")) {
            throw GradleException("Set VERCELTICS_REVENUECAT_API_KEY to the RevenueCat goog_ key before bundling for Google Play.")
        }
    }
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(validateRevenueCatReleaseKey)
}
tasks.matching { it.name == "bundleRelease" }.configureEach {
    dependsOn(validateRevenueCatPlayKey)
}
