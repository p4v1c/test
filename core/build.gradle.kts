plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Vosk version PC : uniquement pour le test d'intégration du mot d'activation (ignoré sans modèle).
    testImplementation("com.alphacephei:vosk:0.3.45")
}

tasks.withType<Test>().configureEach {
    // Chemins optionnels pour le test d'intégration du mot d'activation.
    System.getenv("VOSK_MODEL")?.let { systemProperty("vosk.model", it) }
    System.getenv("WAKE_FIXTURES")?.let { systemProperty("wake.fixtures", it) }
    testLogging { showStandardStreams = true }
}
