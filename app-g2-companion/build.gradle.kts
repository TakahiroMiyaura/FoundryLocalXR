plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val flVersion = providers.gradleProperty("foundryLocal.version").get()
val ktorVersion = "3.6.0"

android {
    namespace = "com.example.flxr.g2companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.flxr.g2companion"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/*.kotlin_module")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
}
