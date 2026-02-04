package com.hhassistant.service.vacancy

import com.hhassistant.client.hh.HHVacancyClient
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.exception.HHAPIException
import com.hhassistant.repository.VacancyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Единый сервис для восстановления skipped вакансий
 * Унифицированная логика recovery с проверкой бан-слов и существующих анализов
 */
@Service
class VacancyRecoveryService(
    private val vacancyRepository: VacancyRepository,
    private val vacancyService: VacancyService,
    private val vacancyStatusService: VacancyStatusService,
    private val vacancyAnalysisService: VacancyAnalysisService,
    private val vacancyContentValidator: VacancyContentValidator,
    private val skillExtractionService: com.hhassistant.service.skill.SkillExtractionService,
    private val metricsService: com.hhassistant.metrics.MetricsService,
    private val hhVacancyClient: HHVacancyClient,
    @Value("\${app.vacancy-recovery.batch-size:100}") private val batchSize: Int,
    @Value("\${app.vacancy-recovery.retry-window-hours:48}") private val retryWindowHours: Int,
    @Value("\${app.vacancy-recovery.process-old-vacancies:true}") private val processOldVacancies: Boolean,
) {
    private val log = KotlinLogging.logger {}

    // Scope для асинхронной обработки
    private val recoveryScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob(),
    )

    /**
     * Восстанавливает skipped вакансии: переводит их в статус NEW для повторной обработки
     * Выполняется асинхронно, не блокируя вызывающий поток
     *
     * @param onComplete Опциональный callback, вызываемый после завершения восстановления
     */
    fun recoverFailedAndSkippedVacancies(onComplete: ((validCount: Int, deletedCount: Int) -> Unit)? = null) {
        recoveryScope.launch {
            try {
                val result = recoverFailedAndSkippedVacanciesInternal()
                onComplete?.invoke(result.first, result.second)
            } catch (e: Exception) {
                log.error("[VacancyRecovery] Error in recovery: ${e.message}", e)
                onComplete?.invoke(0, 0)
            }
        }
    }

    /**
     * Внутренний метод для восстановления вакансий
     * Выполняет полную проверку: существующий анализ, бан-слова, затем восстановление
     * @return Pair<recoveredCount, deletedCount>
     */
    private suspend fun recoverFailedAndSkippedVacanciesInternal(): Pair<Int, Int> {
        val skippedCount = withContext(Dispatchers.IO) {
            vacancyRepository.countSkippedVacancies()
        }

        if (skippedCount == 0L) {
            return Pair(0, 0)
        }

        log.info(
            "[VacancyRecovery] Starting recovery of skipped vacancies (Skipped: $skippedCount)",
        )

        // Обновляем метрику попыток recovery
        metricsService.incrementRecoveryAttempts()

        try {
            val vacanciesToRecover = vacancyService.getSkippedVacanciesForRetry(
                limit = batchSize,
                retryWindowHours = retryWindowHours,
            )

            if (vacanciesToRecover.isEmpty()) {
                log.debug("[VacancyRecovery] No skipped vacancies to recover (within $retryWindowHours hour window)")

                // Если новых вакансий нет и включена обработка старых - обрабатываем старые вакансии
                if (processOldVacancies) {
                    val oldVacanciesResult = processOldSkippedVacancies()
                    // Возвращаем recoveredCount и deletedCount (включая archivedCount как удаленные)
                    return Pair(
                        oldVacanciesResult.first,
                        oldVacanciesResult.second + oldVacanciesResult.third,
                    )
                }

                return Pair(0, 0)
            }

            log.info(
                "[VacancyRecovery] Found ${vacanciesToRecover.size} SKIPPED vacancies to recover, " +
                    "checking exclusion rules and existing analysis...",
            )

            var recoveredCount = 0
            var deletedCount = 0
            var skippedRecoveryCount = 0

            vacanciesToRecover.forEach { vacancy ->
                try {
                    // Шаг 1: Проверяем, не была ли вакансия уже проанализирована
                    // Если анализ существует и вакансия нерелевантна - не сбрасываем статус,
                    // чтобы избежать бесконечного цикла обработки нерелевантных вакансий
                    val existingAnalysis = vacancyAnalysisService.findByVacancyId(vacancy.id)
                    if (existingAnalysis != null && !existingAnalysis.isRelevant) {
                        log.debug(
                            "[VacancyRecovery] Vacancy ${vacancy.id} already analyzed and not relevant " +
                                "(score: ${String.format("%.2f", existingAnalysis.relevanceScore * 100)}%), " +
                                "skipping recovery to avoid infinite loop",
                        )
                        skippedRecoveryCount++
                        metricsService.incrementRecoverySkipped()
                        return@forEach
                    }

                    // Шаг 2: Проверяем на бан-слова перед повторной обработкой
                    val validationResult = vacancyContentValidator.validate(vacancy)
                    if (!validationResult.isValid) {
                        log.warn(
                            "[VacancyRecovery] Vacancy ${vacancy.id} ('${vacancy.name}') " +
                                "contains exclusion rules: ${validationResult.rejectionReason}, " +
                                "deleting from database",
                        )

                        // Удаляем вакансию из БД, так как она содержит бан-слова
                        try {
                            skillExtractionService.deleteVacancyAndSkills(vacancy.id)
                            log.info(
                                "[VacancyRecovery] Deleted vacancy ${vacancy.id} from database due to exclusion rules",
                            )
                            deletedCount++
                            metricsService.incrementRecoveryDeleted()
                        } catch (e: Exception) {
                            log.error("[VacancyRecovery] Failed to delete vacancy ${vacancy.id}: ${e.message}", e)
                        }
                        return@forEach
                    }

                    // Шаг 3: Вакансия прошла проверку, сбрасываем статус на NEW для повторной обработки
                    val oldStatus = vacancy.status
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.NEW))
                    log.debug(
                        "[VacancyRecovery] Reset vacancy ${vacancy.id} status from $oldStatus to NEW",
                    )
                    recoveredCount++
                    metricsService.incrementRecoveryRecovered()
                } catch (e: Exception) {
                    log.error(
                        "[VacancyRecovery] Failed to process vacancy ${vacancy.id}: ${e.message}",
                        e,
                    )
                }
            }

            log.info(
                "[VacancyRecovery] Completed - Reset $recoveredCount vacancies to NEW status, " +
                    "deleted $deletedCount vacancies with exclusion rules, " +
                    "$skippedRecoveryCount skipped as already analyzed and not relevant",
            )

            return Pair(recoveredCount, deletedCount)
        } catch (e: Exception) {
            log.error("[VacancyRecovery] Error recovering skipped vacancies: ${e.message}", e)
            return Pair(0, 0)
        }
    }

    /**
     * Обрабатывает старые SKIPPED вакансии, которые вышли за пределы окна времени.
     * Для старых вакансий применяется финальная обработка:
     * - Проверка на бан-слова → удаление
     * - Проверка существующего анализа → перевод в NOT_SUITABLE (если нерелевантна) или восстановление (если релевантна)
     * - Проверка URL → перевод в IN_ARCHIVE (если 404) или восстановление (если доступна)
     *
     * @return Triple<recoveredCount, deletedCount, archivedCount>
     */
    private suspend fun processOldSkippedVacancies(): Triple<Int, Int, Int> {
        val oldVacancies = vacancyService.getOldSkippedVacancies(
            limit = batchSize,
            retryWindowHours = retryWindowHours,
        )

        if (oldVacancies.isEmpty()) {
            log.debug("[VacancyRecovery] No old skipped vacancies to process")
            return Triple(0, 0, 0)
        }

        log.info(
            "[VacancyRecovery] Found ${oldVacancies.size} old SKIPPED vacancies (outside $retryWindowHours hour window), " +
                "processing for final resolution...",
        )

        var recoveredCount = 0
        var deletedCount = 0
        var archivedCount = 0
        var notSuitableCount = 0

        oldVacancies.forEach { vacancy ->
            try {
                // Шаг 1: Проверяем на бан-слова
                val validationResult = vacancyContentValidator.validate(vacancy)
                if (!validationResult.isValid) {
                    log.warn(
                        "[VacancyRecovery] Old vacancy ${vacancy.id} ('${vacancy.name}') " +
                            "contains exclusion rules: ${validationResult.rejectionReason}, deleting",
                    )
                    try {
                        skillExtractionService.deleteVacancyAndSkills(vacancy.id)
                        log.info(
                            "[VacancyRecovery] Deleted old vacancy ${vacancy.id} from database due to exclusion rules",
                        )
                        deletedCount++
                        metricsService.incrementRecoveryDeleted()
                    } catch (e: Exception) {
                        log.error("[VacancyRecovery] Failed to delete old vacancy ${vacancy.id}: ${e.message}", e)
                    }
                    return@forEach
                }

                // Шаг 2: Проверяем существующий анализ
                val existingAnalysis = vacancyAnalysisService.findByVacancyId(vacancy.id)
                if (existingAnalysis != null) {
                    if (!existingAnalysis.isRelevant) {
                        // Вакансия уже проанализирована и нерелевантна - переводим в терминальный статус
                        log.info(
                            "[VacancyRecovery] Old vacancy ${vacancy.id} already analyzed and not relevant " +
                                "(score: ${String.format("%.2f", existingAnalysis.relevanceScore * 100)}%), " +
                                "marking as NOT_SUITABLE",
                        )
                        vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.NOT_SUITABLE))
                        notSuitableCount++
                        return@forEach
                    } else {
                        // Вакансия релевантна - восстанавливаем
                        log.info(
                            "[VacancyRecovery] Old vacancy ${vacancy.id} already analyzed and relevant " +
                                "(score: ${String.format("%.2f", existingAnalysis.relevanceScore * 100)}%), " +
                                "recovering to NEW",
                        )
                        vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.NEW))
                        recoveredCount++
                        metricsService.incrementRecoveryRecovered()
                        return@forEach
                    }
                }

                // Шаг 3: Нет анализа - проверяем URL
                val urlCheckResult = withContext(Dispatchers.IO) {
                    try {
                        hhVacancyClient.getVacancyDetails(vacancy.id)
                        // Вакансия существует и доступна
                        true
                    } catch (e: HHAPIException.NotFoundException) {
                        // Вакансия не найдена (404) - помечаем как IN_ARCHIVE
                        log.warn(
                            "[VacancyRecovery] Old vacancy ${vacancy.id} ('${vacancy.name}') " +
                                "not found on HH.ru (404), marking as IN_ARCHIVE",
                        )
                        false
                    } catch (e: HHAPIException.RateLimitException) {
                        // Rate limit - пропускаем эту вакансию, обработаем позже
                        log.debug(
                            "[VacancyRecovery] Rate limit while checking old vacancy ${vacancy.id}, " +
                                "skipping for now",
                        )
                        true // Считаем URL валидным, чтобы не удалять из-за rate limit
                    } catch (e: Exception) {
                        // Другие ошибки - логируем, но считаем URL валидным
                        log.warn(
                            "[VacancyRecovery] Error checking old vacancy ${vacancy.id} URL: ${e.message}, " +
                                "assuming URL is valid and recovering",
                        )
                        true
                    }
                }

                if (!urlCheckResult) {
                    // URL неактуален (404) - помечаем как IN_ARCHIVE
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.IN_ARCHIVE))
                    archivedCount++
                    log.info("[VacancyRecovery] Marked old vacancy ${vacancy.id} as IN_ARCHIVE due to 404")
                } else {
                    // URL актуален - восстанавливаем
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.NEW))
                    recoveredCount++
                    metricsService.incrementRecoveryRecovered()
                    log.info("[VacancyRecovery] Recovered old vacancy ${vacancy.id} to NEW status")
                }
            } catch (e: Exception) {
                log.error(
                    "[VacancyRecovery] Failed to process old vacancy ${vacancy.id}: ${e.message}",
                    e,
                )
            }
        }

        log.info(
            "[VacancyRecovery] Old vacancies processing completed - " +
                "Recovered: $recoveredCount, Deleted: $deletedCount, " +
                "Archived: $archivedCount, Not Suitable: $notSuitableCount",
        )

        return Triple(recoveredCount, deletedCount, archivedCount)
    }

    /**
     * Проверяет, есть ли вакансии для восстановления
     */
    fun hasVacanciesToRecover(): Boolean {
        val skippedCount = vacancyRepository.countSkippedVacancies()
        return skippedCount > 0
    }
}
