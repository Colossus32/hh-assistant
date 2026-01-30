# Мониторинг HH Assistant

Этот каталог содержит конфигурацию для мониторинга приложения HH Assistant с помощью Prometheus и Grafana.

## Быстрый старт

### 1. Запуск мониторинга

```bash
docker-compose -f docker-compose.monitoring.yml up -d
```

Это запустит:
- **Prometheus** на порту `9090` (http://localhost:9090)
- **Grafana** на порту `3000` (http://localhost:3000)

### 2. Проверка работы

#### Prometheus (http://localhost:9090)

1. Откройте http://localhost:9090
2. Перейдите в **Status → Targets** (в верхнем меню)
3. Убедитесь, что target `hh-assistant` имеет статус **UP** (зеленый)
   - Если статус **DOWN** (красный), проверьте:
     - Запущено ли приложение на `http://localhost:8080`
     - Доступен ли endpoint `/actuator/prometheus`
     - Правильно ли настроен `host.docker.internal` (для Windows/Mac)

4. Перейдите в **Graph** и попробуйте запрос:
   ```
   vacancies_fetched_total
   ```
   Если метрики есть, вы увидите график или значение.

#### Grafana (http://localhost:3000)

1. Откройте http://localhost:3000
2. Войдите с учетными данными:
   - **Username**: `admin`
   - **Password**: `admin`
3. Перейдите в **Dashboards** (иконка в левом меню)
4. Найдите дашборд **"HH Assistant - Overview"** и откройте его
5. Если метрики не отображаются:
   - Проверьте, что Prometheus собирает данные (см. выше)
   - Убедитесь, что приложение запущено и работает
   - Подождите несколько минут после запуска приложения (метрики появятся после первой активности)

### 3. Проверка доступности метрик приложения

Убедитесь, что приложение запущено и экспортирует метрики:

```bash
# Windows PowerShell
Invoke-WebRequest -Uri http://localhost:8080/actuator/prometheus | Select-Object -ExpandProperty Content

# Linux/Mac
curl http://localhost:8080/actuator/prometheus
```

Вы должны увидеть метрики в формате Prometheus, например:
```
# HELP vacancies_fetched_total Total number of vacancies fetched from HH.ru API
# TYPE vacancies_fetched_total counter
vacancies_fetched_total 42.0
```

## Решение проблем

### Проблема: Prometheus не видит приложение (Status: DOWN)

**Причина**: Prometheus не может подключиться к `host.docker.internal:8080`

**Решение для Windows**:
1. Убедитесь, что Docker Desktop запущен
2. Проверьте, что приложение доступно на `http://localhost:8080`
3. Если не работает, измените в `monitoring/prometheus/prometheus.yml`:
   ```yaml
   - targets: ['host.docker.internal:8080']
   ```
   на:
   ```yaml
   - targets: ['192.168.65.1:8080']  # IP адрес хоста из Docker
   ```
   Или используйте IP вашего компьютера в локальной сети.

**Решение для Linux**:
Замените `host.docker.internal` на IP адрес хоста или используйте `172.17.0.1` (стандартный IP Docker bridge).

### Проблема: Grafana показывает "No data"

**Причины**:
1. Приложение еще не генерировало метрики (нужно подождать активности)
2. Prometheus не собирает данные
3. Неправильные запросы в дашборде

**Решение**:
1. Проверьте Prometheus (см. выше)
2. Запустите приложение и подождите несколько минут
3. В Grafana перейдите в **Explore** и попробуйте запрос:
   ```
   vacancies_fetched_total
   ```
   Если данные есть, они появятся в Explore.

### Проблема: Метрики не появляются

**Причина**: Приложение не экспортирует метрики

**Решение**:
1. Убедитесь, что в `application.yml` включены endpoints:
   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: health,info,metrics,prometheus
   ```
2. Проверьте, что приложение запущено и доступно
3. Проверьте логи приложения на наличие ошибок

## Доступные метрики

### Counter метрики (всегда увеличиваются):
- `vacancies_fetched_total` - Общее количество полученных вакансий
- `vacancies_analyzed_total` - Общее количество проанализированных вакансий
- `vacancies_relevant_total` - Общее количество релевантных вакансий
- `vacancies_skipped_total` - Общее количество пропущенных вакансий
- `vacancies_rejected_by_validator_total` - Общее количество отклоненных валидатором
- `events_published_total` - Общее количество опубликованных событий
- `events_received_total` - Общее количество полученных событий
- `cover_letters_generated_total` - Общее количество сгенерированных писем
- `cover_letters_failed_total` - Общее количество неудачных генераций
- `notifications_sent_total` - Общее количество отправленных уведомлений
- `notifications_failed_total` - Общее количество неудачных уведомлений

### Timer метрики (время выполнения):
- `vacancy_analysis_duration_seconds` - Время анализа вакансии
- `cover_letter_generation_duration_seconds` - Время генерации письма
- `vacancy_fetch_duration_seconds` - Время получения вакансий

### Gauge метрики (текущие значения):
- `cover_letter_queue_size` - Размер очереди генерации писем
- `resume_active` - Наличие активного резюме (1 = есть, 0 = нет)

## Примеры Prometheus запросов

### Общее количество релевантных вакансий:
```
vacancies_relevant_total
```

### Скорость получения вакансий (за последние 5 минут):
```
rate(vacancies_fetched_total[5m])
```

### 95-й перцентиль времени анализа:
```
histogram_quantile(0.95, rate(vacancy_analysis_duration_seconds_bucket[5m]))
```

### Размер очереди генерации писем:
```
cover_letter_queue_size
```

## Остановка мониторинга

```bash
docker-compose -f docker-compose.monitoring.yml down
```

Для удаления данных:
```bash
docker-compose -f docker-compose.monitoring.yml down -v
```
