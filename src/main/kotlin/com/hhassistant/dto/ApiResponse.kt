package com.hhassistant.dto

/**
 * DTO для простого ответа API (success/error)
 */
data class ApiResponse(
    val success: Boolean,
    val message: String? = null,
)
