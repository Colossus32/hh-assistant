# Анализ проблемы: Блокировка команд Telegram во время анализа вакансий

## Проблема

При анализе вакансий команды от Telegram бота не обрабатываются. Пользователь не может взаимодействовать с ботом, пока идет анализ.

## Причина проблемы

### 1. Использование `runBlocking` в `@Scheduled` методах

Оба сервиса используют `runBlocking`, который **блокирует поток выполнения**:

#### `TelegramPollingService.pollUpdates()`
```kotlin
@Scheduled(fixedDelayString = "${telegram.polling.interval-seconds:5}", initialDelay = 10000)
fun pollUpdates() {
    // ...
    runBlocking {  // ❌ Блокирует поток
        // ...
        processUpdates(updates)
    }
}
```

#### `VacancySchedulerService.checkNewVacancies()`
```kotlin
@Scheduled(cron = "${app.schedule.vacancy-check:0 */15 * * * *}")
fun checkNewVacancies() {
    // ...
    runBlocking {  // ❌ Блокирует поток
        // ...
        analyzeVacancies(vacanciesToAnalyze)  // Может занимать много времени
    }
}
```

### 2. Отсутствие конфигурации пула потоков

В проекте **нет конфигурации `TaskScheduler`**, поэтому Spring использует **дефолтный пул потоков**:
- По умолчанию Spring создает **один поток** для всех `@Scheduled` методов
- Если один метод блокирует поток, другие не могут выполниться

### 3. Блокировка в `TelegramCommandHandler.handleCommand()`

Метод `handleCommand` также использует `runBlocking`:

```kotlin
fun handleCommand(chatId: String, text: String) {
    // ...
    if (text.startsWith("/skills")) {
        runBlocking {  // ❌ Блокирует поток
            val response = handleSkillsCommand(chatId, text)
            // Извлечение навыков может занимать много времени
        }
    }
    // ...
    runBlocking {  // ❌ Блокирует поток
        telegramClient.sendMessage(chatId, response)
    }
}
```

Команда `/skills` может выполнять долгие операции (извлечение навыков из вакансий через LLM), что блокирует обработку других команд.

## Сценарий проблемы

1. **Запускается анализ вакансий** (`VacancySchedulerService.checkNewVacancies()`)
2. Метод использует `runBlocking` и блокирует единственный поток пула
3. **Приходит команда от Telegram** (`TelegramPollingService.pollUpdates()`)
4. Метод не может выполниться, потому что поток занят анализом
5. **Команды не обрабатываются** до завершения анализа

## Решение

### Вариант 1: Раздельные пулы потоков (Рекомендуется)

Создать отдельные пулы потоков для разных задач:

```kotlin
@Configuration
@EnableScheduling
class SchedulingConfig {
    
    @Bean(name = "telegramPollingScheduler")
    fun telegramPollingScheduler(): TaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 2  // Отдельный пул для Telegram
        scheduler.threadNamePrefix = "telegram-polling-"
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setAwaitTerminationSeconds(60)
        return scheduler
    }
    
    @Bean(name = "vacancyScheduler")
    fun vacancyScheduler(): TaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 2  // Отдельный пул для анализа вакансий
        scheduler.threadNamePrefix = "vacancy-scheduler-"
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setAwaitTerminationSeconds(60)
        return scheduler
    }
}
```

И использовать их в сервисах:

```kotlin
@Service
class TelegramPollingService(
    // ...
    @Qualifier("telegramPollingScheduler") private val scheduler: TaskScheduler,
) {
    @Scheduled(fixedDelayString = "${telegram.polling.interval-seconds:5}", 
               initialDelay = 10000,
               scheduler = "telegramPollingScheduler")
    fun pollUpdates() {
        // ...
    }
}
```

### Вариант 2: Асинхронная обработка команд (Более правильный подход)

Переделать `handleCommand` в suspend функцию и использовать корутины:

```kotlin
@Service
class TelegramCommandHandler(
    // ...
    private val commandScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    fun handleCommand(chatId: String, text: String) {
        // Запускаем обработку в отдельной корутине (не блокируем поток)
        commandScope.launch {
            try {
                val response = handleCommandInternal(chatId, text)
                telegramClient.sendMessage(chatId, response)
            } catch (e: Exception) {
                log.error("Error handling command: ${e.message}", e)
                telegramClient.sendMessage(chatId, "❌ Ошибка: ${e.message}")
            }
        }
    }
    
    private suspend fun handleCommandInternal(chatId: String, text: String): String {
        // Вся логика обработки команд
    }
}
```

И убрать `runBlocking` из `TelegramPollingService`:

```kotlin
@Scheduled(fixedDelayString = "${telegram.polling.interval-seconds:5}", initialDelay = 10000)
fun pollUpdates() {
    if (!pollingEnabled || isPolling) {
        return
    }
    
    isPolling = true
    // Запускаем в корутине, не блокируя поток
    pollingScope.launch {
        try {
            val updates = telegramClient.getUpdates(...)
            if (updates.isNotEmpty()) {
                processUpdates(updates)
            }
        } catch (e: Exception) {
            log.error("Error polling updates: ${e.message}", e)
        } finally {
            isPolling = false
        }
    }
}
```

### Вариант 3: Комбинированный подход (Оптимальный)

1. Раздельные пулы потоков для `@Scheduled` методов
2. Асинхронная обработка команд через корутины
3. Убрать все `runBlocking` из обработчиков команд

## Приоритет исправления

**Высокий** - это критическая проблема UX, которая делает бота неотзывчивым во время анализа вакансий.

## Рекомендации

1. **Немедленно**: Создать раздельные пулы потоков для разных задач
2. **В ближайшее время**: Переделать обработку команд на асинхронную через корутины
3. **Рефакторинг**: Убрать все `runBlocking` из обработчиков команд (как указано в `CODE_REVIEW_TelegramCommandHandler.md`)