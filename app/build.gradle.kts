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
        // The current target device is a modern 64-bit Android phone.
        // Exclude unused native architectures from the APK package.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.github.singbox-android:libbox:1.14.3")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("junit:junit:4.13.2")
}
