package com.hhassistant.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Конфигурация для VacancyService
 * Группирует параметры поиска вакансий для уменьшения количества параметров конструктора
 * 
 * Spring Boot автоматически конвертирует kebab-case (keywords-rotation) в camelCase (keywordsRotation)
 */
@ConfigurationProperties(prefix = "app.search", ignoreUnknownFields = false)
data class VacancyServiceConfig(
    /**
     * Ротация ключевых слов для поиска
     * Маппится из YAML: keywords-rotation -> keywordsRotation
     */
    val keywordsRotation: List<String>? = null,
    
    /**
     * Одно ключевое слово (для обратной совместимости)
     */
    val keywords: String? = null,
    
    /**
     * Регион поиска
     */
    val area: String? = null,
    
    /**
     * Минимальная зарплата
     */
    val minSalary: Int? = null,
    
    /**
     * Требуемый опыт работы
     */
    val experience: String? = null,
)

