# Настройка OAuth 2.0 для HH.ru API

## Получение Client ID и Client Secret

1. Перейдите на https://dev.hh.ru
2. Войдите с вашим аккаунтом HH.ru
3. Создайте новое приложение или используйте существующее
4. Укажите **Redirect URI**: `http://localhost:8080/oauth/callback`
5. Сохраните **Client ID** и **Client Secret**

## Настройка .env файла

Добавьте в ваш `.env` файл:

```env
# HH.ru OAuth credentials
HH_CLIENT_ID=LVDVVPKVGIB6E81TJ0NGNUMNIUVNV1UGIVN74VSOR1D1D8OSGO1JSFKMVIO6IHCH
HH_CLIENT_SECRET=OEUP8CLQM8M9P52QQVCUN2GOVF87PF0CDPCVPGUK117G5HQGEI49UKSQ8O8TV119
HH_REDIRECT_URI=http://localhost:8080/oauth/callback

# Access token (будет получен автоматически через OAuth flow)
HH_ACCESS_TOKEN=
```

## Получение Access Token через OAuth Flow

### Шаг 1: Запустите приложение

```powershell
./gradlew bootRun
```

### Шаг 2: Получите URL для авторизации

Откройте в браузере:

```
http://localhost:8080/oauth/authorize
```

Или используйте curl:

```powershell
curl http://localhost:8080/oauth/authorize
```

Вы получите JSON ответ с `authorization_url`. Скопируйте этот URL.

### Шаг 3: Авторизуйтесь в HH.ru

1. Откройте `authorization_url` в браузере
2. Войдите в свой аккаунт HH.ru (если не авторизованы)
3. Разрешите доступ приложению
4. Вы будете перенаправлены на `http://localhost:8080/oauth/callback?code=XXXXX`

### Шаг 4: Получение Access Token

После редиректа на `/oauth/callback`, приложение автоматически:
1. Обменяет `authorization_code` на `access_token`
2. Вернет JSON ответ с `access_token`

Скопируйте `access_token` из ответа.

### Шаг 5: Сохранение токена

Добавьте полученный токен в `.env` файл:

```env
HH_ACCESS_TOKEN=ваш_полученный_токен
```

### Шаг 6: Перезапуск приложения

Перезапустите приложение, чтобы оно использовало новый токен:

```powershell
# Остановите текущий процесс (Ctrl+C)
./gradlew bootRun
```

## Проверка работы

После настройки токена проверьте health endpoint:

```powershell
curl http://localhost:8080/actuator/health
```

В ответе `HHAPI` должен иметь статус `UP` (если токен валидный).

## Альтернативный способ (вручную)

Если автоматический OAuth flow не работает, можно получить токен вручную:

### 1. Получите authorization code

Откройте в браузере (замените `YOUR_CLIENT_ID`):

```
https://hh.ru/oauth/authorize?response_type=code&client_id=YOUR_CLIENT_ID&redirect_uri=http://localhost:8080/oauth/callback
```

После авторизации скопируйте `code` из URL редиректа.

### 2. Обменяйте code на token

```powershell
$code = "ваш_code"
$clientId = "LVDVVPKVGIB6E81TJ0NGNUMNIUVNV1UGIVN74VSOR1D1D8OSGO1JSFKMVIO6IHCH"
$clientSecret = "OEUP8CLQM8M9P52QQVCUN2GOVF87PF0CDPCVPGUK117G5HQGEI49UKSQ8O8TV119"

$body = @{
    grant_type = "authorization_code"
    client_id = $clientId
    client_secret = $clientSecret
    code = $code
    redirect_uri = "http://localhost:8080/oauth/callback"
}

$response = Invoke-RestMethod -Uri "https://hh.ru/oauth/token" `
    -Method Post `
    -Body $body `
    -ContentType "application/x-www-form-urlencoded"

Write-Host "Access Token: $($response.access_token)"
```

### 3. Сохраните токен в .env

```env
HH_ACCESS_TOKEN=<скопированный_токен>
```

## Troubleshooting

### Ошибка: "Invalid client credentials"

- Проверьте, что `HH_CLIENT_ID` и `HH_CLIENT_SECRET` правильно скопированы
- Убедитесь, что в приложении на dev.hh.ru указан правильный Redirect URI

### Ошибка: "Invalid authorization code"

- Authorization code может быть использован только один раз
- Код действителен ограниченное время (обычно несколько минут)
- Получите новый код, повторив шаги 2-3

### Ошибка: "Redirect URI mismatch"

- Убедитесь, что Redirect URI в `.env` точно совпадает с указанным в приложении на dev.hh.ru
- Проверьте, что нет лишних пробелов или различий в регистре

### Токен истек

Access token от HH.ru имеет срок действия. Если токен истек:
1. Повторите OAuth flow (шаги 2-4)
2. Обновите `HH_ACCESS_TOKEN` в `.env`
3. Перезапустите приложение








