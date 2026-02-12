package com.hhassistant.service.telegram

import com.hhassistant.domain.entity.Vacancy
import com.hhassistant.domain.entity.VacancyStatus
import com.hhassistant.domain.model.VacancySource
import com.hhassistant.client.telegram.dto.ChannelMessage
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Парсер вакансий из сообщений Telegram каналов
 * Извлекает информацию о вакансиях из текста сообщений
 */
@Component
class TelegramVacancyParser {
    private val log = KotlinLogging.logger {}

    /**
     * Пытается распарсить вакансию из сообщения канала
     * @return Vacancy или null если сообщение не содержит вакансию
     */
    fun parseVacancy(message: ChannelMessage, channelUsername: String): Vacancy? {
        val text = message.text ?: message.caption ?: return null
        
        // Проверяем, похоже ли сообщение на вакансию
        if (!isLikelyVacancy(text)) {
            return null
        }

        return try {
            val vacancy = Vacancy(
                id = "tg_${channelUsername}_${message.messageId}",
                name = extractVacancyTitle(text) ?: extractFirstLine(text),
                employer = extractEmployer(text) ?: "Не указан",
                salary = extractSalary(text),
                area = extractLocation(text) ?: "Не указана",
                url = extractUrl(text, channelUsername, message.messageId) ?: "",
                description = cleanDescription(text),
                experience = extractExperience(text),
                publishedAt = LocalDateTime.ofInstant(Instant.ofEpochSecond(message.date), ZoneId.systemDefault()),
                status = VacancyStatus.QUEUED,
                source = VacancySource.TELEGRAM_CHANNEL,
                messageId = message.messageId.toString(),
                channelUsername = channelUsername,
            )
            
            log.debug("Parsed vacancy from channel $channelUsername: ${vacancy.name}")
            vacancy
        } catch (e: Exception) {
            log.error("Error parsing vacancy from message ${message.messageId}: ${e.message}", e)
            null
        }
    }

    /**
     * Проверяет, похоже ли сообщение на вакансию
     */
    private fun isLikelyVacancy(text: String): Boolean {
        val vacancyKeywords = listOf(
            "вакансия", "вакансии", "позиция",
            "искать", "работа", "робота", "work", "job",
            "ищу", "looking for", "hiring", "найм",
            "программист", "developer", "engineer", "senior", "middle", "junior",
            "backend"
        )
        
        val lowerText = text.lowercase()
        return vacancyKeywords.any { keyword -> keyword in lowerText }
    }

    /**
     * Извлекает заголовок вакансии
     */
    private fun extractVacancyTitle(text: String): String? {
        // Ищем заголовок в первой строке или после эмодзи/маркера
        val lines = text.lines().filter { it.isNotBlank() }
        
        // Проверяем первую строку
        if (lines.isNotEmpty()) {
            val firstLine = lines[0].trim()
                .replace(Regex("^[💼📋🔍📍\\[\\]]+\\s*"), "")
                .trim()
            
            if (firstLine.length in 5..200) {
                return firstLine
            }
        }
        
        // Ищем паттерн типа "Position: ..."
        val positionPattern = Regex("[:🔹]\\s*([A-Z][A-Za-z\\s]+(?:Developer|Engineer|Manager|Specialist|Lead))")
        val match = positionPattern.find(text)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        
        return null
    }

    /**
     * Извлекает название компании
     */
    private fun extractEmployer(text: String): String? {
        val patterns = listOf(
            Regex("компания[:\\s]+([A-Za-zА-Яа-я0-9\\s]+)", RegexOption.IGNORE_CASE),
            Regex("company[:\\s]+([A-Za-z]+)", RegexOption.IGNORE_CASE),
            Regex("🏢\\s*([A-Za-zА-Яа-я0-9\\s]+)"),
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val employerName = match.groupValues[1].trim()
                return employerName.substring(0, minOf(100, employerName.length))
            }
        }
        return null
    }

    /**
     * Извлекает зарплату
     */
    private fun extractSalary(text: String): String? {
        val patterns = listOf(
            Regex("\\$\\s*[\\d,]+\\s*[-–]?\\s*\\$?[\\d,]*"),
            Regex("[\\d,]+\\s*USD"),
            Regex("[\\d,]+\\s*€"),
            Regex("[\\d,]+\\s*грн"),
            Regex("[\\d,]+\\s*₴"),
            Regex("от\\s*[\\d,]+"),
            Regex("до\\s*[\\d,]+"),
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.value.trim().take(100)
            }
        }
        return null
    }

    /**
     * Извлекает локацию
     */
    private fun extractLocation(text: String): String? {
        val patterns = listOf(
            Regex("📍\\s*([A-Za-zА-Яа-я\\s]+)"),
            Regex("локация[:\\s]+([A-Za-zА-Яа-я\\s]+)", RegexOption.IGNORE_CASE),
            Regex("location[:\\s]+([A-Za-z]+)", RegexOption.IGNORE_CASE),
            Regex("remote|удаленно|офис|office|kyiv|kiev|москва|минск|київ", RegexOption.IGNORE_CASE),
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.value.take(50)
            }
        }
        return null
    }

    /**
     * Извлекает опыт работы
     */
    private fun extractExperience(text: String): String? {
        val patterns = listOf(
            Regex("(\\d+\\+?\\s*год|года|лет|years?)", RegexOption.IGNORE_CASE),
            Regex("senior|middle|junior|lead|intern", RegexOption.IGNORE_CASE),
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.value.take(50)
            }
        }
        return null
    }

    /**
     * Извлекает URL вакансии (если есть ссылка)
     */
    private fun extractUrl(text: String, channelUsername: String, messageId: Long): String? {
        // Ищем https:// или t.me/ ссылки
        val urlPattern = Regex("https?://[^\\s]+")
        val urlMatch = urlPattern.find(text)
        
        if (urlMatch != null) {
            return urlMatch.value
        }
        
        // Если нет прямой ссылки, возвращаем ссылку на сообщение
        return "https://t.me/$channelUsername/$messageId"
    }

    /**
     * Очищает описание для сохранения в БД
     */
    private fun cleanDescription(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")  // Убираем лишние пробелы
            .replace(Regex("[🔗✅❌💼📋🔍📍💰💡]"), "")  // Убираем эмодзи
            .trim()
            .take(5000)  // Ограничиваем длину
    }

    /**
     * Извлекает первую непустую строку
     */
    private fun extractFirstLine(text: String): String {
        return text.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(200) ?: "Без названия"
    }
}
