# Получение HH.ru токена вручную

## Шаг 1: Регистрация приложения
1. Перейдите на https://dev.hh.ru
2. Войдите в аккаунт HH.ru  
3. Создайте новое приложение
4. Укажите Redirect URI: `http://localhost:8080/callback`
5. Сохраните Client ID и Client Secret

## Шаг 2: Получение authorization code
Откройте в браузере (замените YOUR_CLIENT_ID):
```
https://hh.ru/oauth/authorize?response_type=code&client_id=YOUR_CLIENT_ID&redirect_uri=http://localhost:8080/callback
```

После разрешения доступа скопируйте `code` из URL.

## Шаг 3: Получение access token
Выполните POST запрос:

### PowerShell:
```powershell
$body = @{
    grant_type = "authorization_code"
    client_id = "YOUR_CLIENT_ID"
    client_secret = "YOUR_CLIENT_SECRET"  
    code = "YOUR_AUTHORIZATION_CODE"
    redirect_uri = "http://localhost:8080/callback"
}

$response = Invoke-RestMethod -Uri "https://hh.ru/oauth/token" -Method Post -Body $body -ContentType "application/x-www-form-urlencoded"
Write-Host "Access Token: $($response.access_token)"
```

### curl:
```bash
curl -X POST "https://hh.ru/oauth/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code&client_id=YOUR_CLIENT_ID&client_secret=YOUR_CLIENT_SECRET&code=YOUR_AUTHORIZATION_CODE&redirect_uri=http://localhost:8080/callback"
```

## Шаг 4: Обновить .env
Добавьте полученный токен в .env:
```
HH_ACCESS_TOKEN=ваш_полученный_токен
```

## Шаг 5: Перезапустить приложение
```
./gradlew bootRun
```


