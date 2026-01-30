# Настройка Telegram Webhook

## Проблема: кнопки не работают

Если кнопки "Откликнулся" и "Неинтересная" не работают, это означает, что webhook не настроен или недоступен.

## Проверка текущего состояния webhook

### 1. Проверьте, настроен ли webhook

```bash
# Замените YOUR_BOT_TOKEN на ваш токен бота
curl "https://api.telegram.org/botYOUR_BOT_TOKEN/getWebhookInfo"
```

Ответ должен содержать информацию о webhook. Если `url` пустой или неправильный - webhook не настроен.

### 2. Проверьте логи приложения

При нажатии кнопки в логах должны появиться записи:
```
📥 [Webhook] Received update ID: ...
🔘 [Webhook] Callback query detected: ...
```

Если этих записей нет - webhook не получает обновления.

## Настройка webhook

### Вариант 1: Локальная разработка (ngrok)

1. Установите и запустите ngrok:
   ```bash
   ngrok http 8080
   ```

2. Скопируйте HTTPS URL (например: `https://abc123.ngrok.io`)

3. Настройте webhook:
   ```bash
   curl "https://api.telegram.org/botYOUR_BOT_TOKEN/setWebhook?url=https://abc123.ngrok.io/api/telegram/webhook"
   ```

4. Проверьте настройку:
   ```bash
   curl "https://api.telegram.org/botYOUR_BOT_TOKEN/getWebhookInfo"
   ```

### Вариант 2: Production (публичный сервер)

1. Убедитесь, что ваш сервер доступен из интернета по HTTPS

2. Настройте webhook:
   ```bash
   curl "https://api.telegram.org/botYOUR_BOT_TOKEN/setWebhook?url=https://your-domain.com/api/telegram/webhook"
   ```

3. Проверьте настройку:
   ```bash
   curl "https://api.telegram.org/botYOUR_BOT_TOKEN/getWebhookInfo"
   ```

### Вариант 3: Использование скрипта

Создайте файл `scripts/setup-telegram-webhook.sh`:

```bash
#!/bin/bash

BOT_TOKEN="${TELEGRAM_BOT_TOKEN}"
WEBHOOK_URL="${TELEGRAM_WEBHOOK_URL}"

if [ -z "$BOT_TOKEN" ]; then
    echo "❌ Error: TELEGRAM_BOT_TOKEN is not set"
    exit 1
fi

if [ -z "$WEBHOOK_URL" ]; then
    echo "❌ Error: TELEGRAM_WEBHOOK_URL is not set"
    echo "Example: export TELEGRAM_WEBHOOK_URL=https://your-domain.com/api/telegram/webhook"
    exit 1
fi

echo "🔧 Setting up Telegram webhook..."
echo "Bot Token: ${BOT_TOKEN:0:10}..."
echo "Webhook URL: $WEBHOOK_URL"

RESPONSE=$(curl -s "https://api.telegram.org/bot$BOT_TOKEN/setWebhook?url=$WEBHOOK_URL")

echo "Response: $RESPONSE"

# Проверяем результат
if echo "$RESPONSE" | grep -q '"ok":true'; then
    echo "✅ Webhook успешно настроен!"
    
    # Показываем информацию о webhook
    echo ""
    echo "📋 Webhook info:"
    curl -s "https://api.telegram.org/bot$BOT_TOKEN/getWebhookInfo" | jq .
else
    echo "❌ Ошибка настройки webhook"
    exit 1
fi
```

Для Windows PowerShell создайте `scripts/setup-telegram-webhook.ps1`:

```powershell
param(
    [Parameter(Mandatory=$true)]
    [string]$BotToken,
    
    [Parameter(Mandatory=$true)]
    [string]$WebhookUrl
)

Write-Host "🔧 Setting up Telegram webhook..." -ForegroundColor Cyan
Write-Host "Bot Token: $($BotToken.Substring(0, [Math]::Min(10, $BotToken.Length)))..." -ForegroundColor Gray
Write-Host "Webhook URL: $WebhookUrl" -ForegroundColor Gray

$setWebhookUrl = "https://api.telegram.org/bot$BotToken/setWebhook?url=$WebhookUrl"
$response = Invoke-RestMethod -Uri $setWebhookUrl -Method Get

if ($response.ok) {
    Write-Host "✅ Webhook успешно настроен!" -ForegroundColor Green
    
    Write-Host ""
    Write-Host "📋 Webhook info:" -ForegroundColor Cyan
    $infoUrl = "https://api.telegram.org/bot$BotToken/getWebhookInfo"
    $info = Invoke-RestMethod -Uri $infoUrl -Method Get
    $info | ConvertTo-Json -Depth 10
} else {
    Write-Host "❌ Ошибка настройки webhook: $($response.description)" -ForegroundColor Red
    exit 1
}
```

## Использование скрипта

### Windows PowerShell:
```powershell
.\scripts\setup-telegram-webhook.ps1 -BotToken "YOUR_BOT_TOKEN" -WebhookUrl "https://your-domain.com/api/telegram/webhook"
```

### Linux/Mac:
```bash
chmod +x scripts/setup-telegram-webhook.sh
export TELEGRAM_BOT_TOKEN="YOUR_BOT_TOKEN"
export TELEGRAM_WEBHOOK_URL="https://your-domain.com/api/telegram/webhook"
./scripts/setup-telegram-webhook.sh
```

## Проверка работы

1. Нажмите кнопку в Telegram

2. Проверьте логи приложения - должны появиться записи:
   ```
   📥 [Webhook] Received update ID: 123456
   🔘 [Webhook] Callback query detected: id=abc123, data=mark_applied_129888989
   🔘 [Webhook] Received callback query from user 123456789 (Иван): mark_applied_129888989
   ✅ [Webhook] User 123456789 clicked 'Откликнулся' button for vacancy 129888989
   ```

3. Если записей нет - проверьте:
   - Доступен ли webhook URL из интернета
   - Правильно ли настроен webhook
   - Есть ли ошибки в логах

## Удаление webhook (для отладки)

Если нужно временно отключить webhook:

```bash
curl "https://api.telegram.org/botYOUR_BOT_TOKEN/deleteWebhook"
```

## Troubleshooting

### Проблема: Webhook не получает обновления

**Причины:**
1. Webhook URL недоступен из интернета (localhost не работает)
2. Webhook URL не использует HTTPS (Telegram требует HTTPS)
3. Webhook URL неправильный
4. Сервер не запущен или недоступен

**Решение:**
1. Используйте ngrok для локальной разработки
2. Убедитесь, что URL использует HTTPS
3. Проверьте, что endpoint `/api/telegram/webhook` доступен
4. Проверьте логи приложения на наличие ошибок

### Проблема: Кнопки не работают, но webhook настроен

**Причины:**
1. Callback_query не обрабатывается
2. Ошибка при обработке callback_query
3. Не отвечаем на callback_query

**Решение:**
1. Проверьте логи - должны быть записи о callback_query
2. Убедитесь, что метод `answerCallbackQuery` вызывается
3. Проверьте, что нет ошибок при обработке

## Важно

- **Webhook требует HTTPS** - Telegram не принимает HTTP
- **Webhook должен быть доступен из интернета** - localhost не работает
- **Endpoint должен отвечать 200 OK** - иначе Telegram будет повторять запросы
- **Ответ должен быть быстрым** - Telegram ожидает ответ в течение нескольких секунд


