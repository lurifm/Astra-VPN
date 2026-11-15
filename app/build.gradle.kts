plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.astra.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.astra.vpn"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
