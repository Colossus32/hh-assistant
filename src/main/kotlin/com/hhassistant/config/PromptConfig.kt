package com.hhassistant.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация промптов для LLM.
 * Промпты можно настраивать через application.yml.
 */
@Component
@ConfigurationProperties(prefix = "app.prompts")
class PromptConfig {
    /**
     * Системный промпт для анализа вакансий.
     * Оптимизирован для возврата JSON с навыками и процентом совпадения.
     * Сокращен для ускорения генерации.
     */
    var analysisSystem: String = """
        Извлеки навыки из вакансии и определи процент совпадения с резюме.
        
        JSON:
        {
            "skills": ["Kotlin", "Spring Boot", "PostgreSQL"],
            "relevance_score": 0.85
        }
        
        skills: технологии из вакансии (3-20). relevance_score: 0.0-1.0 (навыки 40%, опыт 30%, позиция 20%, зарплата 10%).
        Только JSON.
    """.trimIndent()

    /**
     * Шаблон промпта для анализа вакансии.
     * Оптимизирован - убраны избыточные поля для ускорения генерации.
     * Переменные: {vacancyName}, {salary}, {experience}, {description}, {resumeContent}
     */
    var analysisTemplate: String = """
        {vacancyName} | {salary} | {experience}
        
        {description}
        
        Резюме: {resumeContent}
        
        Извлеки навыки и определи совпадение. JSON: {"skills": [...], "relevance_score": 0.0-1.0}
    """.trimIndent()

    /**
     * Системный промпт для извлечения навыков из описания вакансии.
     */
    var skillExtractionSystem: String = """
        Ты - эксперт по анализу вакансий. Твоя задача - извлечь из описания вакансии список технических навыков, технологий, инструментов и требований.
        
        Верни результат в формате JSON:
        {
            "skills": ["Kotlin", "Spring Boot", "PostgreSQL", "Docker", "Kubernetes"]
        }
        
        Требования:
        - Извлекай только конкретные технологии и навыки
        - Игнорируй общие фразы типа "опыт работы", "коммуникабельность", "ответственность"
        - Приводи названия к стандартному виду (например, "Kotlin" вместо "Kotlin Developer")
        - Если навык упоминается несколько раз - включай только один раз
        - Минимум 3 навыка, максимум 20
        - Отвечай ТОЛЬКО валидным JSON, без дополнительных комментариев
    """.trimIndent()

    /**
     * Шаблон промпта для извлечения навыков из описания вакансии.
     * Переменные: {vacancyName}, {description}, {employer}
     */
    var skillExtractionTemplate: String = """
        Извлеки технические навыки и технологии из следующей вакансии:
        
        Название: {vacancyName}
        Работодатель: {employer}
        
        Описание:
        {description}
        
        Верни список навыков в формате JSON.
    """.trimIndent()
}
