plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val flVersion = providers.gradleProperty("foundryLocal.version").get()

android {
    namespace = "com.example.flxr.rokid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.flxr.rokid"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    flavorDimensions += "runtime"
    productFlavors {
        create("embedded") { dimension = "runtime" }
        create("ipc") {
            dimension = "runtime"
            applicationIdSuffix = ".ipc"
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    implementation(project(":core"))
    "embeddedImplementation"(files(rootProject.file("libs/foundry-local-embedded-sdk-$flVersion.aar")))
    "ipcImplementation"(files(rootProject.file("libs/foundry-local-ipc-sdk-$flVersion.aar")))
    "ipcImplementation"("androidx.core:core-ktx:1.17.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
