# Быстрый старт: Трассировка вакансий

## Что уже работает

✅ **Trace ID через MDC** - автоматически добавляется во все логи при обработке вакансий

## Как использовать

### 1. Поиск вакансии в логах

```bash
# Найти все логи для вакансии с ID 123456
grep "vacancy-123456" logs/hh-assistant.log

# Или просто по ID
grep "\[123456\]" logs/hh-assistant.log
```

### 2. Пример вывода

```
2026-02-04 10:30:15 [vacancy-123456] [123456] INFO  VacancyProcessingQueue - Processing vacancy 123456
2026-02-04 10:30:16 [vacancy-123456] [123456] INFO  VacancyAnalysisService - Starting analysis for vacancy: 123456
2026-02-04 10:30:18 [vacancy-123456] [123456] INFO  VacancyStatusService - Updated vacancy 123456 status: QUEUED -> ANALYZED
2026-02-04 10:30:19 [vacancy-123456] [123456] INFO  VacancyNotificationService - Successfully sent vacancy 123456 to Telegram
```

### 3. Полный путь вакансии

```bash
# Найти все этапы обработки одной вакансии
grep "vacancy-123456" logs/hh-assistant.log | grep -E "(Processing|Starting|Updated|Successfully|Error)"
```

## Где устанавливается trace ID

Trace ID автоматически устанавливается в:
- ✅ `VacancyProcessingQueueService.processQueueItem()` - при обработке из очереди
- ✅ Все последующие вызовы наследуют trace ID через MDC

## Дополнительные варианты

Если нужна более продвинутая трассировка, см. [VACANCY_TRACING.md](./VACANCY_TRACING.md)

