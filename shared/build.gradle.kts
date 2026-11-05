// Kotlin shared by the TV / phone app, the Windows app and (phase 1 of the iPhone version) the iPhone app: parsers,
// Xtream API, TMDB rows and search, sports, profile sync. Code that does not depend on the platform goes to
// commonMain; what uses the JVM (OkHttp, javax.crypto, java.io…) stays in jvmMain until it has an iOS version.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // expect / actual classes (IOException): still marked Beta by Kotlin, used here for one exception type.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // The iPhone (and its simulator, for the tests). Built on a Mac only (GitHub Actions: shared-ios.yml);
    // on Windows these targets are skipped and the TV / PC builds are unchanged.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            // Dates and time zones the same on the JVM and iOS (guide, sports).
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            // Locks shared by the JVM and the iPhone (caches read from several threads).
            api("org.jetbrains.kotlinx:atomicfu:0.25.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            api("com.squareup.okhttp3:okhttp:4.12.0")
            api("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
            // XmlPullParser is built into Android; the Windows app adds kxml2 as the implementation.
            compileOnly("net.sf.kxml:kxml2:2.3.0")
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation("net.sf.kxml:kxml2:2.3.0")
        }
    }
}
