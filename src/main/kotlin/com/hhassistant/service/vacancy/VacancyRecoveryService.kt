package com.hhassistant.service.vacancy

import com.hhassistant.domain.entity.VacancyStatus
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
 * Сервис для восстановления skipped вакансий
 * Просто переводит все skipped вакансии в NEW для повторной обработки
 */
@Service
class VacancyRecoveryService(
    private val vacancyRepository: VacancyRepository,
    private val vacancyService: VacancyService,
    private val vacancyStatusService: VacancyStatusService,
    private val vacancyAnalysisService: VacancyAnalysisService,
    @Value("\${app.vacancy-recovery.batch-size:100}") private val batchSize: Int,
    @Value("\${app.vacancy-recovery.retry-window-hours:48}") private val retryWindowHours: Int,
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
     * Просто переводит все skipped вакансии в NEW без проверок
     * @return Pair<recoveredCount, 0> (второй параметр для совместимости)
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

        try {
            val vacanciesToRecover = vacancyService.getSkippedVacanciesForRetry(
                limit = batchSize,
                retryWindowHours = retryWindowHours,
            )

            if (vacanciesToRecover.isEmpty()) {
                log.debug("[VacancyRecovery] No skipped vacancies to recover (within $retryWindowHours hour window)")
                return Pair(0, 0)
            }

            log.info(
                "[VacancyRecovery] Found ${vacanciesToRecover.size} SKIPPED vacancies to recover, " +
                    "converting to NEW...",
            )

            var recoveredCount = 0
            var skippedCount = 0

            vacanciesToRecover.forEach { vacancy ->
                try {
                    // ВАЖНО: Проверяем, не была ли вакансия уже проанализирована
                    // Если анализ существует и вакансия нерелевантна - не сбрасываем статус,
                    // чтобы избежать бесконечного цикла обработки нерелевантных вакансий
                    val existingAnalysis = vacancyAnalysisService.findByVacancyId(vacancy.id)
                    if (existingAnalysis != null && !existingAnalysis.isRelevant) {
                        log.debug(
                            "[VacancyRecovery] Vacancy ${vacancy.id} already analyzed and not relevant " +
                                "(score: ${String.format("%.2f", existingAnalysis.relevanceScore * 100)}%), " +
                                "skipping recovery to avoid infinite loop",
                        )
                        skippedCount++
                        return@forEach
                    }

                    // Переводим в NEW для повторной обработки
                    val oldStatus = vacancy.status
                    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.NEW))
                    log.debug(
                        "[VacancyRecovery] Reset vacancy ${vacancy.id} status from $oldStatus to NEW",
                    )
                    recoveredCount++
                } catch (e: Exception) {
                    log.error(
                        "[VacancyRecovery] Failed to process vacancy ${vacancy.id}: ${e.message}",
                        e,
                    )
                }
            }

            log.info(
                "[VacancyRecovery] Completed - Reset $recoveredCount vacancies to NEW status " +
                    "($skippedCount skipped as already analyzed and not relevant)",
            )

            return Pair(recoveredCount, 0)
        } catch (e: Exception) {
            log.error("[VacancyRecovery] Error recovering skipped vacancies: ${e.message}", e)
            return Pair(0, 0)
        }
    }

    /**
     * Проверяет, есть ли вакансии для восстановления
     */
    fun hasVacanciesToRecover(): Boolean {
        val skippedCount = vacancyRepository.countSkippedVacancies()
        return skippedCount > 0
    }
}
