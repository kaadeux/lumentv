import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.lumentv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lumentv"
        minSdk = 23
        targetSdk = 34
        // Raise versionCode on every release: installed copies only accept a higher one as an update.
        versionCode = 98
        versionName = "2.6.31"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Family access code and update feed, from local.properties (never committed). Only the code's hash is built in.
        val code = localProp("lumen.accessCode").trim()
        buildConfigField("String", "ACCESS_HASH", "\"${if (code.isEmpty()) "" else sha256("lumen-access:$code")}\"")
        // Update channel: "main" (family) or "dev" (testing at home), chosen by publish.ps1 / deploy.ps1 from the git branch.
        // Each channel follows the version.json of the same-named branch in the releases repository.
        val channel = (project.findProperty("lumenChannel") as String?) ?: "main"
        require(channel == "main" || channel == "dev") { "lumenChannel must be main or dev" }
        if (channel == "dev") versionNameSuffix = "-dev"
        buildConfigField("String", "CHANNEL", "\"$channel\"")
        buildConfigField("String", "SYNC_URL", "\"${localProp("lumen.syncUrl").trim()}\"")
        // TMDB key (free, read only): the titles' logos for the banners and pages. Empty = the title in text.
        buildConfigField("String", "TMDB_KEY", "\"${localProp("lumen.tmdbKey").trim()}\"")
        buildConfigField("String", "UPDATE_URL", "\"${localProp("lumen.updateUrl").trim().replace("/main/", "/$channel/")}\"")
    }

    val signingFile = rootProject.file("local.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("lumen.signing") }
        ?.let { file(it) }?.takeIf { it.exists() }
    if (signingFile != null) {
        val s = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(s.getProperty("storeFile"))
            storePassword = s.getProperty("storePassword")
            keyAlias = s.getProperty("keyAlias")
            keyPassword = s.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release key kept outside the project (path in local.properties: lumen.signing=...keystore.properties).
            // Without it the build falls back to the debug key, which cannot update a release-signed install.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // The database and what it reads (shared with the iPhone): module :appcore.
    val room = "2.7.2"
    implementation(project(":appcore"))
    implementation(project(":uikit"))
    // The settings page shared with the iPhone.
    implementation(project(":phoneui"))

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:$room")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

fun localProp(name: String): String = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty(name) }.orEmpty()

fun sha256(s: String): String = MessageDigest.getInstance("SHA-256")
    .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
