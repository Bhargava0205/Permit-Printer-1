plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The signing key is provided by the build workflow (decoded from a GitHub
// secret). If it is missing, the build still works and produces a debug APK.
val keystoreFile = rootProject.file("app/permitprint.jks")
val hasKeystore = keystoreFile.exists()

android {
    namespace = "com.example.permitprint"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.permitprint"
        minSdk = 24
        targetSdk = 34
        versionCode = 22
        versionName = "2.2"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "amcpalamaner"
                keyAlias = System.getenv("KEY_ALIAS") ?: "permitprint"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "amcpalamaner"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { }
