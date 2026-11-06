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
    signingConfigs {
        create("rel") {
            storeFile = file("../weekly.p12")
            storePassword = System.getenv("KS_PASS")
            keyAlias = "weekly"
            keyPassword = System.getenv("KS_PASS")
            storeType = "pkcs12"
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("rel") } }
    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // android-4: FileProvider, so a backup can be handed to Android's share sheet.
    implementation("androidx.core:core:1.13.1")
}
