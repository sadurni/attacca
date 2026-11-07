plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
    distribution
}

group = "com.sadurni.attaccaplg"
version = "1.1.0"

kotlin {
    jvmToolchain(17)
    // Runs on Java 11 too (the PC's default Java); the installer will bring its own.
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

java {
    targetCompatibility = JavaVersion.VERSION_11
}

// The sound classifier's weights, shared with the Android app (../core/assets/sound).
// The phone's gallery of themes, whole (pictures, icons, typefaces: a copy edited here goes back to
// the phone as it was), with an index to find them in the jar.
tasks.processResources {
    from("../android/app/src/main/assets/themes") {
        include("*.json", "*/*.jpg", "*/*.png", "*/*.webp", "*/*.ttf", "*/*.otf")
        into("themes")
    }
    doLast {
        val dir = destinationDir.resolve("themes").apply { mkdirs() }
        dir.resolve("index.txt").writeText(dir.list().orEmpty().filter { it.endsWith(".json") }.sorted().joinToString(" "))
    }
}

sourceSets {
    main {
        resources.srcDir("../core/assets/sound")
    }
}

dependencies {
    // How music is analysed and lists are made: the same code as the phone (../core).
    implementation("com.sadurni.attacca:core")
    // Tags (as Attacca).
    implementation("net.jthink:jaudiotagger:3.0.1")
    // Decoders for Java Sound: MP3, Ogg Vorbis, FLAC (WAV/AIFF are built in).
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("org.jflac:jflac-codec:1.5.2")
    // The analysis cache.
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.json:json:20240303")
    // The desktop window.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Media keys of the keyboard, also with the window in the background (LGPL).
    implementation("com.github.kwhat:jnativehook:2.2.2")
    // The window's thread for coroutines (Dispatchers.Main).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

/** The command line (`attacca-desktop`): start scripts and jars, zipped by `distZip`. */
val cliScripts by tasks.registering(CreateStartScripts::class) {
    mainClass.set("com.sadurni.attaccaplg.MainKt")
    applicationName = "attacca-desktop"
    defaultJvmOpts = listOf("-Xmx2g")
    outputDir = layout.buildDirectory.dir("cli-scripts").get().asFile
    classpath = files(tasks.jar) + configurations.runtimeClasspath.get()
}

/** The window from the same zip (any system with Java 17): `attacca-desktop-window`. */
val windowScripts by tasks.registering(CreateStartScripts::class) {
    mainClass.set("com.sadurni.attaccaplg.ui.AppKt")
    applicationName = "attacca-desktop-window"
    defaultJvmOpts = listOf("-Xmx2g")
    outputDir = layout.buildDirectory.dir("window-scripts").get().asFile
    classpath = files(tasks.jar) + configurations.runtimeClasspath.get()
}

/** The window's drawing library for the other systems (the build's own is in runtimeClasspath). */
val otherSystems: Configuration by configurations.creating
dependencies {
    otherSystems(compose.desktop.linux_x64)
    otherSystems(compose.desktop.linux_arm64)
    otherSystems(compose.desktop.macos_x64)
    otherSystems(compose.desktop.macos_arm64)
}

distributions {
    main {
        distributionBaseName.set("attacca-desktop")
        contents {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            into("bin") {
                from(cliScripts)
                from(windowScripts)
            }
            into("lib") {
                from(tasks.jar)
                from(configurations.runtimeClasspath)
                from(otherSystems)
            }
        }
    }
}

/** Development: the window's pages drawn to PNG files (no screen needed). Args: library, output folder, language, theme. */
tasks.register<JavaExec>("snapshots") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.ui.SnapshotsKt")
    // Its own data folder: the queue and lists it plays with are not the user's.
    systemProperty("attacca.home", layout.buildDirectory.dir("snapshot-home").get().asFile.path)
    args = (project.findProperty("snapArgs") as String? ?: "").split('|').filter { it.isNotEmpty() }
}

/** Development: a library made straight from an .attlib (its music need not be here). Args: attlib, library. */
tasks.register<JavaExec>("libraryFromAttlib") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.ui.SnapshotsKt")
    args = listOf("--from-attlib") + (project.findProperty("snapArgs") as String? ?: "").split('|').filter { it.isNotEmpty() }
}

/** Development: how well chaining works on real music (see ChainBench). Args: folder|edge seconds|max songs|report. */
tasks.register<JavaExec>("chainBench") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.bench.ChainBenchKt")
    maxHeapSize = "8g"
    // Positions matter (an empty one keeps its default).
    args = (project.findProperty("benchArgs") as String? ?: "").split('|')
}

/** Development: the player's engine into a file, for checking joins (see PlayerCheck). Args: db|out.f32|paths… */
tasks.register<JavaExec>("playerCheck") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.bench.PlayerCheckKt")
    args = (project.findProperty("benchArgs") as String? ?: "").split('|')
}

/** Development: what a library holds that a mood could come from, as CSV (see MoodData). Args: db|weights|out.csv. */
tasks.register<JavaExec>("moodData") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.bench.MoodDataKt")
    maxHeapSize = "4g"
    args = (project.findProperty("benchArgs") as String? ?: "").split('|')
}

/** Development: features, moods and families of real music for comparison (see MoodBench). Args: folder|out.csv. */
tasks.register<JavaExec>("moodBench") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.bench.MoodBenchKt")
    maxHeapSize = "8g"
    args = (project.findProperty("benchArgs") as String? ?: "").split('|')
}

/** Development: Attacca's sound network against the original model (see SoundNetCheck). */
tasks.register<JavaExec>("soundNetCheck") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.bench.SoundNetCheckKt")
}

/** Development: sound profiles, families and likeness on real music (see SoundBench). Args: folder|weights. */
tasks.register<JavaExec>("soundBench") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.bench.SoundBenchKt")
    maxHeapSize = "8g"
    args = (project.findProperty("benchArgs") as String? ?: "").split('|')
}

tasks.register<JavaExec>("resampleCheck") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.bench.ResampleCheckKt")
    args = (project.findProperty("benchArgs") as String? ?: "").split('|')
}

tasks.register<JavaExec>("resampleSine") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.bench.ResampleSineKt")
}

tasks.test {
    useJUnit()
}

/** The desktop app (the window); the command line above stays as it is. */
compose.desktop {
    application {
        mainClass = "com.sadurni.attaccaplg.ui.AppKt"
        jvmArgs += listOf("-Xmx2g")
        // jpackage needs JDK 17 (Gradle's own Java may be older).
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }.get()
            .metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            // Each system builds its own: the .msi on Windows, the .deb on Linux (in WSL here, see README).
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
            )
            packageName = "AttaccaDesktop"
            packageVersion = version.toString()
            description = "Attacca Desktop"
            vendor = "Attacca"
            // SQLite (java.sql), sound and dialogs (java.desktop), screen readers when the system has them
            // on (jdk.accessibility), Spanish number formats (jdk.localedata), tag reading (java.logging, java.prefs),
            // covers searched online (java.net.http).
            modules(
                "java.sql", "java.desktop", "java.logging", "java.prefs", "java.instrument", "java.naming", "java.net.http",
                "jdk.unsupported", "jdk.accessibility", "jdk.localedata", "jdk.charsets", "jdk.crypto.ec",
            )
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menu = true
                menuGroup = "Attacca"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1d1a52-5b0e-4c55-9d7a-2a8b8f1c3e47"
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                packageName = "attacca-desktop"
                debMaintainer = "attacca@localhost"
                menuGroup = "AudioVideo;Audio"
                appCategory = "sound"
                shortcut = true
            }
        }
    }
}

// The version the window shows (About).
tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

/** Development: Attacca's measures written into a beets library (see BeetsCheck). Args: db|beet. */
tasks.register<JavaExec>("beetsCheck") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.bench.BeetsCheckKt")
    args = (project.findProperty("benchArgs") as String? ?: "").split('|')
}


/** Development: the phone's Now playing of the themes on every screen size. Args: output folder. */
tasks.register<JavaExec>("themeShots") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.ui.ThemeShotsKt")
    systemProperty("attacca.home", layout.buildDirectory.dir("snapshot-home").get().asFile.path)
    args = listOf(layout.buildDirectory.dir("theme-shots").get().asFile.path)
}

/** Development: the layout editor before and after a drag. Args: output folder|"x1,y1 x2,y2". */
tasks.register<JavaExec>("layoutDrag") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.ui.LayoutDragShotsKt")
    (project.findProperty("shotsLang") as String?)?.let { systemProperty("shots.lang", it) }
    systemProperty("attacca.home", layout.buildDirectory.dir("snapshot-home").get().asFile.path)
    args = (project.findProperty("dragArgs") as String? ?: "").split('|').filter { it.isNotEmpty() }
}

/** Development: Attacca O'rama's worlds drawn to PNG files. Args: output folder. */
tasks.register<JavaExec>("oramaShots") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.ui.OramaShotsKt")
    args = listOf(layout.buildDirectory.dir("orama-shots").get().asFile.path)
}

/** Development: which tracks O'rama splits into stretches of other worlds. Args: library.db|words… */
tasks.register<JavaExec>("oramaSections") {
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.sadurni.attaccaplg.ui.OramaSectionsKt")
    args = (project.findProperty("snapArgs") as String? ?: "").split('|').filter { it.isNotEmpty() }
}
