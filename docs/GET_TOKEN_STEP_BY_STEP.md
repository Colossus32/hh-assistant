# Пошаговая инструкция: Получение токена HH.ru через Client ID и Client Secret

**Модель AI:** Claude Sonnet 4.5 (через Cursor)

## Ваши данные из заявки #18240

```
Приложение: hh_helper_kotlin_bot
Redirect URI: http://localhost:8080
Client ID: LVDVVPKVGIB6E81TJ0NGNUMNIUVNV1UGIVN74VSOR1D1D8OSGO1JSFKMVIO6IHCH
Client Secret: OEUP8CLQM8M9P52QQVCUN2GOVF87PF0CDPCVPGUK117G5HQGEI49UKSQ8O8TV119
```

## Как работает OAuth 2.0 для HH.ru

HH.ru использует стандартный **Authorization Code Flow**:

1. **Шаг 1:** Получить authorization code через браузер (пользователь авторизуется)
2. **Шаг 2:** Обменять authorization code на access token (используя Client ID и Client Secret)
3. **Шаг 3:** Использовать access token для запросов к API

## Способ 1: Автоматический (через приложение)

### Шаг 1: Настройте .env

Добавьте в `.env`:

```env
HH_CLIENT_ID=LVDVVPKVGIB6E81TJ0NGNUMNIUVNV1UGIVN74VSOR1D1D8OSGO1JSFKMVIO6IHCH
HH_CLIENT_SECRET=OEUP8CLQM8M9P52QQVCUN2GOVF87PF0CDPCVPGUK117G5HQGEI49UKSQ8O8TV119
HH_REDIRECT_URI=http://localhost:8080/oauth/callback
```

### Шаг 2: Запустите приложение

```powershell
./gradlew bootRun
```

### Шаг 3: Получите URL для авторизации

Откройте в браузере:

```
http://localhost:8080/oauth/authorize
```

Вы получите JSON с `authorization_url`. Скопируйте этот URL.

### Шаг 4: Авторизуйтесь в HH.ru

1. Откройте скопированный `authorization_url` в браузере
2. Войдите в свой аккаунт HH.ru
3. Разрешите доступ приложению
4. Вы будете перенаправлены на `http://localhost:8080/oauth/callback?code=XXXXX`

### Шаг 5: Получение токена

После редиректа приложение автоматически обменяет code на токен и покажет его в JSON ответе.

Скопируйте `access_token` и добавьте в `.env`:

```env
HH_ACCESS_TOKEN=ваш_полученный_токен
```

## Способ 2: Вручную (через PowerShell/curl)

### Шаг 1: Получите Authorization Code

Откройте в браузере (замените `CLIENT_ID` на ваш):

```
https://hh.ru/oauth/authorize?response_type=code&client_id=LVDVVPKVGIB6E81TJ0NGNUMNIUVNV1UGIVN74VSOR1D1D8OSGO1JSFKMVIO6IHCH&redirect_uri=http://localhost:8080
```

**Важно:** Redirect URI должен точно совпадать с указанным в заявке: `http://localhost:8080`

После авторизации вы будете перенаправлены на:
```
http://localhost:8080?code=ВАШ_AUTHORIZATION_CODE
```

Скопируйте значение `code` из URL.

### Шаг 2: Обменяйте Code на Access Token

Выполните в PowerShell:

```powershell
$code = "ВАШ_AUTHORIZATION_CODE_ИЗ_ШАГА_1"
$clientId = "LVDVVPKVGIB6E81TJ0NGNUMNIUVNV1UGIVN74VSOR1D1D8OSGO1JSFKMVIO6IHCH"
$clientSecret = "OEUP8CLQM8M9P52QQVCUN2GOVF87PF0CDPCVPGUK117G5HQGEI49UKSQ8O8TV119"

$body = @{
    grant_type = "authorization_code"
    client_id = $clientId
    client_secret = $clientSecret
    code = $code
    redirect_uri = "http://localhost:8080"
}

$response = Invoke-RestMethod -Uri "https://hh.ru/oauth/token" `
    -Method Post `
    -Body $body `
    -ContentType "application/x-www-form-urlencoded"

Write-Host "Access Token: $($response.access_token)"
Write-Host "Token Type: $($response.token_type)"
Write-Host "Expires In: $($response.expires_in) seconds"
```

### Шаг 3: Сохраните токен

Скопируйте `access_token` из ответа и добавьте в `.env`:

```env
HH_ACCESS_TOKEN=ваш_полученный_токен
```

## Способ 3: Через curl (для Linux/Mac/Git Bash)

### Шаг 1: Получите Authorization Code

То же самое, что в Способе 2, Шаг 1.

### Шаг 2: Обменяйте Code на Token

```bash
curl -X POST "https://hh.ru/oauth/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code" \
  -d "client_id=LVDVVPKVGIB6E81TJ0NGNUMNIUVNV1UGIVN74VSOR1D1D8OSGO1JSFKMVIO6IHCH" \
  -d "client_secret=OEUP8CLQM8M9P52QQVCUN2GOVF87PF0CDPCVPGUK117G5HQGEI49UKSQ8O8TV119" \
  -d "code=ВАШ_AUTHORIZATION_CODE" \
  -d "redirect_uri=http://localhost:8080"
```

## Проверка токена

После получения токена проверьте его:

```powershell
# Проверьте health endpoint
curl http://localhost:8080/actuator/health

# Или протестируйте поиск вакансий
curl "http://localhost:8080/api/vacancies/search?keywords=Kotlin"
```

Если токен валидный, вы увидите:
- `HHAPI: UP` в health check
- Список вакансий в ответе на поиск

## Важные замечания

1. **Redirect URI должен точно совпадать** с указанным в заявке: `http://localhost:8080`
2. **Authorization code можно использовать только один раз** - после обмена он становится недействительным
3. **Authorization code действителен ограниченное время** (обычно несколько минут)
4. **Access token имеет срок действия** - если истек, получите новый через тот же процесс

## Troubleshooting

### Ошибка: "Invalid redirect_uri"

**Причина:** Redirect URI не совпадает с указанным в заявке

**Решение:** Убедитесь, что используете точно `http://localhost:8080` (без `/oauth/callback` в ручном способе)

### Ошибка: "Invalid authorization code"

**Причина:** Code уже использован или истек

**Решение:** Получите новый authorization code, повторив Шаг 1

### Ошибка: "Invalid client credentials"

**Причина:** Неверный Client ID или Client Secret

**Решение:** Проверьте, что правильно скопировали значения из заявки

### Ошибка: "Connection refused" при callback

**Причина:** Приложение не запущено на localhost:8080

**Решение:** Запустите приложение перед получением authorization code

## Дополнительная информация

- **OAuth 2.0 спецификация:** https://oauth.net/2/
- **HH.ru API документация:** https://api.hh.ru/openapi/redoc
- **Ваша заявка:** #18240 (одобрена)








