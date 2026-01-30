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
try {
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
} catch {
    Write-Host "❌ Ошибка при настройке webhook: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}


