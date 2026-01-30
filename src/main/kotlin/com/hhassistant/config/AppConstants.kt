package com.hhassistant.config

/**
 * Константы приложения для замены magic чисел и строк
 */
object AppConstants {
    // URL и пути
    object Urls {
        const val LOCALHOST_BASE = "http://localhost:8080"
        const val API_VACANCIES_BASE = "$LOCALHOST_BASE/api/vacancies"
        const val OAUTH_AUTHORIZE = "$LOCALHOST_BASE/oauth/authorize"
        
        fun vacancyMarkApplied(vacancyId: String) = "$API_VACANCIES_BASE/$vacancyId/mark-applied"
        fun vacancyMarkNotInterested(vacancyId: String) = "$API_VACANCIES_BASE/$vacancyId/mark-not-interested"
    }

    // Ограничения длины текста
    object TextLimits {
        const val TELEGRAM_DESCRIPTION_MAX_LENGTH = 2000
        const val TELEGRAM_MESSAGE_PREVIEW_LENGTH = 200
        const val VACANCY_DESCRIPTION_PREVIEW_LENGTH = 500
        const val LOG_MESSAGE_PREVIEW_LENGTH = 100
        const val LOG_ARGUMENT_PREVIEW_LENGTH = 100
        const val COVER_LETTER_DESCRIPTION_PREVIEW_LENGTH = 500
        const val ERROR_MESSAGE_MAX_LENGTH = 100
        const val PROBLEMATIC_BATCH_DETAILS_LINES = 100
        const val LOG_ANALYSIS_SUMMARY_WORDS = 200
        const val LOG_ANALYSIS_BRIEF_WORDS = 300
    }

    // Кэширование
    object Cache {
        const val VACANCY_DETAILS_MAX_SIZE = 1000
        const val SEARCH_CONFIG_MAX_SIZE = 100
        const val VACANCY_IDS_MAX_SIZE = 10
        const val VACANCY_LIST_MAX_SIZE = 500
        const val DEFAULT_MAX_SIZE = 500
    }

    // Валидация
    object Validation {
        const val RELEVANCE_SCORE_MIN = 0.0
        const val RELEVANCE_SCORE_MAX = 1.0
    }

    // Индексы и счетчики
    object Indices {
        const val SAMPLE_VACANCIES_COUNT = 3
        const val PROBLEMATIC_BATCHES_LIMIT = 3
        const val JSON_START_CHAR = '{'
        const val JSON_END_CHAR = '}'
    }

    // Форматирование
    object Formatting {
        const val PERCENTAGE_MULTIPLIER = 100
        const val PERCENTAGE_DECIMAL_PLACES = 2
    }

    // Логирование
    object Logging {
        const val LOG_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss"
        const val LOG_TIMESTAMP_LENGTH = 19
    }

    // Резюме
    object Resume {
        const val FAKE_RESUME_EXPERIENCE_YEARS = "От 3 до 6 лет"
        const val FAKE_RESUME_DAYS_AGO = 1L
    }

    // Очередь ретраев
    object RetryQueue {
        const val TOTAL_ATTEMPTS_MULTIPLIER = 2 // maxRetries * 2 для общего количества попыток
    }
}


