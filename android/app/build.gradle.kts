import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sadurni.attacca"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sadurni.attacca"
        minSdk = 26
        targetSdk = 36
        versionCode = 130
        versionName = "1.3.0"
        // On-device tests of the media library (what Android Auto sees).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Shown in the About screen.
        buildConfigField("long", "BUILD_TIME", "${buildTime()}L")
        buildConfigField("String", "MEDIA3_VERSION", "\"${libs.versions.media3.get()}\"")
        buildConfigField("String", "COMPOSE_BOM_VERSION", "\"${libs.versions.composeBom.get()}\"")
        buildConfigField("String", "KOTLIN_VERSION", "\"${libs.versions.kotlin.get()}\"")
        buildConfigField("String", "COROUTINES_VERSION", "\"${libs.versions.coroutines.get()}\"")
        buildConfigField("String", "JAUDIOTAGGER_VERSION", "\"${libs.versions.jaudiotagger.get()}\"")
        buildConfigField("String", "ANDROIDX_CORE_VERSION", "\"${libs.versions.coreKtx.get()}\"")
        buildConfigField("String", "PALETTE_VERSION", "\"${libs.versions.palette.get()}\"")
    }

    // Publishing: the release key, from keystore.properties next to this file (never in the
    // repository): storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are
    // signed with the debug key, so they install over debug builds (personal builds). With the
    // property unsigned=true (gradle -Punsigned=true, as F-Droid builds it) they are left unsigned:
    // the store signs them itself.
    val unsigned = project.findProperty("unsigned")?.toString() == "true"
    // debugSign=true: the debug key even with the release key at hand (personal builds that must
    // install over an app signed with the debug key).
    val debugSign = project.findProperty("debugSign")?.toString() == "true"
    val keystore = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Release builds are not debuggable, which makes the audio processing much faster.
            signingConfig = if (unsigned) null else signingConfigs.getByName(if (keystore != null && !debugSign) "release" else "debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Lets code that logs run in JVM unit tests (android.util.Log returns defaults).
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    // The sound classifier's weights, shared with the desktop app (../core/assets/sound).
    sourceSets["main"].assets.srcDir(rootProject.file("../core/assets"))

    // No dependency list encrypted for Google Play inside the APK: F-Droid refuses it, and it
    // makes builds differ.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

/**
 * When the build's source was last changed (the last commit), so the same source gives the same
 * APK (reproducible builds); the time of the build outside a git checkout.
 */
fun buildTime(): Long = runCatching {
    val process = ProcessBuilder("git", "log", "-1", "--format=%ct").directory(rootDir).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) out.toLong() * 1000 else null
}.getOrNull() ?: System.currentTimeMillis()

// The notes of each version, written once where F-Droid reads them
// (fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt), go inside the app too, for its
// What's new screen (assets/changelog/<locale>/changelogs/<versionCode>.txt).
abstract class CopyChangelogs : DefaultTask() {
    @get:InputFiles
    abstract val notes: ConfigurableFileCollection

    @get:Internal
    abstract val metadata: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val root = metadata.get().asFile
        val target = output.get().asFile.resolve("changelog")
        target.deleteRecursively()
        for (file in notes.files) file.copyTo(target.resolve(file.relativeTo(root).invariantSeparatorsPath), overwrite = true)
    }
}

val copyChangelogs = tasks.register<CopyChangelogs>("copyChangelogs") {
    val root = rootProject.layout.projectDirectory.dir("../fastlane/metadata/android")
    metadata.set(root)
    notes.from(root.asFileTree.matching { include("*/changelogs/*.txt") })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyChangelogs, CopyChangelogs::output)
    }
}

dependencies {
    implementation("com.sadurni.attacca:core")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.palette)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    // Tag writing for the tag editor (LGPL).
    implementation(libs.jaudiotagger)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // Real org.json for JVM unit tests (Android's is a stub there).
    testImplementation(libs.org.json)
}
