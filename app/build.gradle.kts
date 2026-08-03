plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersion: String = (project.findProperty("appVersion") as String?) ?: "0.0"
val appCode: Int = ((project.findProperty("appCode") as String?) ?: "1").toInt()

val keystoreFile = rootProject.file("app/permitprint.jks")
val hasKeystore = keystoreFile.exists()

android {
    namespace = "com.example.permitprint"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.permitprint"
        minSdk = 24
        targetSdk = 34
        versionCode = appCode
        versionName = appVersion
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
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
