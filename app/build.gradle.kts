import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key lives outside the repo; without it, release builds are left unsigned
val signingFile = file("${System.getProperty("user.home")}/.android-keystores/wizmusicsync.properties")
val signingProps = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "com.wizmusicsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wizmusicsync"
        // AudioPlaybackCapture needs Android 10 (API 29)
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (signingFile.exists()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
