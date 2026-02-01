package com.hhassistant.service

import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Сервис для работы с .env файлом
 */
@Service
class EnvFileService {
    private val log = KotlinLogging.logger {}
    private val envFile = File(".env")

    /**
     * Обновляет или добавляет переменную в .env файл
     *
     * @param key Имя переменной (например, "HH_ACCESS_TOKEN")
     * @param value Значение переменной
     * @return true если успешно обновлено, false если ошибка
     */
    fun updateEnvVariable(key: String, value: String): Boolean {
        return try {
            if (!envFile.exists()) {
                // Создаем новый файл, если его нет
                envFile.createNewFile()
                log.info("📝 [EnvFile] Created new .env file")
            }

            val lines = if (envFile.exists()) {
                envFile.readLines(StandardCharsets.UTF_8).toMutableList()
            } else {
                mutableListOf()
            }

            var updated = false
            val newLines = mutableListOf<String>()

            // Ищем существующую переменную и обновляем её
            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.startsWith("$key=")) {
                    // Нашли существующую переменную - обновляем
                    newLines.add("$key=$value")
                    updated = true
                    log.debug("📝 [EnvFile] Updated existing variable: $key")
                } else {
                    // Сохраняем остальные строки как есть
                    newLines.add(line)
                }
            }

            // Если переменная не найдена, добавляем в конец
            if (!updated) {
                // Добавляем пустую строку перед новой переменной, если файл не пустой
                if (newLines.isNotEmpty() && newLines.last().isNotBlank()) {
                    newLines.add("")
                }
                newLines.add("$key=$value")
                log.debug("📝 [EnvFile] Added new variable: $key")
            }

            // Записываем обновленный файл
            envFile.writeText(newLines.joinToString("\n"), StandardCharsets.UTF_8)
            log.info("✅ [EnvFile] Successfully updated .env file with $key")
            true
        } catch (e: Exception) {
            log.error("❌ [EnvFile] Failed to update .env file: ${e.message}", e)
            false
        }
    }

    /**
     * Обновляет несколько переменных одновременно
     *
     * @param variables Map с парами ключ-значение
     * @return true если успешно обновлено, false если ошибка
     */
    fun updateEnvVariables(variables: Map<String, String>): Boolean {
        return try {
            if (!envFile.exists()) {
                envFile.createNewFile()
                log.info("📝 [EnvFile] Created new .env file")
            }

            val lines = if (envFile.exists()) {
                envFile.readLines(StandardCharsets.UTF_8).toMutableList()
            } else {
                mutableListOf()
            }

            val updatedKeys = mutableSetOf<String>()
            val newLines = mutableListOf<String>()

            // Обновляем существующие переменные
            for (line in lines) {
                val trimmedLine = line.trim()
                var lineUpdated = false

                for ((key, value) in variables) {
                    if (trimmedLine.startsWith("$key=")) {
                        newLines.add("$key=$value")
                        updatedKeys.add(key)
                        lineUpdated = true
                        log.debug("📝 [EnvFile] Updated existing variable: $key")
                        break
                    }
                }

                if (!lineUpdated) {
                    newLines.add(line)
                }
            }

            // Добавляем новые переменные, которые не были найдены
            for ((key, value) in variables) {
                if (!updatedKeys.contains(key)) {
                    if (newLines.isNotEmpty() && newLines.last().isNotBlank()) {
                        newLines.add("")
                    }
                    newLines.add("$key=$value")
                    log.debug("📝 [EnvFile] Added new variable: $key")
                }
            }

            // Записываем обновленный файл
            envFile.writeText(newLines.joinToString("\n"), StandardCharsets.UTF_8)
            log.info("✅ [EnvFile] Successfully updated .env file with ${variables.size} variable(s)")
            true
        } catch (e: Exception) {
            log.error("❌ [EnvFile] Failed to update .env file: ${e.message}", e)
            false
        }
    }

    /**
     * Читает значение переменной из .env файла
     *
     * @param key Имя переменной
     * @return Значение переменной или null, если не найдено
     */
    fun readEnvVariable(key: String): String? {
        return try {
            if (!envFile.exists()) {
                return null
            }

            envFile.readLines(StandardCharsets.UTF_8)
                .firstOrNull { it.trim().startsWith("$key=") }
                ?.substringAfter("=", "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log.error("❌ [EnvFile] Failed to read .env file: ${e.message}", e)
            null
        }
    }
}






