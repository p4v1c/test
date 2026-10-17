package fr.assistantvocal.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * core et llm sont compilés pour la JVM, sans l'outil qui signale les API absentes d'Android (lint).
 * L'app accepte Android 10 (API 29) : ces méthodes Java 9+ y font planter l'app (NoSuchMethodError).
 * Vu sur émulateur Android 11 : « minuteur de 1 minute » plantait sur Duration.toMinutesPart().
 */
class AndroidApiCompatTest {
    private val forbidden = Regex("""\.(to(Days|Hours|Minutes|Seconds|Millis|Nanos)Part|datesUntil|readAllBytes|readNBytes|transferTo|requireNonNullElse|toSeconds)\(""")

    @Test fun `pas d'API Java absente d'Android 10 et 11 dans le code partage`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val hits = listOf("core/src/main", "llm/src/main").flatMap { dir ->
            File(root, dir).walk().filter { it.extension == "kt" }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line -> if (forbidden.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null }
            }.toList()
        }
        assertTrue("API absentes d'Android 10/11 :\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
