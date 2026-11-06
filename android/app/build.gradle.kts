plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val code = (System.getenv("VERSION_CODE") ?: "1").toInt()
android {
    namespace = "com.oscarvelz.weekly"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.oscarvelz.weekly"
        minSdk = 30
        targetSdk = 34
        versionCode = code
        versionName = "1.$code"
    }
    // Signing: never from a file in the repo. Only when the build is handed a keystore by path and password
    // (WEEKLY_KS_FILE + WEEKLY_KS_PASS, e.g. decoded from GitHub secrets at runtime); otherwise the release APK is
    // left unsigned and is signed privately afterwards (see the android-signed branch).
    val ksFile = System.getenv("WEEKLY_KS_FILE")?.takeIf { it.isNotBlank() }
    val ksPass = System.getenv("WEEKLY_KS_PASS")?.takeIf { it.isNotBlank() }
    if (ksFile != null && ksPass != null) {
        signingConfigs {
            create("rel") {
                storeFile = file(ksFile)
                storePassword = ksPass
                keyAlias = "weekly"
                keyPassword = ksPass
                storeType = "pkcs12"
            }
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfigs.findByName("rel")?.let { signingConfig = it } } }
    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // android-4: FileProvider, so a backup can be handed to Android's share sheet.
    implementation("androidx.core:core:1.13.1")
}
