# Флоу изменения статусов вакансий

Документ описывает, как меняется статус вакансии в процессе работы приложения.

## Статусы вакансий

```kotlin
enum class VacancyStatus {
    NEW,              // Новая вакансия
    QUEUED,           // В очереди на обработку (добавлена в очередь, но еще не обработана)
    ANALYZED,         // Проанализирована LLM
    SENT_TO_USER,     // Отправлена в Telegram
    SKIPPED,          // Не удалось обработать (технические ошибки, можно восстановить)
    NOT_SUITABLE,     // Не подходит по результатам LLM анализа (финальный статус, не обрабатывать повторно)
    IN_ARCHIVE,        // Вакансия недоступна на HH.ru (404, удалена или в архиве)
    APPLIED,          // Откликнулся на вакансию
    NOT_INTERESTED,   // Неинтересная вакансия (не удалять, но не показывать повторно)
}
```

## Основной флоу обработки вакансии

### 1. Получение вакансий из HH.ru API

**Сервис:** `VacancyFetchService.fetchAndSaveNewVacancies()`

**Процесс:**
- Получение вакансий из HH.ru API по конфигурациям поиска
- Фильтрация по опыту (> 6 лет исключаются)
- Фильтрация по бан-словам (exclusion keywords)
- Проверка на дубликаты (по ID из кэша)

**Статус:** `QUEUED` (устанавливается при создании)

**Код:**
```kotlin
// VacancyFetchService.kt:249
.map { it.copy(status = VacancyStatus.QUEUED) }
```

**Действия:**
- Сохранение в БД со статусом `QUEUED`
- Добавление в очередь обработки через `VacancyProcessingQueueService.enqueueBatch()`

---

### 2. Добавление в очередь обработки

**Сервис:** `VacancyProcessingQueueService.enqueue()`

**Процесс:**
- Проверка на дубликаты (уже обрабатывается или в очереди)
- Проверка существующего анализа (если есть - обновление статуса)
- Если статус не `QUEUED`, обновление на `QUEUED`
- Добавление в приоритетную очередь (приоритет по дате публикации)

**Статус:** `QUEUED` (остается или устанавливается)

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:224-226
if (vacancy.status != VacancyStatus.QUEUED) {
    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.QUEUED))
}
```

---

### 3. Обработка из очереди

**Сервис:** `VacancyProcessingQueueService.processQueueItem()`

**Процесс:**
- Проверка Circuit Breaker (если OPEN - статус → `SKIPPED`)
- **Проверка URL (самая первая)** - доступна ли вакансия на HH.ru (404 → `IN_ARCHIVE`)
- Валидация контента (бан-слова → удаление из БД)
- Анализ вакансии через LLM (Ollama)
- Обновление статуса на основе результата анализа
- **Удаление из очереди** после обработки (всегда)

**Возможные переходы:**
- `QUEUED` → `IN_ARCHIVE` (если вакансия недоступна на HH.ru - 404)
- `QUEUED` → `ANALYZED` (если релевантна)
- `QUEUED` → `NOT_SUITABLE` (если не релевантна по результатам LLM анализа)
- `QUEUED` → `SKIPPED` (если техническая ошибка обработки)
- `QUEUED` → **удаление из БД** (если отклонена валидатором - бан-слова)

**Удаление из очереди:**
После обработки вакансия всегда удаляется из очереди:
```kotlin
// VacancyProcessingQueueService.kt:475-481
processingVacancies.remove(item.vacancyId)
queuedVacancies.remove(item.vacancyId)
queue.remove(item)
metricsService.setQueueSize(queue.size)
```

**Удаление из базы данных:**
Вакансия удаляется из БД только в следующих случаях:
1. **Отклонена валидатором** (содержит бан-слова) - `analysis == null`

**Помечается как IN_ARCHIVE:**
- Вакансия недоступна на HH.ru (404) - помечается как `IN_ARCHIVE` вместо удаления

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:497-510
val analysis = vacancyAnalysisService.analyzeVacancy(vacancy)

// Если анализ вернул null - вакансия была отклонена валидатором и удалена из БД
if (analysis == null) {
    log.info("Vacancy ${vacancy.id} was rejected by validator and deleted from database")
    return
}

// Обновляем статус вакансии
val newStatus = if (analysis.isRelevant) {
    VacancyStatus.ANALYZED
} else {
    VacancyStatus.NOT_SUITABLE  // Нерелевантные вакансии остаются в БД со статусом NOT_SUITABLE
}
vacancyStatusService.updateVacancyStatus(vacancy.withStatus(newStatus))
```

**Важно:**
- **Проверка URL выполняется первой** (до валидации контента) для экономии ресурсов
- При 404 вакансия помечается как `IN_ARCHIVE` (не удаляется из БД)
- Нерелевантные вакансии (низкий relevance score) **НЕ удаляются** из базы данных
- Они остаются в БД со статусом `NOT_SUITABLE` (финальный статус, не обрабатываются повторно)
- Статус `SKIPPED` используется только для технических ошибок (можно восстановить через retry)
- Удаляются только вакансии с бан-словами

---

### 4. Отправка в Telegram (для релевантных вакансий)

**Сервис:** `VacancyNotificationService.sendVacancyToTelegram()`

**Процесс:**
- Отправка уведомления в Telegram с информацией о вакансии
- Обновление статуса и времени отправки

**Статус:** `ANALYZED` → `SENT_TO_USER`

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:526-529
val sentSuccessfully = vacancyNotificationService.sendVacancyToTelegram(vacancy, analysis)
if (sentSuccessfully) {
    val sentAt = LocalDateTime.now()
    vacancyStatusService.updateVacancyStatus(vacancy.withSentToTelegramAt(sentAt))
}
```

**Метод `withSentToTelegramAt()` автоматически устанавливает статус `SENT_TO_USER`:**
```kotlin
// Vacancy.kt:94-97
fun withSentToTelegramAt(sentAt: LocalDateTime): Vacancy = copy(
    status = VacancyStatus.SENT_TO_USER,
    sentToTelegramAt = sentAt,
)
```

---

### 5. Действия пользователя

**Контроллер:** `VacancyManagementController`

#### 5.1. Отметка как "Откликнулся"

**Endpoint:** `POST /api/vacancies/{id}/mark-applied`

**Статус:** Любой → `APPLIED`

**Код:**
```kotlin
// VacancyManagementController.kt:74-76
fun markAsApplied(@PathVariable id: String) {
    return updateVacancyStatus(id, VacancyStatus.APPLIED, "откликнулся")
}
```

#### 5.2. Отметка как "Неинтересная"

**Endpoint:** `POST /api/vacancies/{id}/mark-not-interested`

**Статус:** Любой → `NOT_INTERESTED`

**Код:**
```kotlin
// VacancyManagementController.kt:96-98
fun markAsNotInterested(@PathVariable id: String) {
    return updateVacancyStatus(id, VacancyStatus.NOT_INTERESTED, "неинтересная")
}
```

---

## Восстановление пропущенных вакансий

### Единый сервис восстановления

**Сервис:** `VacancyRecoveryService` (унифицированная логика recovery)

**Триггеры запуска:**
1. **По расписанию:** `VacancySchedulerService.retrySkippedVacancies()` - каждые 5 минут (если Circuit Breaker закрыт)
2. **Автоматически:** `OllamaMonitoringService.tryRecoveryFailedAndSkippedVacancies()` - при мониторинге состояния Ollama

**Процесс восстановления:**
1. Поиск `SKIPPED` вакансий в окне времени (по умолчанию 48 часов)
2. Проверка существующего анализа:
   - Если анализ есть и `isRelevant = false` → пропускается (избегает бесконечного цикла)
3. Проверка на бан-слова:
   - Если содержит бан-слова → удаление из БД
   - Если прошла проверку → сброс статуса на `NEW` для повторной обработки

**Статус:** `SKIPPED` → `NEW` (или удаление из БД при бан-словах)

**Метрики:**
- `vacancies.recovery.attempts` - количество попыток recovery
- `vacancies.recovery.recovered` - количество успешно восстановленных вакансий
- `vacancies.recovery.deleted` - количество удаленных вакансий (бан-слова)
- `vacancies.recovery.skipped` - количество пропущенных вакансий (уже проанализированы и нерелевантны)

**Код:**
```kotlin
// VacancyRecoveryService.kt - единая точка входа для recovery
vacancyRecoveryService.recoverFailedAndSkippedVacancies { recoveredCount, deletedCount ->
    // Callback с результатами recovery
}

// VacancySchedulerService.kt - использует унифицированный сервис
@Scheduled(cron = "0 */5 * * * *")
fun retrySkippedVacancies() {
    vacancyRecoveryService.recoverFailedAndSkippedVacancies { ... }
}
```

**Важно:**
- `NOT_SUITABLE` и `IN_ARCHIVE` не восстанавливаются (финальные статусы)
- Только `SKIPPED` вакансии могут быть восстановлены
- Окно времени ограничивает recovery только недавними вакансиями (48 часов по умолчанию)

---

## Обработка ошибок

### Circuit Breaker OPEN

**Когда:** При перегрузке LLM (Ollama)

**Действие:** Статус → `SKIPPED`

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:390-451
if (circuitBreakerState == "OPEN") {
    // Ждем завершения активных запросов или помечаем как SKIPPED
    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
}
```

### Ошибка анализа

**Когда:** Ошибка при анализе через LLM

**Действие:** Статус → `SKIPPED`

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:560-599
catch (e: OllamaException) {
    vacancyStatusService.updateVacancyStatus(vacancy.withStatus(VacancyStatus.SKIPPED))
}
```

### Отклонение валидатором

**Когда:** Вакансия содержит бан-слова или не прошла валидацию

**Действие:** 
- Удаление из БД (возврат `null` из анализа)
- Удаление из очереди (автоматически при завершении обработки)

**Код:**
```kotlin
// VacancyAnalysisService.kt:137-158
val contentValidation = vacancyContentValidator.validate(vacancy)
if (!contentValidation.isValid) {
    // Удаляем вакансию из БД, так как она содержит бан-слова
    skillExtractionService.deleteVacancyAndSkills(vacancy.id)
    return null  // Возвращаем null, чтобы показать, что вакансия была удалена
}

// VacancyProcessingQueueService.kt:497-510
val analysis = vacancyAnalysisService.analyzeVacancy(vacancy)
if (analysis == null) {
    log.info("Vacancy ${vacancy.id} was rejected by validator and deleted from database")
    return  // Вакансия удалена, обработка завершена, удаление из очереди произойдет автоматически
}
```

### Невалидный URL (404)

**Когда:** Вакансия не найдена на HH.ru (404)

**Действие:**
- Удаление из БД
- Удаление из очереди (автоматически при завершении обработки)

**Код:**
```kotlin
// VacancyAnalysisService.kt:161-193
try {
    hhVacancyClient.getVacancyDetails(vacancy.id)
} catch (e: HHAPIException.NotFoundException) {
    // Вакансия не найдена (404) - удаляем из БД
    skillExtractionService.deleteVacancyAndSkills(vacancy.id)
    return null
}
```

---

## Диаграмма флоу

```
┌─────────────────────────────────────────────────────────────────┐
│                    HH.ru API                                     │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  VacancyFetchService.fetchAndSaveNewVacancies()                 │
│  - Получение вакансий                                           │
│  - Фильтрация (опыт, бан-слова)                                 │
│  - Статус: QUEUED                                               │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  VacancyProcessingQueueService.enqueue()                        │
│  - Добавление в очередь                                         │
│  - Статус: QUEUED (остается)                                    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  VacancyProcessingQueueService.processQueueItem()               │
│  - Проверка URL (404 → IN_ARCHIVE)                              │
│  - Валидация контента (бан-слова → удаление)                    │
│  - Анализ через LLM (Ollama)                                    │
│  - Статус: ANALYZED (релевантна) или NOT_SUITABLE (не релевантна)│
└──────────────────────────┬──────────────────────────────────────┘
                           │
                    ┌──────┴──────┐
                    │             │
                    ▼             ▼
        ┌──────────────────┐  ┌──────────────────┐
        │   ANALYZED       │  │   NOT_SUITABLE    │
        │   (релевантна)   │  │   (не релевантна) │
        └────────┬──────────┘  └──────────────────┘
                 │
                 │
                 │
                 │
                 │
                 │
                 ▼
                 │
                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  VacancyNotificationService.sendVacancyToTelegram()            │
│  - Отправка в Telegram                                          │
│  - Статус: SENT_TO_USER                                         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                    ┌──────┴──────┐
                    │             │
                    ▼             ▼
        ┌──────────────────┐  ┌──────────────────┐
        │   APPLIED        │  │   NOT_INTERESTED │
        │   (откликнулся)  │  │   (неинтересная) │
        └──────────────────┘  └──────────────────┘
```

**Примечания к диаграмме:**
- После обработки в `processQueueItem()` вакансия **всегда удаляется из очереди** (из `queue`, `processingVacancies`, `queuedVacancies`)
- **Проверка URL выполняется первой** (до валидации контента) для оптимизации
- Вакансия **удаляется из базы данных** только если:
  - Отклонена валидатором (содержит бан-слова)
- При 404 вакансия **помечается как `IN_ARCHIVE`** (не удаляется из БД)
- Нерелевантные вакансии (низкий relevance score) **остаются в БД** со статусом `NOT_SUITABLE` (финальный статус)
- Статус `SKIPPED` используется только для технических ошибок и может быть восстановлен через retry
- **Connection pooling** настроен для оптимизации HTTP запросов (переиспользование соединений)

---

## Ключевые сервисы

### VacancyStatusService
Центральный сервис для обновления статусов вакансий.

**Методы:**
- `updateVacancyStatus(updatedVacancy: Vacancy)` - обновление статуса
- `updateVacancyStatusById(vacancyId: String, newStatus: VacancyStatus)` - обновление по ID

**Особенности:**
- Инвалидация кэша при обновлении
- Логирование изменений статуса

### VacancyProcessingQueueService
Очередь обработки вакансий с приоритетом.

**Особенности:**
- Приоритетная очередь (по дате публикации)
- Ограничение параллелизма (semaphore)
- Обработка Circuit Breaker
- Автоматическое восстановление при перезапуске

### VacancySchedulerService
Планировщик задач для обработки вакансий.

**Задачи:**
- `checkNewVacancies()` - получение новых вакансий (каждые 15 минут)
- `processQueuedVacancies()` - обработка QUEUED вакансий из БД (каждые 10 минут)
- `retrySkippedVacancies()` - восстановление SKIPPED вакансий (каждые 5 минут, использует `VacancyRecoveryService`)

### VacancyRecoveryService
Единый сервис для восстановления пропущенных вакансий.

**Особенности:**
- Унифицированная логика recovery (проверка анализа, бан-слов, восстановление)
- Используется как по расписанию, так и автоматически
- Метрики для отслеживания эффективности recovery
- Защита от бесконечного цикла (не восстанавливает уже проанализированные нерелевантные вакансии)

---

## Особые случаи

### При перезапуске приложения

1. Загрузка `QUEUED` вакансий из БД в очередь
2. Проверка существующих анализов и обновление статусов
3. Продолжение обработки с места остановки

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:110-141
@EventListener(ApplicationReadyEvent::class)
fun loadPendingVacanciesOnStartup() {
    val queuedVacancies = vacancyRepository.findByStatus(VacancyStatus.QUEUED)
    // Добавление в очередь и запуск обработки
}
```

### При остановке приложения

Если есть активные запросы к LLM:
- Вакансии в обработке помечаются как `SKIPPED`
- При следующем запуске они будут восстановлены через `retrySkippedVacancies()`

**Код:**
```kotlin
// VacancyProcessingQueueService.kt:724-791
@PreDestroy
fun shutdown() {
    if (activeRequests > 0) {
        markProcessingVacanciesAsSkipped()
    }
}
```

---

## Резюме переходов статусов

| Из статуса | В статус | Условие/Триггер |
|------------|----------|-----------------|
| - | `QUEUED` | Создание вакансии из HH.ru API |
| `NEW` | `QUEUED` | Добавление в очередь обработки |
| `QUEUED` | `IN_ARCHIVE` | Вакансия недоступна на HH.ru (404) |
| `QUEUED` | `ANALYZED` | Успешный анализ, вакансия релевантна |
| `QUEUED` | `NOT_SUITABLE` | Анализ показал нерелевантность (финальный статус) |
| `QUEUED` | `SKIPPED` | Техническая ошибка обработки (можно восстановить) |
| `ANALYZED` | `SENT_TO_USER` | Успешная отправка в Telegram |
| `SENT_TO_USER` | `APPLIED` | Пользователь отметил как "откликнулся" |
| `SENT_TO_USER` | `NOT_INTERESTED` | Пользователь отметил как "неинтересная" |
| `SKIPPED` | `NEW` | Восстановление через retry (через 48 часов) |
| Любой | `SKIPPED` | Ошибка обработки или Circuit Breaker OPEN |

---

## Файлы для изучения

- `src/main/kotlin/com/hhassistant/domain/entity/Vacancy.kt` - Entity и enum статусов
- `src/main/kotlin/com/hhassistant/service/vacancy/VacancyStatusService.kt` - Сервис управления статусами
- `src/main/kotlin/com/hhassistant/service/vacancy/VacancyFetchService.kt` - Получение вакансий из API
- `src/main/kotlin/com/hhassistant/service/vacancy/VacancyProcessingQueueService.kt` - Очередь обработки
- `src/main/kotlin/com/hhassistant/service/vacancy/VacancySchedulerService.kt` - Планировщик задач
- `src/main/kotlin/com/hhassistant/web/VacancyManagementController.kt` - API для управления статусами

