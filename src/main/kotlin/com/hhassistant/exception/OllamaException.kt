package com.hhassistant.exception

/**
 * Базовое исключение для ошибок, связанных с Ollama LLM.
 */
sealed class OllamaException(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    /**
     * Ошибка при анализе вакансии через LLM.
     */
    class AnalysisException(message: String, cause: Throwable? = null) : OllamaException(message, cause)

    /**
     * Ошибка подключения к Ollama сервису.
     */
    class ConnectionException(message: String, cause: Throwable? = null) : OllamaException(message, cause)

    /**
     * Ошибка парсинга ответа от LLM.
     */
    class ParsingException(message: String, cause: Throwable? = null) : OllamaException(message, cause)

    /**
     * Ошибка генерации сопроводительного письма.
     */
    class CoverLetterGenerationException(message: String, cause: Throwable? = null) : OllamaException(message, cause)
}

