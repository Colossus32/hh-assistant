package com.hhassistant.config

import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.env.ConfigurableEnvironment
import java.io.File

/**
 * Автоматически определяет правильный URL для Ollama в зависимости от окружения.
 * Если приложение запущено в Docker контейнере и OLLAMA_BASE_URL не задан явно,
 * автоматически устанавливает http://host.docker.internal:11434
 * Если приложение запущено локально, использует http://localhost:11434
 */
class OllamaUrlAutoConfigurer : ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private val log = KotlinLogging.logger {}

    override fun onApplicationEvent(event: ApplicationEnvironmentPreparedEvent) {
        val environment = event.environment as ConfigurableEnvironment

        // Проверяем текущее значение OLLAMA_BASE_URL
        val currentUrl = environment.getProperty("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }

        // Проверяем, находимся ли мы в Docker контейнере
        val isInDocker = detectDockerContainer()

        if (isInDocker) {
            val dockerUrl = "http://host.docker.internal:11434"

            // Если URL не задан или содержит localhost/127.0.0.1, заменяем на host.docker.internal
            if (currentUrl == null ||
                currentUrl.contains("localhost") ||
                currentUrl.contains("127.0.0.1")
            ) {
                log.info("🐳 [OllamaUrlAutoConfigurer] Обнаружен Docker контейнер")
                if (currentUrl != null) {
                    log.warn("   ⚠️ Обнаружен localhost в OLLAMA_BASE_URL: $currentUrl")
                    log.warn("   Это не будет работать в Docker контейнере!")
                }
                log.info("   ✅ Автоматически устанавливаю OLLAMA_BASE_URL=$dockerUrl")

                // Устанавливаем системное свойство, которое будет использовано Spring
                System.setProperty("OLLAMA_BASE_URL", dockerUrl)

                // Также устанавливаем в environment для немедленного использования
                environment.systemProperties["OLLAMA_BASE_URL"] = dockerUrl
            } else {
                log.info("🔧 [OllamaUrlAutoConfigurer] OLLAMA_BASE_URL задан явно: $currentUrl")
                log.info("   (Docker контейнер обнаружен, но URL уже настроен правильно)")
            }
        } else {
            val localUrl = "http://localhost:11434"
            if (currentUrl == null) {
                log.info("💻 [OllamaUrlAutoConfigurer] Локальное окружение")
                log.info("   Используется OLLAMA_BASE_URL=$localUrl (по умолчанию)")
            } else {
                log.info("🔧 [OllamaUrlAutoConfigurer] OLLAMA_BASE_URL задан: $currentUrl")
            }
        }
    }

    /**
     * Определяет, запущено ли приложение в Docker контейнере.
     * Проверяет несколько признаков:
     * 1. Наличие файла /.dockerenv
     * 2. Содержимое /proc/1/cgroup (Linux)
     * 3. HOSTNAME содержит docker-подобные значения
     */
    private fun detectDockerContainer(): Boolean {
        // Признак 1: файл /.dockerenv существует (самый надежный способ)
        if (File("/.dockerenv").exists()) {
            log.debug("   Обнаружен /.dockerenv - контейнер Docker")
            return true
        }

        // Признак 2: проверка /proc/1/cgroup (Linux)
        val cgroupFile = File("/proc/1/cgroup")
        if (cgroupFile.exists()) {
            try {
                val cgroupContent = cgroupFile.readText()
                if (cgroupContent.contains("docker", ignoreCase = true) ||
                    cgroupContent.contains("containerd", ignoreCase = true) ||
                    cgroupContent.contains("kubepods", ignoreCase = true)
                ) {
                    log.debug("   Обнаружен Docker в /proc/1/cgroup")
                    return true
                }
            } catch (e: Exception) {
                log.debug("   Не удалось прочитать /proc/1/cgroup: ${e.message}")
            }
        }

        // Признак 3: HOSTNAME содержит docker-подобные значения
        val hostname = System.getenv("HOSTNAME") ?: System.getProperty("host.name", "")
        if (hostname.isNotEmpty()) {
            // Docker часто использует хеш в имени контейнера (12 символов)
            // Или имена типа "container-name" или "service-name"
            if (hostname.matches(Regex("^[a-f0-9]{12}$")) || // Docker hash
                hostname.contains("-") && hostname.length > 10
            ) { // Docker compose service names
                log.debug("   HOSTNAME указывает на Docker: $hostname")
                return true
            }
        }

        // Признак 4: проверка переменных окружения Docker
        if (System.getenv("container") != null) {
            log.debug("   Обнаружена переменная окружения 'container'")
            return true
        }

        return false
    }
}
