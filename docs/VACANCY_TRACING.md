# Трассировка вакансий через приложение

Документ описывает, как отслеживать прохождение вакансии через все компоненты приложения с помощью trace ID в логах.

## Варианты трассировки

### 1. ✅ Простой вариант: Trace ID через MDC (реализовано)

**Преимущества:**
- ✅ Не требует дополнительных зависимостей
- ✅ Работает сразу после настройки
- ✅ Все логи автоматически содержат trace ID
- ✅ Легко искать в логах по ID вакансии

**Как работает:**
- При обработке вакансии устанавливается trace ID в MDC (Mapped Diagnostic Context)
- Все логи в этом контексте автоматически содержат trace ID
- Trace ID имеет формат: `vacancy-{vacancyId}`

**Пример логов:**
```
2026-02-04 10:30:15 [vacancy-123456] [123456] INFO  VacancyProcessingQueue - Processing vacancy 123456
2026-02-04 10:30:16 [vacancy-123456] [123456] INFO  VacancyAnalysisService - Starting analysis for vacancy: 123456
2026-02-04 10:30:18 [vacancy-123456] [123456] INFO  VacancyStatusService - Updated vacancy 123456 status: QUEUED -> ANALYZED
2026-02-04 10:30:19 [vacancy-123456] [123456] INFO  VacancyNotificationService - Successfully sent vacancy 123456 to Telegram
```

**Поиск в логах:**
```bash
# Найти все логи для конкретной вакансии
grep "vacancy-123456" logs/hh-assistant.log

# Или просто по ID вакансии
grep "\[123456\]" logs/hh-assistant.log
```

### 2. Micrometer Tracing (опционально, без Zipkin)

**Преимущества:**
- ✅ Использует существующую зависимость `micrometer-registry-prometheus`
- ✅ Можно экспортировать в Prometheus для визуализации
- ✅ Поддержка distributed tracing (если понадобится в будущем)

**Сложность внедрения:** Средняя (требует добавления зависимостей и настройки)

**Зависимости:**
```kotlin
implementation("io.micrometer:micrometer-tracing")
implementation("io.micrometer:micrometer-tracing-bridge-brave") // или otel
```

**Настройка:**
```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # 100% трассировка
```

### 3. Zipkin (полноценное distributed tracing)

**Преимущества:**
- ✅ Визуализация трассировки в UI
- ✅ Анализ производительности
- ✅ Поиск по trace ID
- ✅ Интеграция с Grafana

**Недостатки:**
- ❌ Требует дополнительной инфраструктуры (Zipkin server)
- ❌ Больше накладных расходов

**Сложность внедрения:** Средняя-высокая (требует настройки Zipkin server)

**Зависимости:**
```kotlin
implementation("io.micrometer:micrometer-tracing-bridge-brave")
implementation("io.zipkin.reporter2:zipkin-reporter-brave")
```

## Текущая реализация (вариант 1)

### Использование TraceContext

Trace ID автоматически устанавливается в ключевых местах обработки вакансий:

1. **VacancyProcessingQueueService.processQueueItem()** - при обработке из очереди
2. Все последующие вызовы автоматически наследуют trace ID через MDC

### Ручное использование (если нужно)

```kotlin
import com.hhassistant.util.TraceContext

// Для обычного кода
TraceContext.withTraceId("vacancy-123456", "123456") {
    log.info("Processing vacancy")
    // Все логи здесь будут содержать traceId=vacancy-123456
}

// Для корутин
TraceContext.withTraceIdSuspend("vacancy-123456", "123456") {
    val analysis = analyzeVacancy(vacancy)
    // Все логи здесь будут содержать traceId=vacancy-123456
}
```

### Формат логов

После настройки паттерн логов включает trace ID:

**Консоль:**
```
2026-02-04 10:30:15 [vacancy-123456] [123456] - Processing vacancy 123456
```

**Файл:**
```
2026-02-04 10:30:15 [main] [vacancy-123456] [123456] INFO  VacancyProcessingQueue - Processing vacancy 123456
```

Где:
- `[vacancy-123456]` - trace ID
- `[123456]` - vacancy ID (для удобства поиска)

## Поиск вакансии в логах

### По trace ID
```bash
grep "vacancy-123456" logs/hh-assistant.log
```

### По vacancy ID
```bash
grep "\[123456\]" logs/hh-assistant.log
```

### Полный путь вакансии
```bash
# Найти все этапы обработки вакансии
grep "vacancy-123456" logs/hh-assistant.log | grep -E "(Processing|Starting|Updated|Successfully|Error)"
```

### Пример полной трассировки
```bash
$ grep "vacancy-123456" logs/hh-assistant.log

2026-02-04 10:30:15 [vacancy-123456] [123456] INFO  VacancyProcessingQueue - Processing vacancy 123456
2026-02-04 10:30:15 [vacancy-123456] [123456] INFO  VacancyProcessingQueue - Starting analysis pipeline for vacancy 123456
2026-02-04 10:30:16 [vacancy-123456] [123456] INFO  VacancyAnalysisService - Starting analysis for vacancy: 123456
2026-02-04 10:30:18 [vacancy-123456] [123456] INFO  VacancyStatusService - Updated vacancy 123456 status: QUEUED -> ANALYZED
2026-02-04 10:30:18 [vacancy-123456] [123456] INFO  VacancyProcessingQueue - Vacancy 123456 is relevant (score: 85.00%)
2026-02-04 10:30:19 [vacancy-123456] [123456] INFO  VacancyNotificationService - Successfully sent vacancy 123456 to Telegram
2026-02-04 10:30:19 [vacancy-123456] [123456] INFO  VacancyStatusService - Updated vacancy 123456 status: ANALYZED -> SENT_TO_USER
```

## Настройка

### application.yml

Паттерн логов уже настроен в `application.yml`:

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%X{traceId:-}] [%X{vacancyId:-}] - %msg%n"
    file: "%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{traceId:-}] [%X{vacancyId:-}] %-5level %logger{36} - %msg%n"
```

Где:
- `%X{traceId:-}` - trace ID из MDC (или `-` если не установлен)
- `%X{vacancyId:-}` - vacancy ID из MDC (или `-` если не установлен)

## Расширенная трассировка (будущее)

Если понадобится более продвинутая трассировка:

1. **Micrometer Tracing** - для метрик и экспорта в Prometheus
2. **Zipkin** - для визуализации в UI
3. **Jaeger** - альтернатива Zipkin

Все эти варианты можно добавить позже без изменения текущего кода, так как они используют те же принципы (MDC/trace context).

## Рекомендации

1. **Для текущих нужд:** Используйте вариант 1 (MDC) - он уже реализован и работает
2. **Для мониторинга:** Добавьте Micrometer Tracing, если нужны метрики в Prometheus
3. **Для визуализации:** Добавьте Zipkin, если нужен UI для анализа трассировок






