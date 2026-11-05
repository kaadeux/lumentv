// Phase 3 of the iPhone version: the phone screens in Compose Multiplatform, over :appcore. Built into the
// framework "LumenKit" that the iPhone app (iosApp/) embeds; the Android phone moves its screens here step by step.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
}

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // Built on a Mac only (GitHub Actions: ios-app.yml); skipped on Windows.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { t ->
        t.binaries.framework {
            baseName = "LumenKit"
            isStatic = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":appcore"))
            implementation(project(":uikit"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            // The Android phone's icons (tabs, title bar).
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            implementation(compose.ui)
            implementation("io.coil-kt.coil3:coil-compose:3.0.4")
        }
        androidMain.dependencies {
            implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
        }
        iosMain.dependencies {
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.0.4")
            implementation("io.ktor:ktor-client-darwin:3.0.1")
        }
    }
}

android {
    namespace = "com.lumentv.phoneui"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
