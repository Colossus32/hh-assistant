package com.hhassistant.client.hh

import com.github.benmanes.caffeine.cache.Cache
import com.hhassistant.aspect.Loggable
import com.hhassistant.client.hh.dto.VacancyDto
import com.hhassistant.client.hh.dto.VacancySearchResponse
import com.hhassistant.config.VacancyServiceConfig
import com.hhassistant.domain.entity.SearchConfig
import com.hhassistant.exception.HHAPIException
import com.hhassistant.ratelimit.RateLimitService
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class HHVacancyClient(
    @Qualifier("hhWebClient") private val webClient: WebClient,
    @Value("\${hh.api.search.per-page}") private val perPage: Int,
    @Value("\${hh.api.search.default-page}") private val defaultPage: Int,
    private val rateLimitService: RateLimitService,
    @Qualifier("vacancyDetailsCache") private val vacancyDetailsCache: Cache<String, VacancyDto>,
    private val searchConfig: VacancyServiceConfig,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Максимальная глубина результатов согласно документации HH.ru API
     * per_page * page не может быть больше 2000
     */
    private val maxVacanciesDepth = 2000

    /**
     * Кэш для отслеживания последней обработанной страницы для каждого уникального SearchConfig.
     * Ключ: уникальный идентификатор конфигурации (keywords + area + minSalary)
     * Значение: последняя обработанная страница
     */
    private val lastProcessedPageCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Генерирует уникальный ключ для SearchConfig
     */
    private fun getConfigKey(config: SearchConfig): String {
        return "${config.keywords}|${config.area ?: "null"}|${config.minSalary ?: "null"}"
    }

    /**
     * Поиск вакансий с улучшенной пагинацией.
     * Определяет конец пагинации по пустой/неполной странице и автоматически перезапускается
     * с начала при обнаружении новых вакансий.
     *
     * Каждый уникальный SearchConfig (по keywords + area + minSalary) имеет свой независимый прогресс пагинации.
     *
     * @param config Конфигурация поиска
     * @param startFromPage Страница, с которой начинать поиск (по умолчанию используется сохраненный прогресс или 0)
     * @param isRestart Флаг, указывающий что это перезапуск (для предотвращения бесконечной рекурсии)
     * @return Список найденных вакансий
     */
    @Loggable
    suspend fun searchVacancies(config: SearchConfig, startFromPage: Int? = null, isRestart: Boolean = false): List<VacancyDto> {
        val experienceIds = searchConfig.experienceIds ?: listOf("between1And3", "between3And6")

        // Получаем уникальный ключ для этого SearchConfig
        val configKey = getConfigKey(config)

        // Определяем стартовую страницу: используем переданную, сохраненную или 0
        val actualStartPage = startFromPage ?: lastProcessedPageCache.getOrDefault(configKey, 0)

        log.debug("[HH.ru API] Searching vacancies: keywords='${config.keywords}', area=${config.area}, minSalary=${config.minSalary}, experience=$experienceIds, startFromPage=$actualStartPage (configKey=$configKey)")

        val allVacancies = mutableListOf<VacancyDto>()
        var currentPage = actualStartPage
        var hasMorePages = true
        var totalFound = 0
        var lastSuccessfulPage = actualStartPage

        while (hasMorePages && currentPage * perPage < maxVacanciesDepth) {
            try {
                rateLimitService.tryConsume()

                val pageResponse = fetchVacanciesPage(config, currentPage)

                // Сохраняем totalFound из первой страницы для логирования
                if (currentPage == actualStartPage) {
                    totalFound = pageResponse.found
                    log.info("[HH.ru API] Page $currentPage: ${pageResponse.items.size} vacancies, total found: $totalFound")
                }

                // Проверяем признаки конца пагинации
                when {
                    // Пустая страница - достигли конца
                    pageResponse.items.isEmpty() -> {
                        log.info("[HH.ru API] Empty page $currentPage detected - reached end of results")
                        hasMorePages = false

                        // Если начали не с 0 и это не перезапуск, значит были новые вакансии - начинаем сначала
                        if (currentPage > 0 && actualStartPage > 0 && !isRestart) {
                            log.info("[HH.ru API] Empty page detected at $currentPage (started from $actualStartPage) - new vacancies may have appeared, restarting from page 0")
                            // Сбрасываем кэш для этого конфига и перезапускаем с начала
                            lastProcessedPageCache.remove(configKey)
                            // Рекурсивно перезапускаем с начала
                            return searchVacancies(config, startFromPage = 0, isRestart = true)
                        } else if (currentPage == 0 && pageResponse.items.isEmpty()) {
                            log.warn("[HH.ru API] First page (0) is empty - no vacancies found for this search")
                            // Сбрасываем кэш, так как нет вакансий
                            lastProcessedPageCache.remove(configKey)
                        } else {
                            // Сохраняем последнюю успешную страницу (предыдущую)
                            lastProcessedPageCache[configKey] = lastSuccessfulPage
                        }
                    }

                    // Неполная страница - последняя страница
                    pageResponse.items.size < perPage -> {
                        log.info("[HH.ru API] Incomplete page $currentPage (${pageResponse.items.size} < $perPage) - last page detected")
                        allVacancies.addAll(pageResponse.items)
                        hasMorePages = false
                        // Сохраняем текущую страницу как последнюю обработанную
                        lastProcessedPageCache[configKey] = currentPage
                    }

                    // Обычная страница - продолжаем
                    else -> {
                        allVacancies.addAll(pageResponse.items)
                        lastSuccessfulPage = currentPage
                        currentPage++
                    }
                }

                log.trace("[HH.ru API] Page $currentPage: ${pageResponse.items.size} vacancies (total so far: ${allVacancies.size})")

                // Адаптивная задержка между запросами на основе доступных токенов rate limit
                if (hasMorePages) {
                    val adaptiveDelay = calculateAdaptiveDelay()
                    if (adaptiveDelay > 0) {
                        delay(adaptiveDelay)
                    }
                }
            } catch (e: HHAPIException.RateLimitException) {
                log.warn("[HH.ru API] Rate limit exceeded on page $currentPage, stopping pagination")
                break
            } catch (e: Exception) {
                log.warn("[HH.ru API] Error fetching page $currentPage: ${e.message}, continuing with next page")
                // При ошибке продолжаем со следующей страницы
                currentPage++
                if (currentPage * perPage >= maxVacanciesDepth) {
                    log.warn("[HH.ru API] Reached max depth limit ($maxVacanciesDepth), stopping pagination")
                    break
                }
            }
        }

        val lastPage = if (hasMorePages && currentPage > actualStartPage) currentPage - 1 else currentPage
        log.info("[HH.ru API] Total fetched: ${allVacancies.size} vacancies from pages $actualStartPage..$lastPage (total available: $totalFound, configKey=$configKey)")

        // Если дошли до конца без ошибок, сохраняем последнюю страницу
        if (!hasMorePages && allVacancies.isNotEmpty()) {
            lastProcessedPageCache[configKey] = lastPage
            log.debug("[HH.ru API] Saved last processed page $lastPage for configKey=$configKey")
        }

        return allVacancies
    }

    /**
     * Вычисляет адаптивную задержку между запросами на основе доступных токенов rate limit.
     * 
     * Логика:
     * - Если токенов нет (0) - ждем 500ms (половина секунды для пополнения при 2 req/s)
     * - Если мало токенов (1-2) - небольшая задержка 100ms
     * - Если достаточно токенов (3+) - минимальная задержка 10ms
     * 
     * Это позволяет оптимизировать скорость загрузки, не превышая rate limit.
     * 
     * @return Задержка в миллисекундах
     */
    private fun calculateAdaptiveDelay(): Long {
        val availableTokens = rateLimitService.getAvailableTokens()
        
        return when {
            availableTokens == 0L -> {
                // Нет токенов - ждем пополнения (500ms = половина секунды для 2 req/s)
                log.trace("[HH.ru API] No tokens available, using delay 500ms")
                500
            }
            availableTokens <= 2 -> {
                // Мало токенов - небольшая задержка для безопасности
                log.trace("[HH.ru API] Low tokens ($availableTokens), using delay 100ms")
                100
            }
            else -> {
                // Достаточно токенов - минимальная задержка
                log.trace("[HH.ru API] Sufficient tokens ($availableTokens), using delay 10ms")
                10
            }
        }
    }

    /**
     * Запрашивает одну страницу вакансий
     */
    private suspend fun fetchVacanciesPage(config: SearchConfig, page: Int): VacancySearchResponse {
        return try {
            val experienceIds = searchConfig.experienceIds ?: listOf("between1And3", "between3And6")
            val requestSpec = webClient.get()
                .uri { builder ->
                    builder.path("/vacancies")
                        .queryParam("text", config.keywords)
                        .apply {
                            config.area?.let { queryParam("area", it) }
                            config.minSalary?.let { queryParam("salary", it) }
                            // Фильтруем по опыту из конфигурации
                            experienceIds.forEach { experienceId ->
                                queryParam("experience", experienceId)
                            }
                            queryParam("per_page", perPage)
                            queryParam("page", page)
                        }
                        .build()
                }

            val response = requestSpec
                .retrieve()
                .bodyToMono<VacancySearchResponse>()
                .awaitSingle()

            response
        } catch (e: WebClientResponseException) {
            log.error("❌ [HH.ru API] Error searching vacancies on page $page: ${e.message}", e)
            val exception = mapToHHAPIException(e, "Failed to search vacancies on page $page")

            // Если это ошибка авторизации, логируем детально
            if (exception is HHAPIException.UnauthorizedException) {
                log.error("🚨 [HH.ru API] UNAUTHORIZED: Access token expired or invalid!")
                log.error("🚨 [HH.ru API] Status code: ${e.statusCode}, Response: ${e.responseBodyAsString}")
            }

            throw exception
        } catch (e: Exception) {
            log.error("Unexpected error searching vacancies on page $page: ${e.message}", e)
            throw HHAPIException.ConnectionException("Failed to connect to HH.ru API: ${e.message}", e)
        }
    }

    @Loggable
    suspend fun getVacancyDetails(id: String): VacancyDto {
        // Check cache before API request
        vacancyDetailsCache.getIfPresent(id)?.let { cached ->
            log.trace("[HH.ru API] Using cached vacancy details for ID: $id")
            return cached
        }

        rateLimitService.tryConsume()

        log.debug("[HH.ru API] Fetching vacancy details for ID: $id (cache miss)")

        return try {
            val vacancy = webClient.get()
                .uri("/vacancies/$id")
                .retrieve()
                .bodyToMono<VacancyDto>()
                .awaitSingle()

            vacancyDetailsCache.put(id, vacancy)
            log.debug("[HH.ru API] Fetched and cached vacancy: ${vacancy.name} (ID: $id)")

            vacancy
        } catch (e: WebClientResponseException) {
            log.error("[HH.ru API] Error getting vacancy details: ${e.message}", e)
            throw mapToHHAPIException(e, "Failed to get vacancy details for id: $id")
        } catch (e: Exception) {
            log.error("[HH.ru API] Unexpected error getting vacancy details: ${e.message}", e)
            throw HHAPIException.ConnectionException("Failed to connect to HH.ru API: ${e.message}", e)
        }
    }

    private fun mapToHHAPIException(e: WebClientResponseException, defaultMessage: String): HHAPIException {
        return when (e.statusCode) {
            HttpStatus.UNAUTHORIZED -> HHAPIException.UnauthorizedException(
                "Unauthorized access to HH.ru API. Check your access token.",
                e,
            )
            HttpStatus.FORBIDDEN -> HHAPIException.UnauthorizedException(
                "Forbidden (403): Access token may be invalid, expired, or lacks required permissions. " +
                    "Response: ${e.responseBodyAsString}",
                e,
            )
            HttpStatus.NOT_FOUND -> HHAPIException.NotFoundException(
                "Resource not found in HH.ru API: ${e.message}",
                e,
            )
            HttpStatus.TOO_MANY_REQUESTS -> HHAPIException.RateLimitException(
                "Rate limit exceeded for HH.ru API. Please wait before retrying.",
                e,
            )
            HttpStatus.INTERNAL_SERVER_ERROR,
            HttpStatus.BAD_GATEWAY,
            HttpStatus.SERVICE_UNAVAILABLE,
            -> HHAPIException.ConnectionException(
                "Server error from HH.ru API: ${e.statusCode}",
                e,
            )
            else -> HHAPIException.APIException(
                "$defaultMessage: ${e.statusCode} - ${e.message}",
                e,
            )
        }
    }
}