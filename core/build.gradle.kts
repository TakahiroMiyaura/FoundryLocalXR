plugins {
    id("com.android.library")
}

val flVersion = providers.gradleProperty("foundryLocal.version").get()

android {
    namespace = "com.example.flxr.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
        aarMetadata { minCompileSdk = 33 }
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    compileOnly(files(rootProject.file("libs/foundry-local-embedded-sdk-$flVersion.aar")))

    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
