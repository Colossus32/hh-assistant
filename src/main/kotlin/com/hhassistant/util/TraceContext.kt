package com.hhassistant.util

import org.slf4j.MDC
import java.util.UUID

/**
 * Утилита для управления trace ID (correlation ID) для трассировки вакансий через приложение.
 * Использует MDC (Mapped Diagnostic Context) для автоматического добавления trace ID во все логи.
 *
 * Пример использования:
 * ```
 * TraceContext.withTraceId("vacancy-123") {
 *     // Все логи в этом блоке будут содержать traceId=vacancy-123
 *     log.info("Processing vacancy")
 * }
 * ```
 */
object TraceContext {
    /**
     * Ключ для trace ID в MDC
     */
    private const val TRACE_ID_KEY = "traceId"

    /**
     * Ключ для vacancy ID в MDC (для удобства поиска)
     */
    private const val VACANCY_ID_KEY = "vacancyId"

    /**
     * Выполняет блок кода с установленным trace ID.
     * Trace ID автоматически добавляется во все логи через MDC.
     *
     * @param traceId Trace ID для трассировки (обычно ID вакансии)
     * @param vacancyId Опциональный ID вакансии для дополнительного контекста
     * @param block Блок кода для выполнения
     * @return Результат выполнения блока
     */
    fun <T> withTraceId(traceId: String, vacancyId: String? = null, block: () -> T): T {
        val previousTraceId = MDC.get(TRACE_ID_KEY)
        val previousVacancyId = MDC.get(VACANCY_ID_KEY)

        try {
            MDC.put(TRACE_ID_KEY, traceId)
            if (vacancyId != null) {
                MDC.put(VACANCY_ID_KEY, vacancyId)
            }
            return block()
        } finally {
            // Восстанавливаем предыдущие значения
            if (previousTraceId != null) {
                MDC.put(TRACE_ID_KEY, previousTraceId)
            } else {
                MDC.remove(TRACE_ID_KEY)
            }

            if (previousVacancyId != null) {
                MDC.put(VACANCY_ID_KEY, previousVacancyId)
            } else if (vacancyId != null) {
                MDC.remove(VACANCY_ID_KEY)
            }
        }
    }

    /**
     * Выполняет suspend блок кода с установленным trace ID.
     * Используется для корутин.
     */
    suspend fun <T> withTraceIdSuspend(traceId: String, vacancyId: String? = null, block: suspend () -> T): T {
        val previousTraceId = MDC.get(TRACE_ID_KEY)
        val previousVacancyId = MDC.get(VACANCY_ID_KEY)

        try {
            MDC.put(TRACE_ID_KEY, traceId)
            if (vacancyId != null) {
                MDC.put(VACANCY_ID_KEY, vacancyId)
            }
            return block()
        } finally {
            // Восстанавливаем предыдущие значения
            if (previousTraceId != null) {
                MDC.put(TRACE_ID_KEY, previousTraceId)
            } else {
                MDC.remove(TRACE_ID_KEY)
            }

            if (previousVacancyId != null) {
                MDC.put(VACANCY_ID_KEY, previousVacancyId)
            } else if (vacancyId != null) {
                MDC.remove(VACANCY_ID_KEY)
            }
        }
    }

    /**
     * Генерирует новый trace ID на основе ID вакансии
     */
    fun generateTraceId(vacancyId: String): String = "vacancy-$vacancyId"

    /**
     * Генерирует уникальный trace ID
     */
    fun generateUniqueTraceId(): String = UUID.randomUUID().toString().take(8)

    /**
     * Получает текущий trace ID из MDC
     */
    fun getCurrentTraceId(): String? = MDC.get(TRACE_ID_KEY)

    /**
     * Получает текущий vacancy ID из MDC
     */
    fun getCurrentVacancyId(): String? = MDC.get(VACANCY_ID_KEY)

    /**
     * Очищает trace context
     */
    fun clear() {
        MDC.remove(TRACE_ID_KEY)
        MDC.remove(VACANCY_ID_KEY)
    }
}
