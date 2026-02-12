# Анализ оптимизации SQL-запроса

## Исходный запрос

```sql
select f.actual_departure,
       f.actual_arrival,
       f.route_no
  from bookings.seats s
inner join bookings.routes r on r.airplane_code = s.airplane_code
inner join bookings.flights f on f.route_no = r.route_no
where s.seat_no = '4A';
```

## Текущий план выполнения

```
Hash Join (cost=72.10..950.34 rows=32067 width=23)
  Output: f.actual_departure, f.actual_arrival, f.route_no
  Hash Cond: (f.route_no = r.route_no)
  -> Seq Scan on bookings.flights f (cost=0.00..421.58 rows=21758 width=23)
  -> Hash (cost=62.23..62.23 rows=790 width=7)
        -> Nested Loop (cost=0.29..62.23 rows=790 width=7)
              Inner Unique: true
              -> Seq Scan on bookings.routes r (cost=0.00..31.62 rows=1162 width=11)
              -> Memoize (cost=0.29..0.37 rows=1 width=4)
                    Cache Key: r.airplane_code
                    Cache Mode: logical
                    -> Index Only Scan using seats_pkey on bookings.seats s (cost=0.28..0.36 rows=1 width=4)
                          Index Cond: ((s.airplane_code = r.airplane_code) AND (s.seat_no = '4A'::text))
```

## Статистика таблиц

- **bookings.seats**: 1741 строка, 81 kB
- **bookings.routes**: 1162 строки, 163840 kB (160 MB)
- **bookings.flights**: 21758 строк, 1671168 kB (1.6 GB)

## Диагностические запросы

1. `select count(*) from bookings.seats s where s.seat_no = '4A'` → **5 строк**
2. `select count(*) from bookings.seats s inner join bookings.routes r on r.airplane_code = s.airplane_code where s.seat_no = '4A'` → **1150 строк**

## Анализ проблем в текущем плане

### 🔴 Проблема #1: Seq Scan на flights (самая критичная)

**Текущее поведение:**
- План делает **Seq Scan на всей таблице flights** (21758 строк, 1.6 GB)
- Это самая дорогая операция: `cost=0.00..421.58`

**Почему это плохо:**
- Читается весь файл таблицы (1.6 GB)
- Даже если в результате будет только 1150 строк (из диагностики), PostgreSQL сканирует все 21758 строк
- Hash Join строится на основе всех строк flights, а не только нужных

**Ожидаемое поведение:**
- Сначала отфильтровать routes по условию (получить 1150 строк)
- Затем использовать индекс на `flights.route_no` для быстрого поиска только нужных рейсов
- Это должно дать Index Scan или Index Seek вместо Seq Scan

### 🟡 Проблема #2: Порядок JOIN'ов

**Текущий порядок:**
1. `seats` JOIN `routes` (через Nested Loop) → 1150 строк
2. Результат JOIN'ится с `flights` (через Hash Join) → сканируется вся таблица flights

**Оптимальный порядок:**
1. Отфильтровать `seats` по `seat_no = '4A'` → **5 строк** (самый селективный фильтр)
2. JOIN с `routes` → 1150 строк
3. JOIN с `flights` через индекс на `route_no` → только нужные строки

### 🟢 Что работает хорошо

- **Index Only Scan на seats**: Используется индекс `seats_pkey` с фильтром по `airplane_code` и `seat_no`
- **Memoize**: Кэширование результатов поиска в seats (хорошо для повторяющихся значений)
- **Nested Loop для seats-routes**: Подходит, так как после фильтрации seats остается мало строк

## Рекомендации по оптимизации

### ✅ Рекомендация #1: Создать индекс на flights.route_no

**Если индекса нет:**
```sql
CREATE INDEX idx_flights_route_no ON bookings.flights(route_no);
```

**Почему это важно:**
- Позволит использовать Index Scan вместо Seq Scan при JOIN с routes
- Для 1150 route_no из результата JOIN, индекс позволит быстро найти соответствующие flights
- Ожидаемое улучшение: Seq Scan (421.58 cost) → Index Scan (~50-100 cost)

### ✅ Рекомендация #2: Проверить составной индекс на seats

**Текущий индекс:** `seats_pkey` (вероятно на `(airplane_code, seat_no)` или только на `seat_no`)

**Если primary key только на `seat_no`:**
```sql
-- Создать составной индекс для оптимизации JOIN
CREATE INDEX idx_seats_airplane_seat ON bookings.seats(airplane_code, seat_no);
```

**Если primary key на `(airplane_code, seat_no)` - уже оптимально!**

### ✅ Рекомендация #3: Создать индекс на routes.airplane_code (если нет)

```sql
CREATE INDEX idx_routes_airplane_code ON bookings.routes(airplane_code);
```

**Почему:**
- Ускорит JOIN между seats и routes
- Хотя Nested Loop уже работает, индекс может улучшить производительность

### ✅ Рекомендация #4: Обновить статистику после создания индексов

```sql
ANALYZE bookings.seats;
ANALYZE bookings.routes;
ANALYZE bookings.flights;
```

## Ожидаемый улучшенный план

После создания индексов, план должен выглядеть примерно так:

```
Nested Loop (cost=0.57..XXX rows=1150 width=23)
  -> Nested Loop (cost=0.29..62.23 rows=1150 width=7)
        -> Index Scan using idx_seats_seat_no on bookings.seats s
             Index Cond: (s.seat_no = '4A'::text)  -- 5 строк
        -> Index Scan using idx_routes_airplane_code on bookings.routes r
             Index Cond: (r.airplane_code = s.airplane_code)  -- 1150 строк
  -> Index Scan using idx_flights_route_no on bookings.flights f
       Index Cond: (f.route_no = r.route_no)  -- Только нужные flights, не все 21758!
```

**Ключевые улучшения:**
1. ❌ **Убрано**: Seq Scan на flights (421.58 cost)
2. ✅ **Добавлено**: Index Scan на flights через route_no
3. ✅ **Оптимизировано**: Порядок JOIN'ов начинается с самого селективного фильтра

## Оценка производительности

### Текущий план:
- **Seq Scan flights**: ~421 cost (сканирование 1.6 GB)
- **Hash Join**: ~950 total cost
- **Ожидаемое время**: зависит от скорости диска, но для 1.6 GB это может быть 100-500ms

### Улучшенный план:
- **Index Scan flights**: ~50-100 cost (только нужные строки)
- **Total cost**: ~150-200 (в 5-6 раз лучше)
- **Ожидаемое время**: 10-50ms (в 10 раз быстрее)

## Проверка текущих индексов

Выполните для диагностики:

```sql
-- Проверить индексы на flights
SELECT 
    indexname, 
    indexdef 
FROM pg_indexes 
WHERE tablename = 'flights' AND schemaname = 'bookings';

-- Проверить индексы на routes
SELECT 
    indexname, 
    indexdef 
FROM pg_indexes 
WHERE tablename = 'routes' AND schemaname = 'bookings';

-- Проверить индексы на seats
SELECT 
    indexname, 
    indexdef 
FROM pg_indexes 
WHERE tablename = 'seats' AND schemaname = 'bookings';
```

## Вывод

### ❌ Текущий план НЕ оптимален

**Основная проблема:** Seq Scan на всей таблице flights (1.6 GB) вместо использования индекса для поиска только нужных строк.

### ✅ Можно улучшить без изменения запроса

**Достаточно создать недостающие индексы:**
1. `CREATE INDEX idx_flights_route_no ON bookings.flights(route_no);` - **критично**
2. `CREATE INDEX idx_routes_airplane_code ON bookings.routes(airplane_code);` - желательно
3. Проверить/создать составной индекс на `seats(airplane_code, seat_no)` - если еще нет

**Ожидаемое улучшение:** 5-10x по производительности (с ~500ms до ~50ms)

