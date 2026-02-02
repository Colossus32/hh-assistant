# Получение токена доступа для HH.ru API

## ⚠️ Важно

Если у вас возникают проблемы с получением токена, попробуйте:

1. **Проверьте, что вы зарегистрированы на HH.ru** - нужен активный аккаунт
2. **Используйте скрипт автоматизации** - см. раздел "Автоматизированный способ" ниже
3. **Для тестирования можно запустить приложение без токена** - оно будет работать в режиме dry-run (см. `docs/WINDOWS_SETUP.md`)

## Краткая инструкция

HH.ru использует OAuth 2.0 для авторизации. Для получения токена нужно:

1. Создать приложение на https://dev.hh.ru (или https://hh.ru/account/applications если доступно)
2. Получить Client ID и Client Secret
3. Пройти OAuth flow для получения access token

## Подробные шаги

### Шаг 1: Создание приложения

**Вариант A: Через dev.hh.ru (рекомендуется)**

1. Перейдите на https://dev.hh.ru
2. Войдите с вашим аккаунтом HH.ru
3. Нажмите "Создать приложение" или найдите раздел "Мои приложения"
4. Заполните форму:
   - **Название**: HH Assistant (или любое другое)
   - **Redirect URI**: `http://localhost:8080` (для личного использования можно указать любой)
   - **Описание**: опционально
5. Сохраните приложение
6. **Скопируйте Client ID и Client Secret** - они понадобятся на следующем шаге

**Вариант B: Через личный кабинет (если доступно)**

1. Войдите на https://hh.ru
2. Перейдите в настройки аккаунта
3. Найдите раздел "Приложения" или "API" или "Для разработчиков"
4. Создайте новое приложение
5. Сохраните Client ID и Client Secret

**Если не можете найти раздел для создания приложения:**

- Попробуйте прямой доступ: https://hh.ru/account/applications
- Или обратитесь в поддержку HH.ru: https://hh.ru/feedback
- Можно также попробовать использовать публичные эндпоинты без токена (с ограничениями)

### Шаг 2: Получение Authorization Code

Откройте в браузере следующую ссылку (замените `YOUR_CLIENT_ID` на ваш Client ID):

```
https://hh.ru/oauth/authorize?response_type=code&client_id=YOUR_CLIENT_ID&redirect_uri=http://localhost:8080
```

1. Авторизуйтесь, если потребуется
2. Разрешите доступ приложению
3. После редиректа на `http://localhost:8080?code=XXXXX` скопируйте значение параметра `code` из URL

### Шаг 3: Обмен Code на Access Token

Используйте один из способов:

#### Способ 1: PowerShell

```powershell
$code = "ваш_code_из_шага_2"
$clientId = "ваш_client_id"
$clientSecret = "ваш_client_secret"

$body = @{
    grant_type = "authorization_code"
    client_id = $clientId
    client_secret = $clientSecret
    code = $code
    redirect_uri = "http://localhost:8080"
}

$response = Invoke-RestMethod -Uri "https://hh.ru/oauth/token" `
    -Method Post `
    -Body ($body | ConvertTo-Json) `
    -ContentType "application/json"

# Токен будет в $response.access_token
Write-Host "Access Token: $($response.access_token)"
```

#### Способ 2: curl

```bash
curl -X POST "https://hh.ru/oauth/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code" \
  -d "client_id=YOUR_CLIENT_ID" \
  -d "client_secret=YOUR_CLIENT_SECRET" \
  -d "code=YOUR_CODE" \
  -d "redirect_uri=http://localhost:8080"
```

#### Способ 3: Postman

1. Создайте POST запрос на `https://hh.ru/oauth/token`
2. В Body выберите `x-www-form-urlencoded`
3. Добавьте параметры:
   - `grant_type`: `authorization_code`
   - `client_id`: ваш Client ID
   - `client_secret`: ваш Client Secret
   - `code`: код из шага 2
   - `redirect_uri`: `http://localhost:8080`
4. Отправьте запрос
5. Скопируйте `access_token` из ответа

### Шаг 4: Сохранение токена

Добавьте токен в файл `.env`:

```env
HH_ACCESS_TOKEN=ваш_access_token_здесь
```

## Проверка токена

После добавления токена в `.env`, запустите приложение и проверьте health check:

```
http://localhost:8080/actuator/health
```

Если токен валидный, компонент `hhapi` будет показывать статус `UP`.

## Важные замечания

1. **Access Token имеет срок действия** - обычно несколько месяцев
2. Если токен истек, нужно повторить процесс получения
3. **Refresh Token** (если предоставляется) можно использовать для обновления access token без повторной авторизации
4. Для production рекомендуется реализовать автоматическое обновление токена

## Автоматизированный способ (PowerShell скрипт)

Для упрощения процесса можно использовать готовый скрипт:

1. Убедитесь, что у вас есть Client ID и Client Secret
2. Запустите скрипт:
   ```powershell
   .\scripts\get-hh-token.ps1 -ClientId "YOUR_CLIENT_ID" -ClientSecret "YOUR_CLIENT_SECRET"
   ```
3. Скрипт откроет браузер для авторизации и автоматически получит токен
4. Токен можно сохранить в `.env` файл автоматически

## Альтернативный способ: Запуск без токена

Если получение токена вызывает проблемы, можно запустить приложение в режиме **dry-run**:

1. В файле `.env` оставьте `HH_ACCESS_TOKEN` пустым или не указывайте его
2. Установите `app.scheduler.dry-run=true` в `application.yml`
3. Приложение будет работать без реальных запросов к HH.ru API
4. Можно протестировать работу с Ollama и Telegram

## Решение проблем

### Проблема: "Не могу найти раздел для создания приложения"

**Решение:**
- Попробуйте разные URL:
  - https://dev.hh.ru
  - https://hh.ru/account/applications
  - https://hh.ru/account/settings (ищите раздел "API" или "Приложения")
- Проверьте, что у вас есть активный аккаунт на HH.ru
- Обратитесь в поддержку HH.ru

### Проблема: "Ошибка при обмене кода на токен"

**Возможные причины:**
- Код уже использован (код можно использовать только один раз)
- Неправильный redirect_uri (должен совпадать с указанным при создании приложения)
- Истек срок действия кода (код действителен несколько минут)
- Неправильный Client ID или Client Secret

**Решение:**
- Получите новый код (повторите Шаг 2)
- Проверьте, что redirect_uri точно совпадает
- Убедитесь, что Client ID и Client Secret скопированы правильно

### Проблема: "Unauthorized" при использовании токена

**Возможные причины:**
- Токен истек
- Токен скопирован неправильно (лишние пробелы, переносы строк)
- Токен не имеет нужных прав доступа

**Решение:**
- Получите новый токен
- Проверьте формат токена в `.env` файле (без кавычек, без пробелов)
- Убедитесь, что при создании приложения выбраны нужные права доступа

## Документация

- Официальная документация: https://api.hh.ru/openapi/redoc#tag/OAuth
- Раздел для разработчиков: https://dev.hh.ru
- Поддержка HH.ru: https://hh.ru/feedback