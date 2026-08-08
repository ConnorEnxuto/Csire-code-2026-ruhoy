pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // TODO: add RayNeo's Maven repo here once you have the URL from the
        // RayNeo Open Platform / Android ARDK docs, e.g.:
        // maven { url = uri("https://maven.rayneo.com/repository") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Same RayNeo Maven repo goes here too, since ARDK is likely
        // distributed as an AAR via a hosted repo or a local .aar file.

        // MbientLab's own Ivy repo (mbientlab.com/releases/ivyrep) that used to host
        // com.mbientlab:metawear is dead (redirects to a 404 page as of 2026-07).
        // Building the SDK from its GitHub source via JitPack instead.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MovementCheck"
include(":app")
