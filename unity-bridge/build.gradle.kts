plugins {
    id("com.android.library")
}

val flVersion = providers.gradleProperty("foundryLocal.version").get()

android {
    namespace = "com.example.flxr.unity"
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
    implementation(project(":core"))
    compileOnly(files(rootProject.file("libs/foundry-local-embedded-sdk-$flVersion.aar")))
}

val distDir = layout.projectDirectory.dir("dist")

val cleanDist by tasks.registering(Delete::class) {
    delete(distDir)
}

val bridgeAar = layout.buildDirectory.file("outputs/aar/unity-bridge-release.aar")
val coreAar = project(":core").layout.buildDirectory.file("outputs/aar/core-release.aar")

val dist by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Collects the AARs and Gradle snippets a Unity project needs into unity-bridge/dist."
    dependsOn(cleanDist, "assembleRelease", ":core:assembleRelease")

    into(distDir)
    from(bridgeAar) {
        rename { "flxr-unity-bridge.aar" }
        into("Plugins/Android")
    }
    from(coreAar) {
        rename { "flxr-core.aar" }
        into("Plugins/Android")
    }
    from(rootProject.file("libs/foundry-local-embedded-sdk-$flVersion.aar")) { into("runtime-embedded") }
    from(rootProject.file("libs/foundry-local-ipc-sdk-$flVersion.aar")) { into("runtime-ipc") }
    from(rootProject.file("libs/foundry-local-android-sdk-license-$flVersion.txt"))
    from(layout.projectDirectory.dir("unity-gradle")) { into("unity-gradle") }
}
