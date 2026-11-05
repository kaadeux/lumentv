// What the TV / phone app and the iPhone app share above :shared (phase 2 of the iPhone version): the local
// database (playlists, channels, films, series, favourites, history, guide, downloads, ratings) and, step by step,
// the repositories that read and fill it. The Windows app keeps its own storage and does not use this module.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val room = "2.7.2"

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // Built on a Mac only (GitHub Actions: shared-ios.yml); skipped on Windows.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            api("androidx.room:room-runtime:$room")
            // The settings (same "settings" file as before on the TV and the phone).
            api("androidx.datastore:datastore-preferences-core:1.1.1")
        }
        androidMain.dependencies {
            implementation("androidx.datastore:datastore-preferences:1.1.1")
            // The profile syncs when the app comes to the foreground (ProcessLifecycleOwner).
            implementation("androidx.lifecycle:lifecycle-process:2.8.7")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        iosMain.dependencies {
            // The iPhone has no SQLite through Room's Android layer: SQLite built into the app.
            implementation("androidx.sqlite:sqlite-bundled:2.5.2")
        }
    }
}

android {
    namespace = "com.lumentv.appcore"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The schema of each version, kept in git: the automatic migrations are built from them (the TV keeps its data).
room { schemaDirectory("$projectDir/schemas") }

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:$room")
    add("kspIosArm64", "androidx.room:room-compiler:$room")
    add("kspIosSimulatorArm64", "androidx.room:room-compiler:$room")
}
