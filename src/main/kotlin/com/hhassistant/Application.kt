package com.hhassistant

import io.github.cdimascio.dotenv.Dotenv
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.io.File
import java.nio.charset.Charset

@SpringBootApplication
@EnableScheduling
class Application

fun main(args: Array<String>) {
    loadDotEnvIntoSystemProperties()

    runApplication<Application>(*args)
}

private fun loadDotEnvIntoSystemProperties() {
    val log = KotlinLogging.logger {}
    val dotEnvFile = File(".env")
    if (!dotEnvFile.exists()) return

    // dotenv-java does not reliably handle BOM on Windows-created files (e.g. UTF-8 BOM).
    // If we detect a BOM, use the fallback parser directly to avoid noisy exceptions on startup.
    if (hasBom(dotEnvFile)) {
        loadDotEnvFallback(dotEnvFile, log)
        return
    }

    // Load .env file if it exists.
    // Important: on Windows, .env is often saved as UTF-16 LE (PowerShell default),
    // which dotenv-java may fail to parse. We fallback to a simple parser with BOM detection.
    try {
        val dotenv = Dotenv.configure()
            .directory(".")
            .ignoreIfMissing()
            .load()

        dotenv.entries().forEach { entry ->
            System.setProperty(entry.key, entry.value)
        }

        if (dotenv.entries().isNotEmpty()) {
            log.info { "Loaded ${dotenv.entries().size} keys from .env into System properties" }
        } else {
            log.warn { ".env file exists, but no keys were loaded by dotenv-java; falling back to BOM-aware parsing" }
            loadDotEnvFallback(dotEnvFile, log)
        }
    } catch (e: Exception) {
        log.warn(e) { "Failed to load .env via dotenv-java; falling back to BOM-aware parsing" }
        loadDotEnvFallback(dotEnvFile, log)
    }
}

private fun loadDotEnvFallback(dotEnvFile: File, log: mu.KLogger) {
    val bytes = dotEnvFile.readBytes()
    val charset = detectCharset(bytes)
    val text = bytes.toString(charset)

    var loaded = 0
    text.lineSequence()
        .map { it.trim().removePrefix("\uFEFF") } // handle UTF-8 BOM at start of file/line
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .forEach { lineRaw ->
            val idx = lineRaw.indexOf('=')
            if (idx <= 0) return@forEach
            val key = lineRaw.substring(0, idx).trim()
            var value = lineRaw.substring(idx + 1).trim()

            // Strip surrounding quotes (common in .env files)
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length - 1)
            }

            // Do not override Spring defaults with blank values like "PROXY_PORT="
            if (value.isBlank()) return@forEach

            if (key.isNotBlank()) {
                System.setProperty(key, value)
                loaded += 1
            }
        }

    log.info { "Loaded $loaded keys from .env via fallback parser (charset=${charset.name()})" }
}

private fun detectCharset(bytes: ByteArray): Charset {
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
    ) {
        return Charsets.UTF_8
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
    return Charsets.UTF_8
}

private fun hasBom(file: File): Boolean {
    val header = file.inputStream().use { it.readNBytes(3) }
    if (header.size >= 3 &&
        header[0] == 0xEF.toByte() &&
        header[1] == 0xBB.toByte() &&
        header[2] == 0xBF.toByte()
    ) {
        return true
    }
    if (header.size >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xFE.toByte()) return true
    if (header.size >= 2 && header[0] == 0xFE.toByte() && header[1] == 0xFF.toByte()) return true
    return false
}
