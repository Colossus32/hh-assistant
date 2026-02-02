# Анализ статистики навыков

## Текущая реализация

### 1. Структура данных

#### Таблица `skills`
- `id` - уникальный идентификатор
- `name` - оригинальное название навыка (например, "Kotlin Developer")
- `normalized_name` - нормализованное название (например, "kotlin developer") - уникальный индекс
- `occurrence_count` - количество вакансий, в которых был найден этот навык
- `last_seen_at` - дата последнего обнаружения
- `created_at` - дата создания

#### Таблица `vacancy_skills` (связь many-to-many)
- `id` - уникальный идентификатор
- `vacancy_id` - ID вакансии
- `skill_id` - ID навыка
- `extracted_at` - дата извлечения навыка из вакансии

### 2. Как считается статистика

#### `occurrenceCount` (количество вакансий с навыком)
- **Источник**: поле `occurrence_count` в таблице `skills`
- **Обновление**: увеличивается на 1 каждый раз, когда навык извлекается из вакансии
- **Метод**: `Skill.incrementOccurrence()` вызывается в `SkillExtractionService.saveOrUpdateSkill()`

#### `totalVacanciesAnalyzed` (общее количество проанализированных вакансий)
- **Источник**: SQL запрос `COUNT(DISTINCT vs.vacancyId) FROM VacancySkill vs`
- **Метод**: `VacancySkillRepository.countDistinctVacancies()`
- **Логика**: считает уникальные вакансии, из которых были извлечены навыки (есть записи в `vacancy_skills`)

#### `frequencyPercentage` (процент встречаемости)
- **Формула**: `(occurrenceCount / totalVacanciesAnalyzed) * 100`
- **Метод**: `SkillStatisticsService.calculateFrequencyPercentage()`
- **Диапазон**: 0.0 - 100.0

### 3. Процесс извлечения навыков

```kotlin
extractAndSaveSkills(vacancy, keySkillsFromApi):
  1. Извлечение навыков из API или LLM
  2. Нормализация навыков (приведение к единому виду)
  3. Сохранение/обновление навыков в БД:
     - Если навык существует (по normalized_name) → incrementOccurrence()
     - Если навык новый → создается с occurrenceCount = 1
  4. Проверка существующих связей VacancySkill
  5. Создание новых связей (только если связи еще нет)
  6. Обновление vacancy.skills_extracted_at
```

## ⚠️ Обнаруженная проблема

### Проблема: `occurrenceCount` увеличивается даже при повторном извлечении

**Текущая логика:**
1. Навыки сохраняются/обновляются (строка 99-101) → `occurrenceCount` увеличивается
2. Проверка существующих связей происходит ПОСЛЕ (строка 107-108)
3. Если связь уже существует, она не создается, но `occurrenceCount` уже увеличен

**Последствия:**
- Если навык извлекается повторно из той же вакансии, `occurrenceCount` увеличивается
- Статистика становится неточной: `occurrenceCount` может быть больше реального количества вакансий
- `frequencyPercentage` завышается

**Пример:**
- Вакансия #1 извлекается первый раз: "Kotlin" → `occurrenceCount = 1`
- Вакансия #1 извлекается второй раз: "Kotlin" → `occurrenceCount = 2` (но связь не создается)
- Реальная статистика: навык в 1 вакансии
- Отображаемая статистика: навык в 2 вакансиях ❌

### Когда это может происходить:
1. Recovery механизм извлекает навыки повторно
2. Ручной перезапуск извлечения навыков
3. Ошибки при извлечении, требующие повторной обработки

## Рекомендации по исправлению

### Вариант 1: Проверка существующих связей ДО сохранения навыков
```kotlin
// 1. Проверить существующие связи
val existingLinks = vacancySkillRepository.findByVacancyId(vacancy.id)
val existingSkillIds = existingLinks.map { it.skillId }.toSet()

// 2. Сохранять/обновлять только навыки, которых еще нет
val newSkills = normalizedSkills.filter { skillName ->
    val normalized = normalizeSkillName(skillName)
    val skill = skillRepository.findByNormalizedName(normalized).orElse(null)
    skill?.id !in existingSkillIds
}

// 3. Сохранять только новые навыки
val savedSkills = newSkills.map { skillName ->
    saveOrUpdateSkill(skillName)
}
```

### Вариант 2: Использовать `occurrenceCount` из таблицы `vacancy_skills`
```kotlin
// Вместо occurrenceCount из skills использовать реальный COUNT из vacancy_skills
@Query("SELECT COUNT(DISTINCT vs.vacancyId) FROM VacancySkill vs WHERE vs.skillId = :skillId")
fun countVacanciesBySkillId(skillId: Long): Long
```

### Вариант 3: Удалять старые связи перед повторным извлечением
```kotlin
// Удалить все связи для вакансии перед извлечением
vacancySkillRepository.deleteByVacancyId(vacancy.id)
// Затем извлечь навыки заново
```

## Текущая точность статистики

### Что работает правильно:
✅ `totalVacanciesAnalyzed` - точный (считает DISTINCT вакансии из vacancy_skills)
✅ Сортировка топ навыков - правильная (по occurrenceCount DESC)
✅ Нормализация навыков - работает (объединяет синонимы)

### Что может быть неточно:
⚠️ `occurrenceCount` - может быть завышен при повторном извлечении
⚠️ `frequencyPercentage` - завышен из-за неточного `occurrenceCount`

## Выводы

1. **Статистика в целом работает**, но есть потенциальная проблема с точностью `occurrenceCount`
2. **Проблема проявляется** только при повторном извлечении навыков из той же вакансии
3. **Рекомендуется исправить** логику, чтобы `occurrenceCount` увеличивался только при создании новой связи
4. **Альтернатива**: использовать реальный COUNT из `vacancy_skills` вместо `occurrenceCount` из `skills`


