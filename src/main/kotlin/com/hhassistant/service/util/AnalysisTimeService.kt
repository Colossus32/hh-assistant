package com.hhassistant.service.util

import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * Сервис для хранения среднего времени обработки вакансий через Ollama.
 * Использует in-memory хранение с расчетом скользящего среднего.
 */
@Service
class AnalysisTimeService {
    private val log = KotlinLogging.logger {}

    // Атомарная ссылка для thread-safe доступа к среднему времени
    private val averageTimeMs = AtomicReference<Double?>(null)

    /**
     * Обновляет среднее время обработки.
     * Если это первое значение - сохраняет его как есть.
     * Для последующих значений вычисляет среднее между текущим средним и новым значением.
     *
     * @param durationMs Время обработки в миллисекундах
     */
    fun updateAverageTime(durationMs: Long) {
        averageTimeMs.updateAndGet { currentAverage ->
            if (currentAverage == null) {
                // Первое значение - сохраняем как есть
                log.debug("📊 [AnalysisTime] First analysis time recorded: ${durationMs}ms")
                durationMs.toDouble()
            } else {
                // Вычисляем среднее между текущим средним и новым значением
                val newAverage = (currentAverage + durationMs) / 2.0
                log.debug("📊 [AnalysisTime] Updated average time: ${String.format("%.2f", currentAverage)}ms -> ${String.format("%.2f", newAverage)}ms (new: ${durationMs}ms)")
                newAverage
            }
        }
    }

    /**
     * Получает текущее среднее время обработки в миллисекундах.
     *
     * @return Среднее время в миллисекундах или null, если еще не было ни одного анализа
     */
    fun getAverageTimeMs(): Double? {
        return averageTimeMs.get()
    }

    /**
     * Сбрасывает среднее время (для тестирования или сброса статистики)
     */
    fun reset() {
        averageTimeMs.set(null)
        log.debug("📊 [AnalysisTime] Average time reset")
    }
}
