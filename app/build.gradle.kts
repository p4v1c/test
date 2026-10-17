plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.assistantvocal.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.assistantvocal.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.md",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":llm"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Mot d'activation hors ligne
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// ---------------------------------------------------------------------------------------------
// Modèle Vosk français (41 Mo, Apache 2.0) : téléchargé à la compilation et embarqué dans l'APK,
// pour que le mot d'activation fonctionne dès le premier lancement, même sans internet.
val voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
val voskAssetDir = layout.projectDirectory.dir("src/main/assets/model-fr")
val downloadVoskModel by tasks.registering {
    description = "Télécharge le modèle Vosk français dans les assets"
    outputs.dir(voskAssetDir)
    onlyIf { !voskAssetDir.file("uuid").asFile.exists() }
    doLast {
        val zip = layout.buildDirectory.file("vosk/model.zip").get().asFile
        zip.parentFile.mkdirs()
        if (!zip.exists()) {
            logger.lifecycle("Téléchargement du modèle Vosk…")
            uri(voskModelUrl).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        }
        val target = voskAssetDir.asFile
        target.deleteRecursively()
        copy {
            from(zipTree(zip))
            eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
            includeEmptyDirs = false
            into(target)
        }
        target.resolve("uuid").writeText("vosk-model-small-fr-0.22")
    }
}
tasks.named("preBuild") { dependsOn(downloadVoskModel) }
