plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.movementcheck"
    compileSdk = 34

    defaultConfig {
        // Distinct from the rep-counter build so both can be installed on the glasses at once.
        applicationId = "com.example.formcheck"
        minSdk = 30        // required by com.mbientlab:metawear:4.0.0 (live IMU sensor SDK)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // Live IMU sensor: MbientLab MetaWear (matches the wrist/arm-worn sensor the
    // original LiftRight iOS app used). MbientLab's own ivy repo is dead, so this is
    // built from GitHub source via JitPack instead - see settings.gradle.kts.
    // Uses Bolts (Task) for async, matching the library's own API.
    implementation("com.github.mbientlab:MetaWear-SDK-Android:4.0.0")
    implementation("com.parse.bolts:bolts-tasks:1.4.0")

    // Camera + pose detection — swap/extend these once you wire up
    // real movement detection instead of the placeholder timer.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // TODO: RayNeo Android ARDK dependency goes here, e.g.:
    // implementation("com.rayneo:ardk:<version>")
    // or implementation(files("libs/rayneo-ardk.aar"))
}
