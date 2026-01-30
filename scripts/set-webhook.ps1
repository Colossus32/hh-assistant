param(
    [Parameter(Mandatory=$true)]
    [string]$WebhookUrl
)

$botToken = "8361446565:AAFh6-x7ZFhPbiqpYTe68XGmJ0lCFzVPZnQ"

Write-Host "🔧 Настройка Telegram webhook..." -ForegroundColor Cyan
Write-Host "Webhook URL: $WebhookUrl" -ForegroundColor Gray

$setWebhookUrl = "https://api.telegram.org/bot$botToken/setWebhook?url=$WebhookUrl"
try {
    $response = Invoke-RestMethod -Uri $setWebhookUrl -Method Get
    
    if ($response.ok) {
        Write-Host "✅ Webhook успешно настроен!" -ForegroundColor Green
        
        Write-Host ""
        Write-Host "📋 Информация о webhook:" -ForegroundColor Cyan
        $infoUrl = "https://api.telegram.org/bot$botToken/getWebhookInfo"
        $info = Invoke-RestMethod -Uri $infoUrl -Method Get
        $info | ConvertTo-Json -Depth 10
        
        Write-Host ""
        Write-Host "✅ Теперь кнопки должны работать!" -ForegroundColor Green
        Write-Host "Попробуйте нажать кнопку в Telegram и проверьте логи приложения." -ForegroundColor Yellow
    } else {
        Write-Host "❌ Ошибка настройки webhook: $($response.description)" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "❌ Ошибка при настройке webhook: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}


