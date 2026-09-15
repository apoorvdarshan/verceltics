plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val googleOAuthClientId = providers.gradleProperty("VERCELTICS_GOOGLE_OAUTH_CLIENT_ID")
    .orNull
    ?.trim()
    .orEmpty()
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

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.apoorvdarshan.verceltics"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.apoorvdarshan.verceltics"
        minSdk = 28
        targetSdk = 36
        versionCode = 42
        versionName = "3.0"

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
    }

    buildTypes {
        release {
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

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
