plugins {
    kotlin("jvm") version "2.2.21"
}

group = "com.sadurni.attacca"
version = "1.3.0"

/**
 * What the phone, the desktop app (and through their export files, the web page and the Rockbox
 * plugin) share: how music is analysed and lists are made, the playlist and inclusion formats and
 * the library exchange format. Pure Kotlin: no Android, no desktop.
 *
 * The sound classifier's weights are in assets/sound/soundnet.bin, packed by each app in its own
 * way (Android assets, desktop resources); tools/export_soundnet.py makes them.
 */
kotlin {
    jvmToolchain(17)
    // The desktop app runs on Java 11 too.
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

java {
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    mavenCentral()
}

dependencies {
    // The sound settings are kept as JSON: Android has org.json built in, the desktop app ships it.
    compileOnly("org.json:json:20240303")
}
