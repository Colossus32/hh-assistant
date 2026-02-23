package com.hhassistant.web.controller

import com.hhassistant.integration.hh.HHVacancyClient
import mu.KotlinLogging
import org.springframework.web.bind.annotation.*

/**
 * Контроллер для управления пагинацией поиска вакансий
 */
@RestController
@RequestMapping("/api/pagination")
class PaginationController(
    private val hhVacancyClient: HHVacancyClient,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Информация для сброса прогресса
     */
    data class PaginationResetInfo(
        val configKey: String,
        val message: String,
        val success: Boolean,
    )

    /**
     * Информация о текущей пагинации
     */
    data class PaginationInfo(
        val message: String,
        val timestamp: String,
    )

    /**
     * Сбрасывает прогресс пагинации для всех конфигураций поиска
     * После сброса следующий фетч вакансий начнется с первой страницы
     *
     * @return Список сброшенных конфигураций
     */
    @PostMapping("/reset-all")
    fun resetAllPagination(): Map<String, Any> {
        log.warn("[PaginationController] Manual pagination reset requested - all configs will be reset")

        // Информация о сбросе для всех конфигов
        val resetResults = mutableListOf<PaginationResetInfo>()

        // Сбрасываем прогресс для основных конфигов (Java, Kotlin)
        val defaultConfigs = listOf("Java|null|null", "Kotlin|null|null")

        for (configKey in defaultConfigs) {
            try {
                // Используем рефлексию для доступа к приватному методу deleteProgressFromDatabase
                // Это временное решение, в будущем можно сделать метод публичным
                val method = hhVacancyClient.javaClass.getDeclaredMethod("deleteProgressFromDatabase", String::class.java)
                method.isAccessible = true
                method.invoke(hhVacancyClient, configKey)

                resetResults.add(
                    PaginationResetInfo(
                        configKey = configKey,
                        message = "Pagination progress reset successfully",
                        success = true,
                    ),
                )
                log.info("[PaginationController] Reset pagination for config: $configKey")
            } catch (e: Exception) {
                log.error("[PaginationController] Failed to reset pagination for config $configKey: ${e.message}", e)
                resetResults.add(
                    PaginationResetInfo(
                        configKey = configKey,
                        message = "Failed to reset: ${e.message}",
                        success = false,
                    ),
                )
            }
        }

        val successfulResets = resetResults.count { it.success }
        val failedResets = resetResults.count { !it.success }

        return mapOf(
            "timestamp" to java.time.LocalDateTime.now().toString(),
            "message" to "Pagination reset completed",
            "total" to resetResults.size,
            "successful" to successfulResets,
            "failed" to failedResets,
            "results" to resetResults,
            "nextAction" to "The next vacancy fetch will start from page 0",
        )
    }

    /**
     * Сбрасывает прогресс пагинации для конкретной конфигурации
     *
     * @param configKey Ключ конфигурации (например: "Java|null|null")
     * @return Результат сброса
     */
    @PostMapping("/reset/{configKey}")
    fun resetPagination(@PathVariable configKey: String): Map<String, Any> {
        log.warn("[PaginationController] Manual pagination reset requested for config: $configKey")

        return try {
            // Используем рефлексию для доступа к приватному методу deleteProgressFromDatabase
            val method = hhVacancyClient.javaClass.getDeclaredMethod("deleteProgressFromDatabase", String::class.java)
            method.isAccessible = true
            method.invoke(hhVacancyClient, configKey)

            mapOf(
                "success" to true,
                "configKey" to configKey,
                "message" to "Pagination progress reset successfully",
                "timestamp" to java.time.LocalDateTime.now().toString(),
                "nextAction" to "The next vacancy fetch will start from page 0",
            )
        } catch (e: Exception) {
            log.error("[PaginationController] Failed to reset pagination for config $configKey: ${e.message}", e)
            mapOf(
                "success" to false,
                "configKey" to configKey,
                "message" to "Failed to reset: ${e.message}",
                "timestamp" to java.time.LocalDateTime.now().toString(),
            )
        }
    }

    /**
     * Показывает информацию о текущей пагинации
     *
     * @return Информация о пагинации
     */
    @GetMapping("/info")
    fun getPaginationInfo(): Map<String, Any> {
        return mapOf(
            "message" to "Pagination tracking is active for all search configurations",
            "resetEndpoints" to listOf(
                "POST /api/pagination/reset-all - Reset all configurations",
                "POST /api/pagination/reset/{configKey} - Reset specific configuration",
            ),
            "notes" to listOf(
                "Pagination reset will cause the next vacancy fetch to start from page 0",
                "Progress is automatically reset when reaching the end of pagination",
                "Cooldown period (${getCooldownHours()}h) prevents infinite loops",
            ),
            "timestamp" to java.time.LocalDateTime.now().toString(),
        )
    }

    /**
     * Получает значение cooldown часов из конфигурации
     */
    private fun getCooldownHours(): Long {
        return try {
            val field = hhVacancyClient.javaClass.getDeclaredField("restartCooldownHours")
            field.isAccessible = true
            field.getLong(hhVacancyClient)
        } catch (e: Exception) {
            1L // значение по умолчанию
        }
    }
}


