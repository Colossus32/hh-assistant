package com.hhassistant.web

import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.exception.HHAPIException
import com.hhassistant.service.EnvFileService
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Контроллер для тестирования токена и диагностики проблем
 */
@RestController
@RequestMapping("/api/token")
class TokenTestController(
    private val hhVacancyClient: HHVacancyClient,
    private val envFileService: EnvFileService,
    @Value("\${hh.api.access-token:}") private val accessToken: String,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Проверяет токен и пытается получить вакансии
     * GET /api/token/test
     */
    @GetMapping("/test")
    fun testToken(
        @RequestParam(required = false, defaultValue = "Java") keywords: String,
    ): ResponseEntity<Map<String, Any>> {
        log.info("🔍 [TokenTest] Testing token validity...")

        val tokenFromEnv = envFileService.readEnvVariable("HH_ACCESS_TOKEN")
        val tokenFromConfig = accessToken

        val response = mutableMapOf<String, Any>(
            "token_from_env" to (if (tokenFromEnv != null) "✅ Found (${tokenFromEnv.length} chars)" else "❌ Not found"),
            "token_from_config" to (if (tokenFromConfig.isNotBlank()) "✅ Found (${tokenFromConfig.length} chars)" else "❌ Not found"),
            "tokens_match" to (tokenFromEnv == tokenFromConfig),
        )

        if (tokenFromEnv == null && tokenFromConfig.isBlank()) {
            response["error"] = "No token found in .env file or configuration"
            response["message"] = "Please obtain a token via OAuth: http://localhost:8080/oauth/authorize"
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
        }

        // Пытаемся получить вакансии
        return try {
            log.info("🔍 [TokenTest] Attempting to fetch vacancies with keywords: '$keywords'")
            val searchConfig = SearchConfig(
                keywords = keywords,
                area = null,
                minSalary = null,
                maxSalary = null,
                experience = null,
                isActive = true,
            )

            val vacancies = runBlocking {
                hhVacancyClient.searchVacancies(searchConfig)
            }

            response["status"] = "✅ SUCCESS"
            response["message"] = "Token is valid and working!"
            response["vacancies_found"] = vacancies.size
            response["sample_vacancies"] = vacancies.take(3).map { mapOf(
                "id" to it.id,
                "name" to it.name,
                "employer" to it.employer?.name,
            ) }

            log.info("✅ [TokenTest] Token test successful: found ${vacancies.size} vacancies")
            ResponseEntity.ok(response)
        } catch (e: HHAPIException.UnauthorizedException) {
            log.error("❌ [TokenTest] Token test failed: ${e.message}", e)
            response["status"] = "❌ FAILED"
            response["error"] = "Token is invalid or expired"
            response["error_message"] = e.message ?: "Unknown error"
            response["message"] = "Token is not valid. Please obtain a new token via OAuth: http://localhost:8080/oauth/authorize"
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response)
        } catch (e: Exception) {
            log.error("❌ [TokenTest] Token test failed with unexpected error: ${e.message}", e)
            response["status"] = "❌ ERROR"
            response["error"] = "Unexpected error"
            response["error_message"] = e.message ?: "Unknown error"
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response)
        }
    }

    /**
     * Показывает информацию о текущем токене (без самого токена)
     * GET /api/token/info
     */
    @GetMapping("/info")
    fun tokenInfo(): ResponseEntity<Map<String, Any>> {
        val tokenFromEnv = envFileService.readEnvVariable("HH_ACCESS_TOKEN")
        val tokenFromConfig = accessToken

        val response = mapOf(
            "token_in_env" to (tokenFromEnv != null),
            "token_in_config" to tokenFromConfig.isNotBlank(),
            "tokens_match" to (tokenFromEnv == tokenFromConfig),
            "token_length_env" to (tokenFromEnv?.length ?: 0),
            "token_length_config" to tokenFromConfig.length,
            "token_prefix_env" to (tokenFromEnv?.take(10) ?: "N/A"),
            "token_prefix_config" to (if (tokenFromConfig.isNotBlank()) tokenFromConfig.take(10) else "N/A"),
            "message" to if (tokenFromEnv == null && tokenFromConfig.isBlank()) {
                "No token found. Get one via: http://localhost:8080/oauth/authorize"
            } else if (tokenFromEnv != tokenFromConfig) {
                "⚠️ Tokens don't match! Restart the application to load token from .env"
            } else {
                "✅ Token found in both .env and configuration"
            },
        )

        return ResponseEntity.ok(response)
    }
}

