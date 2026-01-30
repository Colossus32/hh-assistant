# Скрипт для получения токена доступа HH.ru API
# Использование: .\get-hh-token.ps1 -ClientId "YOUR_CLIENT_ID" -ClientSecret "YOUR_CLIENT_SECRET"

param(
    [Parameter(Mandatory=$true)]
    [string]$ClientId,
    
    [Parameter(Mandatory=$true)]
    [string]$ClientSecret
)

Write-Host "=== Получение токена доступа для HH.ru API ===" -ForegroundColor Cyan
Write-Host ""

# Шаг 1: Получение Authorization Code
$redirectUri = "http://localhost:8080"
$authUrl = "https://hh.ru/oauth/authorize?response_type=code&client_id=$ClientId&redirect_uri=$redirectUri"

Write-Host "Шаг 1: Откройте следующую ссылку в браузере:" -ForegroundColor Yellow
Write-Host $authUrl -ForegroundColor Green
Write-Host ""
Write-Host "После авторизации вы будете перенаправлены на:"
Write-Host "$redirectUri?code=XXXXX" -ForegroundColor Green
Write-Host ""
Write-Host "Скопируйте значение параметра 'code' из URL и вставьте его ниже:" -ForegroundColor Yellow

$code = Read-Host "Введите authorization code"

if ([string]::IsNullOrWhiteSpace($code)) {
    Write-Host "Ошибка: код не может быть пустым!" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Шаг 2: Обмен кода на токен доступа..." -ForegroundColor Yellow

# Шаг 2: Обмен кода на токен
try {
    $body = @{
        grant_type = "authorization_code"
        client_id = $ClientId
        client_secret = $ClientSecret
        code = $code
        redirect_uri = $redirectUri
    }

    # Используем application/x-www-form-urlencoded
    $formData = @()
    foreach ($key in $body.Keys) {
        $encodedValue = [System.Uri]::EscapeDataString($body[$key])
        $formData += "$key=$encodedValue"
    }
    $bodyString = $formData -join "&"

    try {
        $response = Invoke-RestMethod -Uri "https://hh.ru/oauth/token" `
            -Method Post `
            -Body $bodyString `
            -ContentType "application/x-www-form-urlencoded"

        if ($response.access_token) {
        Write-Host ""
        Write-Host "=== Токен успешно получен! ===" -ForegroundColor Green
        Write-Host ""
        Write-Host "Access Token:" -ForegroundColor Cyan
        Write-Host $response.access_token -ForegroundColor White
        Write-Host ""
        
        if ($response.refresh_token) {
            Write-Host "Refresh Token (для обновления токена):" -ForegroundColor Cyan
            Write-Host $response.refresh_token -ForegroundColor White
            Write-Host ""
        }
        
        Write-Host "Срок действия токена: $($response.expires_in) секунд" -ForegroundColor Yellow
        Write-Host ""
        
        # Предложение сохранить в .env
        $saveToEnv = Read-Host "Сохранить токен в файл .env? (y/n)"
        if ($saveToEnv -eq "y" -or $saveToEnv -eq "Y") {
            $envFile = ".env"
            $envContent = ""
            
            if (Test-Path $envFile) {
                $envContent = Get-Content $envFile -Raw
                
                # Обновляем существующий токен или добавляем новый
                if ($envContent -match "HH_ACCESS_TOKEN=") {
                    $envContent = $envContent -replace "HH_ACCESS_TOKEN=.*", "HH_ACCESS_TOKEN=$($response.access_token)"
                } else {
                    $envContent += "`nHH_ACCESS_TOKEN=$($response.access_token)"
                }
            } else {
                $envContent = "HH_ACCESS_TOKEN=$($response.access_token)"
            }
            
            Set-Content -Path $envFile -Value $envContent -NoNewline
            Write-Host "Токен сохранен в файл .env" -ForegroundColor Green
        }
        
        Write-Host ""
        Write-Host "Добавьте следующую строку в ваш .env файл:" -ForegroundColor Yellow
        Write-Host "HH_ACCESS_TOKEN=$($response.access_token)" -ForegroundColor White
        
        } else {
        Write-Host "Ошибка: токен не найден в ответе" -ForegroundColor Red
        Write-Host "Ответ сервера:" -ForegroundColor Yellow
        Write-Host ($response | ConvertTo-Json -Depth 10)
        exit 1
        }
    
} catch {
    Write-Host ""
    Write-Host "Ошибка при получении токена:" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    
    if ($_.Exception.Response) {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $responseBody = $reader.ReadToEnd()
        Write-Host "Ответ сервера:" -ForegroundColor Yellow
        Write-Host $responseBody
    }
    
    exit 1
}

