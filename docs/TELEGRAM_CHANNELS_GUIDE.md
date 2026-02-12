# Telegram Channels Integration Guide

## Overview

HH Assistant now supports monitoring Telegram channels as an additional source of vacancies alongside HH.ru API. This allows you to:

- Add Telegram channels that post job vacancies
- Monitor these channels for new vacancies
- Parse and analyze vacancies from channel messages
- Send relevant vacancies to your Telegram chat after analysis

## Adding a Telegram Channel

### Prerequisites

**Для публичных каналов (рекомендуется):**
- **Веб-скрапинг включен по умолчанию**: Для публичных каналов не требуется добавлять бота как администратора
- Канал должен быть доступен по адресу `https://t.me/s/channel_name`
- Это самый простой способ - просто добавьте канал через команду `/add_channel`

**Для приватных каналов:**
- Бот должен быть добавлен как администратор в канал
- Для приватных каналов используется Telegram Bot API (требует прав администратора)

### Adding a Channel via Telegram Commands

**Для публичных каналов (веб-скрапинг, не требует прав администратора):**

1. Убедитесь, что канал публичный и доступен по адресу `https://t.me/s/channel_name`
2. Используйте команду `/add_channel`:
   ```
   /add_channel @channel_name
   ```
   
   Example: `/add_channel @devjobs`

3. Запустите мониторинг канала:
   ```
   /monitor_channel @channel_name
   ```
   
   Example: `/monitor_channel @devjobs`

**Для приватных каналов (требует прав администратора):**

1. Добавьте бота в канал как администратора:
   - Перейдите в настройки канала
   - Нажмите "Administrators"
   - Нажмите "Add Admin"
   - Найдите бота по username
   - Подтвердите с соответствующими правами

2. Используйте команду `/add_channel`:
   ```
   /add_channel @channel_name
   ```

3. Запустите мониторинг канала:
   ```
   /monitor_channel @channel_name
   ```

### Managing Channels

#### View All Channels
```
/channels
```
Shows all added channels with their monitoring status and last update time.

#### Start/Stop Monitoring
```
/monitor_channel @channel_name
/stop_monitoring @channel_name
```
Control whether the bot actively fetches vacancies from the channel.

#### Remove Channel
```
/remove_channel @channel_name
```
Completely remove the channel from the system. The bot will also leave the channel.

## Supported Message Formats

The parser can extract vacancies from various message formats:

### Ideal Format
```
🔥 [HOT] Senior Java Developer needed at fintech startup

🏢 Company: FinTech Solutions
💰 Salary: $5000-7000
📍 Location: Remote (EU timezone)
💼 Experience: 5+ years
🔗 Link: https://example.com/job/123

Looking for a Senior Java Developer with experience in fintech...
```

### Simple Text Format
```
Position: Middle Frontend Developer (React)
Company: TechCorp
Salary: from $2000
Location: Kyiv
Requirements: React 3+, TypeScript, REST API
Contact: hr@techcorp.com
```

### Mixed Format with Emojis
```
💼 Java Developer (Spring Boot)
📍 Москва, офис
💰 250000-300000 руб.
📝 3+ года опыта
⏱️ Полная занятость
```

## Configuration

### Application Settings

The following configuration options are available in `application.yml`:

```yaml
app:
  telegram-channels:
    enabled: true
    fetch-interval: 900  # Every 15 minutes (same as HH.ru)
    messages-per-fetch: 100  # Number of messages to fetch per request
    min-relevance-score: 0.7  # Minimum score for channel vacancies

telegram:
  # Использовать веб-скрапинг для публичных каналов (не требует прав администратора)
  use-web-scraping: true
  web-scraping:
    enabled: true  # Включить веб-скрапинг
    user-agent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36..."  # User-Agent для запросов
    timeout: 10000  # Таймаут запросов в миллисекундах
```

### Web Scraping vs Bot API

**Веб-скрапинг (рекомендуется для публичных каналов):**
- ✅ Не требует прав администратора
- ✅ Работает для всех публичных каналов
- ✅ Нет риска бана аккаунта
- ⚠️ Ограничение: показывает только последние ~50-100 сообщений
- ⚠️ Может сломаться при изменении верстки Telegram

**Bot API (для приватных каналов):**
- ✅ Полный доступ к истории сообщений
- ✅ Более стабильный метод
- ❌ Требует добавления бота как администратора
- ❌ Не работает для каналов, где бот не является администратором

### Channel-Level Settings

Each channel can have individual settings:
- `minRelevanceScore`: Override global minimum relevance score
- `isMonitored`: Enable/disable monitoring for this specific channel
- `notes`: Add notes about the channel for reference

## Troubleshooting

### Channel Not Accessible

**Для публичных каналов (веб-скрапинг):**
1. **Проверьте доступность канала**: Убедитесь, что канал доступен по адресу `https://t.me/s/channel_name`
2. **Проверьте настройки**: Убедитесь, что `telegram.web-scraping.enabled=true` в `application.yml`
3. **Проверьте логи**: Посмотрите логи на наличие ошибок парсинга HTML
4. **Изменение верстки**: Если Telegram изменил верстку, веб-скрапинг может временно не работать

**Для приватных каналов (Bot API):**
1. **Check bot permissions**: Make sure the bot is an administrator with "Read Messages" permission
2. **Privacy settings**: Ensure the channel allows bot administrators to read messages
3. **API rate limits**: Telegram has rate limits; wait if you get rate limit errors

### Poor Vacancy Detection
1. **Keywords not detected**: Check that the message contains job-related keywords
2. **Language support**: The parser supports English, Russian, and Ukrainian keywords
3. **Custom formats**: Consider editing message format to match supported patterns

### Duplicate Vacancies
1. **Message tracking**: Vacancies are tracked by `message_id` + `channel_username`
2. **Edited messages**: If a message is edited, it might be detected as a new vacancy
3. **Database constraint**: Unique constraint prevents exact duplicates

## Integration with HH.ru

Vacancies from Telegram channels are:
1. **Parsed**: Extracted into standardized vacancy format
2. **Analyzed**: Processed through the same LLM analysis as HH.ru vacancies
3. **Filtered**: Subject to the same exclusion rules and content validation
4. **Queued**: Added to the same processing queue as HH.ru vacancies
5. **Notified**: Sent via the same notification system

## Rate Limits and Best Practices

### API Rate Limits
- Telegram Bot API: 30 messages per second
- Consider adding delays if you monitor many channels
- Implement backoff strategy when rate limit is hit

### Best Practices
1. **Start with a few channels**: Add 2-3 popular channels first
2. **Monitor channel quality**: Focus on channels with high-quality, relevant vacancies
3. **Regular cleanup**: Periodically review and remove inactive or low-quality channels
4. **Respect privacy**: Only add channels that you have permission to monitor

## Security Considerations

1. **Channel permissions**: Only add channels where you have authorization to monitor
2. **Data privacy**: All extracted vacancies are processed through your private LLM instance
3. **Bot security**: Use a dedicated bot token with limited permissions
4. **Access control**: Consider which users can add/remove channels in your deployment

## Example Channels for Testing

Good channels to test with (check local regulations):

- Public job posting channels
- Tech-specific communities
- Developer job boards
- Industry-specific vacancy boards

## Monitoring and Analytics

The system tracks:
- Channel fetch success/failure rates
- Vacancy detection accuracy
- Processing performance
- Duplicate prevention effectiveness

Monitor these metrics through the `/stats` command and application logs.
