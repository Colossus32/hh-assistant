package com.hhassistant.service

import com.hhassistant.config.VacancyServiceConfig
import com.hhassistant.domain.entity.SearchConfig
import org.springframework.stereotype.Component

/**
 * Фабрика для создания SearchConfig из различных источников
 * Устраняет дублирование кода создания SearchConfig
 */
@Component
class SearchConfigFactory {

    /**
     * Создает SearchConfig из конфигурации YAML
     */
    fun createFromYamlConfig(
        keywords: String,
        config: VacancyServiceConfig,
    ): SearchConfig {
        return SearchConfig(
            keywords = keywords,
            area = config.area?.takeIf { it.isNotBlank() },
            minSalary = config.minSalary,
            maxSalary = null,
            experience = config.experience?.takeIf { it.isNotBlank() },
            isActive = true,
        )
    }
}
