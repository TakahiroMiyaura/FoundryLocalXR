pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FoundryLocalXR"

include(":core")
include(":app-panel")
include(":app-rokid")
include(":app-rokid-glasses")
include(":unity-bridge")
include(":app-g2-companion")
