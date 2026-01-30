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


