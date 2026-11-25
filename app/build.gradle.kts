plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is enabled only when all four CI-provided values exist and
// the workflow has decoded the keystore into the runner's temporary directory.
val releaseStoreFile = System.getenv("ASTRA_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("ASTRA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ASTRA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ASTRA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    file(releaseStoreFile).isFile

android {
    namespace = "com.astra.vpn"

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
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

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation("com.github.singbox-android:libbox:1.14.3")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
