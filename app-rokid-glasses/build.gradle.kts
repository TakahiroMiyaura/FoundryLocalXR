plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.flxr.rokid.glasses"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.flxr.rokid.glasses"
        minSdk = 28
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }

    lint { disable += "ExpiredTargetSdkVersion" }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}
