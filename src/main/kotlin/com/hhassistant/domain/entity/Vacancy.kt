package com.hhassistant.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDateTime

@Entity
@Table(name = "vacancies")
data class Vacancy(
    @Id
    @Column(name = "id", length = 50)
    val id: String,

    @Column(name = "name", length = 500, nullable = false)
    val name: String,

    @Column(name = "employer", length = 255, nullable = false)
    val employer: String,

    @Column(name = "salary", length = 100)
    val salary: String?,

    @Column(name = "area", length = 255)
    val area: String,

    @Column(name = "url", length = 1000, nullable = false)
    val url: String,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String?,

    @Column(name = "experience", length = 100)
    val experience: String?,

    @Column(name = "published_at")
    val publishedAt: LocalDateTime?,

    @Column(name = "fetched_at", nullable = false)
    val fetchedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "sent_to_telegram_at")
    val sentToTelegramAt: LocalDateTime? = null,

    @Column(name = "skills_extracted_at")
    val skillsExtractedAt: LocalDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    val status: VacancyStatus = VacancyStatus.NEW,

    /**
     * Версия записи для optimistic locking.
     * Автоматически увеличивается при каждом обновлении записи.
     * Предотвращает lost updates при параллельном обновлении.
     */
    @Version
    @Column(name = "version", nullable = false)
    val version: Long = 0,
) {
    /**
     * Rich Domain Model: бизнес-логика внутри entity
     * Проверяет, является ли вакансия новой (еще не обработанной)
     */
    fun isNew(): Boolean = status == VacancyStatus.NEW

    /**
     * Проверяет, была ли вакансия отправлена пользователю
     */
    fun isSentToUser(): Boolean = status == VacancyStatus.SENT_TO_USER && sentToTelegramAt != null

    /**
     * Проверяет, была ли вакансия пропущена (не релевантна)
     */
    fun isSkipped(): Boolean = status == VacancyStatus.SKIPPED

    /**
     * Проверяет, была ли вакансия отмечена как неинтересная
     */
    fun isNotInterested(): Boolean = status == VacancyStatus.NOT_INTERESTED

    /**
     * Проверяет, была ли вакансия помечена как неподходящая (не релевантна по результатам LLM анализа)
     */
    fun isNotSuitable(): Boolean = status == VacancyStatus.NOT_SUITABLE

    /**
     * Проверяет, находится ли вакансия в архиве (недоступна на HH.ru)
     */
    fun isInArchive(): Boolean = status == VacancyStatus.IN_ARCHIVE

    /**
     * Проверяет, можно ли обрабатывать вакансию (не помечена как неинтересная, неподходящая, отклоненная или в архиве)
     */
    fun canBeProcessed(): Boolean = status != VacancyStatus.NOT_INTERESTED &&
        status != VacancyStatus.NOT_SUITABLE &&
        status != VacancyStatus.IN_ARCHIVE &&
        status != VacancyStatus.REJECTED_BY_VALIDATOR

    /**
     * Проверяет, находится ли вакансия в очереди на обработку
     */
    fun isQueued(): Boolean = status == VacancyStatus.QUEUED

    /**
     * Создает копию вакансии с новым статусом (immutability)
     */
    fun withStatus(newStatus: VacancyStatus): Vacancy = copy(status = newStatus)

    /**
     * Создает копию вакансии с отметкой времени отправки в Telegram
     */
    fun withSentToTelegramAt(sentAt: LocalDateTime): Vacancy = copy(
        status = VacancyStatus.SENT_TO_USER,
        sentToTelegramAt = sentAt,
    )

    /**
     * Проверяет, были ли извлечены навыки для вакансии
     */
    fun hasSkillsExtracted(): Boolean = skillsExtractedAt != null

    /**
     * Создает копию вакансии с отметкой времени извлечения навыков
     */
    fun withSkillsExtractedAt(extractedAt: LocalDateTime): Vacancy = copy(
        skillsExtractedAt = extractedAt,
    )
}

enum class VacancyStatus {
    NEW, // Новая вакансия
    QUEUED, // В очереди на обработку (добавлена в очередь, но еще не обработана)
    ANALYZED, // Проанализирована LLM
    SENT_TO_USER, // Отправлена в Telegram
    SKIPPED, // Не удалось обработать (технические ошибки, можно восстановить)
    NOT_SUITABLE, // Не подходит по результатам LLM анализа (финальный статус, не обрабатывать повторно)
    IN_ARCHIVE, // Вакансия недоступна на HH.ru (404, удалена или в архиве)
    APPLIED, // Откликнулся на вакансию
    NOT_INTERESTED, // Неинтересная вакансия (не удалять, но не показывать повторно)
    REJECTED_BY_VALIDATOR, // Отклонена валидатором (содержит бан-слова или не соответствует критериям, финальный статус)
}
