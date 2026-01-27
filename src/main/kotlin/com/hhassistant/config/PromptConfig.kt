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
     */
    var analysisSystem: String = """
        Ты - эксперт по подбору персонала. Твоя задача - проанализировать вакансию и резюме кандидата, 
        определить релевантность вакансии для кандидата и предоставить структурированный ответ в формате JSON.
        
        Формат ответа:
        {
            "is_relevant": true/false,
            "relevance_score": 0.0-1.0,
            "reasoning": "Обоснование решения на русском языке",
            "matched_skills": ["навык1", "навык2", ...]
        }
        
        Критерии релевантности:
        - Совпадение ключевых навыков (вес: 40%)
        - Соответствие опыта работы требованиям (вес: 30%)
        - Соответствие желаемой позиции (вес: 20%)
        - Соответствие зарплатных ожиданий (вес: 10%)
        
        Отвечай ТОЛЬКО валидным JSON, без дополнительных комментариев.
    """.trimIndent()

    /**
     * Шаблон промпта для анализа вакансии.
     * Переменные: {vacancyName}, {employer}, {salary}, {area}, {experience}, {description}, {skills}, {desiredPosition}, {desiredSalary}, {summary}, {rawText}
     */
    var analysisTemplate: String = """
        ВАКАНСИЯ:
        Название: {vacancyName}
        Работодатель: {employer}
        Зарплата: {salary}
        Регион: {area}
        Требуемый опыт: {experience}
        
        Описание вакансии:
        {description}
        
        ---
        
        РЕЗЮМЕ КАНДИДАТА:
        {resumeContent}
        
        Проанализируй релевантность вакансии для кандидата и верни JSON ответ.
    """.trimIndent()

    /**
     * Системный промпт для генерации сопроводительного письма.
     */
    var coverLetterSystem: String = """
        Ты - эксперт по написанию сопроводительных писем. Напиши профессиональное, 
        краткое (до 200 слов) сопроводительное письмо на русском языке для отклика на вакансию.
        
        Письмо должно:
        - Быть персонализированным под конкретную вакансию
        - Подчеркивать релевантный опыт и навыки
        - Быть профессиональным, но не шаблонным
        - Показывать заинтересованность в позиции
        
        Начни с обращения к работодателю, затем кратко представься и объясни, 
        почему ты подходишь для этой позиции.
    """.trimIndent()

    /**
     * Шаблон промпта для генерации сопроводительного письма.
     * Переменные: {vacancyName}, {employer}, {description}, {matchedSkills}, {summary}
     */
    var coverLetterTemplate: String = """
        Напиши сопроводительное письмо для следующей вакансии:
        
        Вакансия: {vacancyName}
        Работодатель: {employer}
        Описание: {description}
        
        Релевантные навыки кандидата: {matchedSkills}
        {summary}
    """.trimIndent()
}
