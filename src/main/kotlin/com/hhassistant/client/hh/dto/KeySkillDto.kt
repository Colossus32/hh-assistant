package com.hhassistant.client.hh.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * DTO для ключевого навыка из API HH.ru.
 * 
 * Структура навыка в ответе API:
 * {
 *   "name": "Kotlin",
 *   // возможно есть другие поля: id, etc.
 * }
 */
data class KeySkillDto(
    /**
     * Название навыка
     */
    val name: String,
    
    /**
     * ID навыка (если есть в API)
     */
    val id: String? = null,
)





