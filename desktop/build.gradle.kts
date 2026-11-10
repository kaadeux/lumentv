import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties
import java.security.MessageDigest

// Lumen TV for Windows: same playlists, profile and sync as the TV app, playback through an embedded VLC engine.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

val appVersion = "1.9.26"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":uikit"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("net.sf.kxml:kxml2:2.3.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.lumentv.desktop.MainKt"
        // 2 GB at most: a list of 250 000 titles, the one shown and the one being reloaded at once.
        jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")
        buildTypes.release.proguard { isEnabled.set(false) }
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Lumen TV"
            packageVersion = appVersion
            description = "Lecteur IPTV familial"
            vendor = "Lumen TV"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            // jdk.localedata: French day and month names (without it dates fall back to English).
            modules("java.sql", "java.naming", "jdk.crypto.ec", "jdk.unsupported", "jdk.localedata")
            windows {
                menu = true
                shortcut = true
                // Per machine (Program Files, one admin prompt): a per-user install runs without rights and fails on a
                // drive whose system "Config.Msi" folder it may not touch ("Could not set file security… Error 5").
                perUserInstall = false
                menuGroup = "Lumen TV"
                // Fixed, so a new installer upgrades the previous version instead of installing a second copy.
                upgradeUuid = "6b1f0a52-3c1e-4d5e-9b8a-2f4c7d9e1a33"
                iconFile.set(project.layout.projectDirectory.file("icon.ico"))
            }
        }
    }
}

// Constants from local.properties (never committed): sync server, update feed, family access code (hash only).
fun localProp(name: String): String = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty(name) }.orEmpty().trim()
fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

val buildInfoDir = layout.buildDirectory.dir("generated/buildinfo")
val buildInfo by tasks.registering {
    val code = localProp("lumen.accessCode")
    val sync = localProp("lumen.syncUrl")
    val update = localProp("lumen.updateUrl").replace("/version.json", "/desktop.json")
    // TMDB key (free, read only): logos, ratings, summaries, cast and the rows of the Films / Séries homes.
    val tmdb = localProp("lumen.tmdbKey")
    inputs.property("v", appVersion); inputs.property("s", sync); inputs.property("u", update); inputs.property("c", code.isNotEmpty()); inputs.property("t", tmdb)
    outputs.dir(buildInfoDir)
    doLast {
        val hash = if (code.isEmpty()) "" else sha256("lumen-access:$code")
        val f = buildInfoDir.get().file("com/lumentv/desktop/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package com.lumentv.desktop\n\nobject BuildInfo {\n    const val VERSION = \"$appVersion\"\n" +
            "    const val SYNC_URL = \"$sync\"\n    const val UPDATE_URL = \"$update\"\n    const val ACCESS_HASH = \"$hash\"\n    const val TMDB_KEY = \"$tmdb\"\n}\n")
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(buildInfoDir) }
tasks.named("compileKotlin") { dependsOn(buildInfo) }
