# Быстрый старт мониторинга

## Шаг 1: Запустите приложение

Убедитесь, что ваше приложение HH Assistant запущено и доступно на `http://localhost:8080`.

## Шаг 2: Запустите Prometheus и Grafana

```bash
docker-compose -f docker-compose.monitoring.yml up -d
```

## Шаг 3: Проверьте Prometheus

1. Откройте http://localhost:9090
2. Перейдите в **Status → Targets**
3. Убедитесь, что `hh-assistant` имеет статус **UP** (зеленый)
4. Если статус **DOWN**:
   - Проверьте, что приложение запущено: откройте http://localhost:8080/actuator/prometheus в браузере
   - Если не работает, измените в `monitoring/prometheus/prometheus.yml`:
     ```yaml
     - targets: ['192.168.65.1:8080']  # или IP вашего компьютера
     ```
   - Перезапустите Prometheus: `docker-compose -f docker-compose.monitoring.yml restart prometheus`

## Шаг 4: Проверьте метрики в Prometheus

1. В Prometheus перейдите в **Graph**
2. Введите запрос: `vacancies_fetched_total`
3. Нажмите **Execute**
4. Если видите значение (даже 0) - все работает!

## Шаг 5: Откройте Grafana

1. Откройте http://localhost:3000
2. Войдите:
   - Username: `admin`
   - Password: `admin`
3. Перейдите в **Dashboards** (иконка в левом меню)
4. Найдите и откройте **"HH Assistant - Overview"**

## Шаг 6: Если метрики не отображаются

### Проверка 1: Приложение генерирует метрики?

Откройте в браузере: http://localhost:8080/actuator/prometheus

Вы должны увидеть что-то вроде:
```
# HELP vacancies_fetched_total Total number of vacancies fetched from HH.ru
# TYPE vacancies_fetched_total counter
vacancies_fetched_total 0.0
```

### Проверка 2: Prometheus собирает данные?

1. В Prometheus (http://localhost:9090) перейдите в **Graph**
2. Введите: `up{job="hh-assistant"}`
3. Должно быть значение `1` (если `0` - Prometheus не может подключиться)

### Проверка 3: Метрики есть, но Grafana показывает "No data"

1. В Grafana перейдите в **Explore** (иконка в левом меню)
2. Выберите datasource **Prometheus**
3. Введите запрос: `vacancies_fetched_total`
4. Нажмите **Run query**
5. Если данные есть здесь, но не в дашборде - проблема в дашборде

### Проверка 4: Метрики появляются только после активности

**Это нормально!** Метрики будут равны 0, пока приложение не начнет работать:
- Получать вакансии
- Анализировать их
- Генерировать письма
- Отправлять уведомления

Подождите несколько минут после запуска приложения или запустите проверку вакансий вручную.

## Полезные запросы для проверки

В Prometheus или Grafana Explore попробуйте:

```promql
# Все метрики вакансий
vacancies_fetched_total
vacancies_analyzed_total
vacancies_relevant_total

# Размер очереди
cover_letter_queue_size

# Активное резюме (1 = есть, 0 = нет)
resume_active

# Время анализа (если были анализы)
vacancy_analysis_duration_seconds_count
```

## Остановка

```bash
docker-compose -f docker-compose.monitoring.yml down
```


