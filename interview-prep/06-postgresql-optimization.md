# PostgreSQL и оптимизация для собеседований

**Java/Kotlin Backend Developer | Middle/Senior**

## Индексы

### КЕЙС #1 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:** Когда использовать B-tree vs Hash vs GIN индексы? Приведите примеры.

**ОТВЕТ:**
**B-tree**: универсальный индекс (99% случаев)
**Hash**: только для точного равенства
**GIN/GiST**: для сложных типов (JSON, массивы, full-text search)

**ПОЧЕМУ ЭТО ВАЖНО:**
- Неправильный тип индекса → индекс не используется
- Hash быстрее B-tree для равенства, но менее универсален
- GIN нужен для JSONB queries

**ПРИМЕР КОДА:**
```sql
-- B-tree (по умолчанию): для сравнений, сортировки, LIKE 'prefix%'
CREATE INDEX idx_users_email ON users(email);  -- B-tree
SELECT * FROM users WHERE email = 'john@example.com';  -- Index Scan
SELECT * FROM users WHERE email LIKE 'john%';  -- Index Scan
SELECT * FROM users WHERE age > 18 ORDER BY age;  -- Index Scan
SELECT * FROM users WHERE age BETWEEN 20 AND 30;  -- Index Range Scan

-- Hash: ТОЛЬКО для точного равенства (=)
CREATE INDEX idx_users_hash ON users USING HASH (email);
SELECT * FROM users WHERE email = 'john@example.com';  -- Hash Index Scan (быстрее B-tree)
SELECT * FROM users WHERE email LIKE '%john%';  -- Seq Scan (индекс НЕ используется!)
SELECT * FROM users WHERE email > 'john@example.com';  -- Seq Scan (НЕ поддерживает)

-- GIN: для полнотекстового поиска, JSON, массивов
CREATE INDEX idx_products_tags ON products USING GIN (tags);
SELECT * FROM products WHERE tags @> ARRAY['electronics'];  -- Bitmap Index Scan
SELECT * FROM products WHERE tags && ARRAY['electronics', 'computers'];  -- Эффективно

-- GIN для JSONB
CREATE INDEX idx_orders_metadata ON orders USING GIN (metadata jsonb_path_ops);
SELECT * FROM orders WHERE metadata @> '{"status": "completed"}';  -- Bitmap Index Scan
SELECT * FROM orders WHERE metadata->>'source' = 'mobile';  -- Bitmap Index Scan

-- GiST: для геоданных, range types
CREATE INDEX idx_locations ON stores USING GIST (location);
SELECT * FROM stores WHERE ST_DWithin(location, ST_MakePoint(40.7, -74.0), 1000);  -- GIST Index Scan

-- Полнотекстовый поиск
CREATE INDEX idx_documents_search ON documents USING GIN (to_tsvector('english', content));
SELECT * FROM documents 
WHERE to_tsvector('english', content) @@ to_tsquery('english', 'postgresql & performance');

-- Составной индекс: порядок важен!
CREATE INDEX idx_orders_user_date ON orders(user_id, created_at);
-- Эффективен для:
SELECT * FROM orders WHERE user_id = 123;  -- ✅ Использует индекс
SELECT * FROM orders WHERE user_id = 123 AND created_at > '2026-01-01';  -- ✅ Использует полностью
-- НЕ эффективен для:
SELECT * FROM orders WHERE created_at > '2026-01-01';  -- ❌ created_at не первый в индексе

-- INCLUDE columns (covering index)
CREATE INDEX idx_users_email_include ON users(email) INCLUDE (name, age);
SELECT name, age FROM users WHERE email = 'john@example.com';  -- Index Only Scan!
-- Все данные берутся из индекса, без обращения к таблице
```
```kotlin
// Kotlin: работа с индексами через JPA
@Entity
@Table(
    name = "users",
    indexes = [
        Index(name = "idx_email", columnList = "email"),
        Index(name = "idx_age_name", columnList = "age, name"),
        Index(name = "idx_created_at", columnList = "created_at DESC")
    ]
)
data class User(
    @Id val id: Long,
    @Column(unique = true)
    val email: String,
    val name: String,
    val age: Int,
    val createdAt: LocalDateTime
)

// Проверка использования индекса
@Repository
interface UserRepository : JpaRepository<User, Long> {
    
    @Query(
        value = "EXPLAIN ANALYZE SELECT * FROM users WHERE email = :email",
        nativeQuery = true
    )
    fun explainFindByEmail(@Param("email") email: String): String
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #2 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
У вас есть запрос с WHERE clause по нескольким колонкам. Нужен один составной индекс 
или несколько одиночных? Как определить порядок колонок?

**ОТВЕТ:**
**Правило порядка колонок в составном индексе:**
1. Колонки с фильтрацией по равенству (=) — первыми
2. Колонки с range фильтрацией (>, <, BETWEEN) — в середине
3. Колонки для сортировки — последними

**ПОЧЕМУ ЭТО ВАЖНО:**
- Неправильный порядок → индекс не используется полностью
- Один хороший составной индекс лучше нескольких плохих одиночных

**ПРИМЕР КОДА:**
```sql
-- Запрос
SELECT * FROM orders 
WHERE user_id = 123           -- Фильтр по равенству
  AND status = 'PENDING'      -- Фильтр по равенству
  AND created_at > '2026-01-01'  -- Range фильтр
ORDER BY created_at DESC;

-- ПЛОХОЙ порядок
CREATE INDEX idx_bad ON orders(created_at, user_id, status);
-- PostgreSQL использует только created_at, остальные колонки бесполезны!

-- ХОРОШИЙ порядок
CREATE INDEX idx_good ON orders(user_id, status, created_at DESC);
-- user_id = 123 → узкий диапазон
-- status = 'PENDING' → ещё более узкий
-- created_at для сортировки

-- Проверка через EXPLAIN
EXPLAIN (ANALYZE, BUFFERS) 
SELECT * FROM orders 
WHERE user_id = 123 AND status = 'PENDING' AND created_at > '2026-01-01'
ORDER BY created_at DESC;

-- С плохим индексом:
-- Index Scan using idx_bad on orders  (cost=0.43..1500.00 rows=100)
--   Index Cond: (created_at > '2026-01-01')
--   Filter: (user_id = 123 AND status = 'PENDING')  ← ПЛОХО! Фильтр, а не Index Cond
--   Rows Removed by Filter: 50000  ← Много лишних строк

-- С хорошим индексом:
-- Index Scan using idx_good on orders  (cost=0.43..50.00 rows=100)
--   Index Cond: (user_id = 123 AND status = 'PENDING' AND created_at > '2026-01-01')
--   ← ВСЕ условия через индекс!

-- Partial index для часто используемых фильтров
CREATE INDEX idx_pending_orders ON orders(user_id, created_at) 
WHERE status = 'PENDING';

-- Индекс ТОЛЬКО для PENDING заказов (меньше размер, быстрее)
SELECT * FROM orders 
WHERE user_id = 123 AND status = 'PENDING'
ORDER BY created_at DESC;
-- Использует idx_pending_orders

-- Expression index
CREATE INDEX idx_lower_email ON users(LOWER(email));

SELECT * FROM users WHERE LOWER(email) = 'john@example.com';
-- Index Scan using idx_lower_email

-- Без expression index (НЕ использует обычный индекс на email):
SELECT * FROM users WHERE LOWER(email) = 'john@example.com';
-- Seq Scan (индекс на email не подходит для LOWER(email))
```
```kotlin
// JPA + индексы
@Entity
@Table(
    name = "orders",
    indexes = [
        Index(
            name = "idx_user_status_date",
            columnList = "user_id, status, created_at DESC"
        ),
        // Partial index через native query при создании
    ]
)
data class Order(
    @Id val id: Long,
    val userId: Long,
    
    @Enumerated(EnumType.STRING)
    val status: OrderStatus,
    
    val createdAt: LocalDateTime
)

// Partial index через migration
@Component
class PartialIndexMigration {
    
    @PostConstruct
    fun createPartialIndex() {
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_pending_orders 
            ON orders(user_id, created_at) 
            WHERE status = 'PENDING'
        """)
    }
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #3 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое Index Bloat в PostgreSQL? Как диагностировать и исправить?

**ОТВЕТ:**
**Index Bloat**: индекс содержит много "мёртвых" записей после UPDATE/DELETE.

**Причина**: PostgreSQL использует MVCC → старые версии строк остаются в индексе.

**ПОЧЕМУ ЭТО ВАЖНО:**
- Раздутые индексы занимают много места
- Медленнее поиск (больше страниц для чтения)
- Решение: REINDEX или pg_repack

**ПРИМЕР КОДА:**
```sql
-- Диагностика bloat
SELECT
    schemaname,
    tablename,
    indexname,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size,
    idx_scan AS index_scans,
    100 * (pg_relation_size(indexrelid) / NULLIF(pg_relation_size(relid), 0)) AS index_ratio
FROM pg_stat_user_indexes
JOIN pg_index USING (indexrelid)
WHERE idx_scan = 0  -- Индексы, которые не используются
ORDER BY pg_relation_size(indexrelid) DESC;

-- Проверка bloat процента
SELECT
    current_database(), 
    schemaname, 
    tablename,
    ROUND(CASE WHEN otta=0 THEN 0.0 ELSE sml.relpages/otta::numeric END, 1) AS tbloat,
    relpages AS actual_pages,
    otta AS expected_pages,
    pg_size_pretty(bs*(relpages-otta)::bigint) AS bloat_size
FROM (
    -- Сложный подзапрос для расчёта bloat
    SELECT 
        schemaname, tablename, 
        cc.relpages, 
        bs,
        CEIL((cc.reltuples*((datahdr+ma-
            (CASE WHEN datahdr%ma=0 THEN ma ELSE datahdr%ma END))+nullhdr2+4))/(bs-20::float)) AS otta
    FROM (
        SELECT
            ma, bs, schemaname, tablename,
            (datawidth+(hdr+ma-(CASE WHEN hdr%ma=0 THEN ma ELSE hdr%ma END)))::numeric AS datahdr,
            (maxfracsum*(nullhdr+ma-(CASE WHEN nullhdr%ma=0 THEN ma ELSE nullhdr%ma END))) AS nullhdr2
        FROM pg_stats s2
        -- ... полный запрос
    ) AS rs
) AS sml
WHERE relpages - otta > 128  -- Bloat больше 128 страниц (1MB)
ORDER BY bloat_size DESC;

-- ИСПРАВЛЕНИЕ 1: REINDEX (блокирует таблицу)
REINDEX INDEX idx_users_email;
-- Пересоздаёт индекс, удаляя мёртвые записи

-- REINDEX для всей таблицы
REINDEX TABLE users;

-- ПРОБЛЕМА: блокирует таблицу на запись!

-- ИСПРАВЛЕНИЕ 2: REINDEX CONCURRENTLY (PostgreSQL 12+)
REINDEX INDEX CONCURRENTLY idx_users_email;
-- НЕ блокирует таблицу, но работает дольше

-- ИСПРАВЛЕНИЕ 3: pg_repack (external tool)
-- Перестраивает таблицу БЕЗ блокировок
pg_repack -t users -d mydb

-- ПРОФИЛАКТИКА: автоматический VACUUM
ALTER TABLE users SET (autovacuum_vacuum_scale_factor = 0.05);
-- VACUUM будет запускаться чаще (при 5% изменений вместо 20%)

-- Мониторинг последнего VACUUM
SELECT 
    schemaname,
    relname,
    last_vacuum,
    last_autovacuum,
    n_dead_tup,  -- "Мёртвые" записи
    n_live_tup   -- "Живые" записи
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC;
```
```kotlin
// Kotlin: мониторинг bloat через Actuator
@Component
class DatabaseHealthIndicator(
    private val jdbcTemplate: JdbcTemplate
) : HealthIndicator {
    
    override fun health(): Health {
        val bloatInfo = jdbcTemplate.queryForList("""
            SELECT 
                tablename,
                pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size
            FROM pg_tables
            WHERE schemaname = 'public'
            ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC
            LIMIT 5
        """)
        
        return Health.up()
            .withDetail("largest_tables", bloatInfo)
            .build()
    }
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #2 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое Index Selectivity? Как определить, нужен ли индекс на колонку?

**ОТВЕТ:**
**Selectivity**: процент уникальных значений.
- Высокая selectivity (email, id) → индекс эффективен
- Низкая selectivity (gender, boolean) → индекс может быть бесполезен

**Формула**: `Selectivity = COUNT(DISTINCT column) / COUNT(*)`

**ПРИМЕР КОДА:**
```sql
-- Проверка selectivity
SELECT 
    COUNT(DISTINCT status)::float / COUNT(*) AS selectivity,
    COUNT(DISTINCT status) AS unique_values,
    COUNT(*) AS total_rows
FROM orders;

-- Результат:
-- selectivity | unique_values | total_rows
-- 0.000005    | 5             | 1000000
-- Очень низкая selectivity (5 статусов на миллион записей)

-- ПЛОХО: индекс на низкоселективной колонке
CREATE INDEX idx_orders_status ON orders(status);

-- Запрос
SELECT * FROM orders WHERE status = 'PENDING';
-- Если PENDING = 40% всех заказов → PostgreSQL НЕ использует индекс!
-- Seq Scan дешевле, чем прочитать 400,000 записей через индекс

EXPLAIN ANALYZE SELECT * FROM orders WHERE status = 'PENDING';
-- Seq Scan on orders  (cost=0.00..15000.00 rows=400000)
--   Filter: (status = 'PENDING')

-- ХОРОШО: partial index для редких значений
CREATE INDEX idx_orders_cancelled ON orders(id) WHERE status = 'CANCELLED';
-- Индекс ТОЛЬКО для CANCELLED (5% заказов) → эффективен

SELECT * FROM orders WHERE status = 'CANCELLED';
-- Index Scan using idx_orders_cancelled

-- Составной индекс с высокоселективной колонкой первой
CREATE INDEX idx_orders_user_status ON orders(user_id, status);
-- user_id — высокая selectivity (много пользователей)
-- status — низкая selectivity

SELECT * FROM orders WHERE user_id = 123 AND status = 'PENDING';
-- Index Scan — эффективно (user_id сужает диапазон)

-- Проверка использования индексов
SELECT 
    schemaname,
    tablename,
    indexname,
    idx_scan AS times_used,
    pg_size_pretty(pg_relation_size(indexrelid)) AS index_size
FROM pg_stat_user_indexes
WHERE idx_scan = 0  -- Индексы, которые НИКОГДА не использовались
  AND indexrelname NOT LIKE 'pg_toast%'
ORDER BY pg_relation_size(indexrelid) DESC;

-- Удаление неиспользуемых индексов
DROP INDEX CONCURRENTLY idx_unused_index;
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #3 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает VACUUM в PostgreSQL? Когда нужен VACUUM FULL?

**ОТВЕТ:**
**VACUUM**: удаляет "мёртвые" записи (dead tuples) после UPDATE/DELETE.

**MVCC в PostgreSQL:**
- UPDATE = INSERT новой версии +ометка старой как deleted
- DELETE = пометка как deleted
- Старые версии нужны для активных транзакций

**VACUUM освобождает** место для повторного использования.
**VACUUM FULL** полностью перестраивает таблицу (блокирует!).

**ПРИМЕР КОДА:**
```sql
-- Проверка мёртвых записей
SELECT 
    schemaname,
    relname,
    n_live_tup AS live_tuples,
    n_dead_tup AS dead_tuples,
    ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 2) AS dead_ratio,
    last_vacuum,
    last_autovacuum
FROM pg_stat_user_tables
WHERE n_dead_tup > 1000
ORDER BY n_dead_tup DESC;

-- Результат:
-- relname  | live_tuples | dead_tuples | dead_ratio | last_autovacuum
-- orders   | 1000000     | 500000      | 33.33      | 2026-01-28 10:00
-- Много мёртвых записей!

-- VACUUM: освобождает место для переиспользования
VACUUM orders;
-- Не возвращает место ОС, но доступно внутри таблицы

-- VACUUM VERBOSE: с детальным выводом
VACUUM VERBOSE orders;
-- INFO:  vacuuming "public.orders"
-- INFO:  "orders": removed 500000 row versions in 10000 pages
-- INFO:  "orders": found 500000 removable, 1000000 nonremovable row versions

-- VACUUM FULL: полная перестройка (БЛОКИРУЕТ таблицу!)
VACUUM FULL orders;
-- Копирует всю таблицу, удаляя мёртвые записи
-- Возвращает место ОС
-- ⚠️ БЛОКИРУЕТ таблицу на чтение и запись!

-- ANALYZE: обновляет статистику для планировщика
VACUUM ANALYZE orders;
-- VACUUM + обновление статистики

-- Настройка autovacuum
ALTER TABLE orders SET (
    autovacuum_vacuum_threshold = 1000,        -- Минимум мёртвых записей
    autovacuum_vacuum_scale_factor = 0.1,      -- 10% от размера таблицы
    autovacuum_analyze_threshold = 500,
    autovacuum_analyze_scale_factor = 0.05
);

-- Глобальная настройка (postgresql.conf)
autovacuum = on
autovacuum_max_workers = 3
autovacuum_naptime = 30s  -- Проверка каждые 30 сек

-- Мониторинг autovacuum
SELECT 
    pid,
    now() - query_start AS duration,
    query
FROM pg_stat_activity
WHERE query LIKE '%autovacuum%' AND query NOT LIKE '%pg_stat_activity%';
```
```kotlin
// Kotlin: scheduled VACUUM для критичных таблиц
@Component
class DatabaseMaintenanceScheduler(
    private val jdbcTemplate: JdbcTemplate
) {
    
    @Scheduled(cron = "0 0 2 * * *")  // Каждый день в 2:00
    fun vacuumCriticalTables() {
        val tables = listOf("orders", "order_items", "payments")
        
        tables.forEach { table ->
            logger.info("Starting VACUUM ANALYZE on $table")
            
            jdbcTemplate.execute("VACUUM ANALYZE $table")
            
            logger.info("VACUUM ANALYZE completed for $table")
        }
    }
}
```
───────────────────────────────────────────────────────────────────────────────

## EXPLAIN и анализ планов запросов

### КЕЙС #5 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:** Как читать EXPLAIN ANALYZE? Что такое Seq Scan vs Index Scan vs Bitmap Scan?

**ОТВЕТ:**
**Типы сканирования:**
- **Seq Scan**: последовательное чтение всей таблицы
- **Index Scan**: поиск через индекс (для малого кол-ва строк)
- **Index Only Scan**: все данные из индекса (covering index)
- **Bitmap Scan**: гибрид для среднего кол-ва строк

**ПОЧЕМУ ЭТО ВАЖНО:**
- Seq Scan на большой таблице = проблема производительности
- Index Scan для 50% таблицы медленнее Seq Scan
- Bitmap Scan оптимален для диапазонов

**ПРИМЕР КОДА:**
```sql
-- Seq Scan: читает всю таблицу (медленно на больших таблицах)
EXPLAIN ANALYZE SELECT * FROM users WHERE age > 18;
-- Seq Scan on users  (cost=0.00..2500.00 rows=100000 width=50)
--   (actual time=0.123..45.678 rows=90000 loops=1)
--   Planning Time: 0.234 ms
--   Execution Time: 46.123 ms

-- Index Scan: использует индекс (быстро для малого кол-ва строк)
CREATE INDEX idx_users_age ON users(age);
EXPLAIN ANALYZE SELECT * FROM users WHERE age = 25;
-- Index Scan using idx_users_age  (cost=0.43..12.47 rows=5 width=50)
--   Index Cond: (age = 25)
--   (actual time=0.023..0.045 rows=5 loops=1)

-- Index Only Scan: все данные из индекса (самый быстрый)
CREATE INDEX idx_users_email_name ON users(email) INCLUDE (name);
EXPLAIN ANALYZE SELECT email, name FROM users WHERE email = 'john@example.com';
-- Index Only Scan using idx_users_email_name  (cost=0.43..8.45 rows=1)
--   Index Cond: (email = 'john@example.com')
--   Heap Fetches: 0  ← Не обращается к таблице!

-- Bitmap Scan: для множества строк (средняя селективность)
EXPLAIN ANALYZE SELECT * FROM users WHERE age BETWEEN 20 AND 30;
-- Bitmap Heap Scan on users  (cost=4.50..1000.00 rows=50000 width=50)
--   Recheck Cond: (age >= 20 AND age <= 30)
--   Heap Blocks: exact=5000
--   -> Bitmap Index Scan on idx_users_age
--        Index Cond: (age >= 20 AND age <= 30)
--   (actual time=5.234..123.456 rows=48523 loops=1)

-- Ключевые метрики:
-- cost: оценка стоимости (startup_cost..total_cost)
--   startup_cost: время до первой строки
--   total_cost: время для всех строк
-- rows: ожидаемое количество строк
-- width: средний размер строки (байты)
-- actual time: реальное время (мс) — startup..total
-- loops: количество повторений (в join'ах может быть >1)

-- Nested Loop Join
EXPLAIN ANALYZE 
SELECT o.*, u.name 
FROM orders o 
JOIN users u ON o.user_id = u.id 
WHERE o.status = 'PENDING';

-- Nested Loop  (cost=0.86..15000.00 rows=1000 width=100)
--   (actual time=0.234..234.567 rows=1000 loops=1)
--   -> Seq Scan on orders o  (cost=0.00..5000.00 rows=1000)
--        Filter: (status = 'PENDING')
--   -> Index Scan using users_pkey on users u  (cost=0.43..8.45 rows=1)
--        Index Cond: (id = o.user_id)
--        (actual time=0.023..0.024 rows=1 loops=1000)  ← loops=1000!

-- Hash Join (для больших таблиц)
EXPLAIN ANALYZE 
SELECT o.*, u.name 
FROM orders o 
JOIN users u ON o.user_id = u.id;

-- Hash Join  (cost=5000.00..25000.00 rows=100000)
--   Hash Cond: (o.user_id = u.id)
--   -> Seq Scan on orders o  (cost=0.00..10000.00 rows=100000)
--   -> Hash  (cost=2500.00..2500.00 rows=50000)
--        Buckets: 65536  Batches: 1  Memory Usage: 4096kB
--        -> Seq Scan on users u  (cost=0.00..2500.00 rows=50000)

-- Buffers: информация об использовании памяти
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM users WHERE age > 18;
-- Seq Scan on users  (cost=0.00..2500.00 rows=100000)
--   Buffers: shared hit=1250 read=50
--   ← shared hit: из shared_buffers (кэш PostgreSQL)
--   ← read: с диска
```
```kotlin
// Kotlin: автоматический анализ медленных запросов
@Aspect
@Component
class SlowQueryAnalyzer {
    
    @Around("execution(* org.springframework.data.jpa.repository.JpaRepository+.*(..))")
    fun analyzeQuery(joinPoint: ProceedingJoinPoint): Any? {
        val start = System.currentTimeMillis()
        
        val result = joinPoint.proceed()
        
        val duration = System.currentTimeMillis() - start
        
        if (duration > 1000) {  // Медленнее 1 сек
            logger.warn("Slow query detected: ${joinPoint.signature.name} took ${duration}ms")
            
            // Можно автоматически запустить EXPLAIN
            explainLastQuery()
        }
        
        return result
    }
    
    private fun explainLastQuery() {
        // PostgreSQL: pg_stat_statements extension
        val slowQueries = jdbcTemplate.queryForList("""
            SELECT 
                query,
                calls,
                mean_exec_time,
                max_exec_time
            FROM pg_stat_statements
            ORDER BY mean_exec_time DESC
            LIMIT 10
        """)
        
        logger.info("Top 10 slow queries: $slowQueries")
    }
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #4 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое query planner statistics? Почему ANALYZE важен после bulk insert?

**ОТВЕТ:**
**Statistics**: информация о распределении данных (histogram, correlation).

PostgreSQL использует статистику для выбора плана запроса.

**Устаревшая статистика** → неоптимальный план → медленные запросы.

**ПРИМЕР КОДА:**
```sql
-- Проверка статистики для колонки
SELECT 
    tablename,
    attname,
    n_distinct,     -- Оценка уникальных значений
    correlation,    -- Корреляция с физическим порядком (-1..1)
    most_common_vals,
    most_common_freqs
FROM pg_stats
WHERE tablename = 'orders' AND attname = 'status';

-- Результат:
-- n_distinct | correlation | most_common_vals          | most_common_freqs
-- 5          | 0.95        | {PENDING,COMPLETED,...}   | {0.4, 0.35, ...}

-- ПЛОХАЯ статистика → плохой план
-- Загрузили 1 млн новых заказов через bulk insert
COPY orders FROM '/data/orders.csv' CSV;

-- Статистика не обновилась! PostgreSQL думает, что в таблице всё ещё 1000 строк
EXPLAIN SELECT * FROM orders WHERE status = 'PENDING';
-- Seq Scan (cost=0.00..20.00 rows=400)  ← НЕПРАВИЛЬНАЯ оценка!
-- На самом деле rows = 400,000

-- Обновление статистики
ANALYZE orders;

-- Теперь правильная оценка
EXPLAIN SELECT * FROM orders WHERE status = 'PENDING';
-- Seq Scan (cost=0.00..15000.00 rows=400000)  ← Правильно!

-- ANALYZE для всей БД
ANALYZE;

-- Auto-analyze настройка
ALTER TABLE orders SET (
    autovacuum_analyze_threshold = 500,
    autovacuum_analyze_scale_factor = 0.05  -- ANALYZE при 5% изменений
);

-- Проверка последнего ANALYZE
SELECT 
    schemaname,
    relname,
    last_analyze,
    last_autoanalyze,
    n_mod_since_analyze  -- Изменённых строк с последнего ANALYZE
FROM pg_stat_user_tables
WHERE n_mod_since_analyze > 10000
ORDER BY n_mod_since_analyze DESC;

-- Extended statistics (PostgreSQL 10+)
-- Для коррелированных колонок
CREATE STATISTICS stats_user_age_city ON age, city FROM users;

ANALYZE users;

-- PostgreSQL теперь знает корреляцию между age и city
-- План запроса будет точнее для:
SELECT * FROM users WHERE age > 25 AND city = 'Moscow';
```
───────────────────────────────────────────────────────────────────────────────

## Оптимизация запросов

### КЕЙС #8 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
У вас медленный JOIN двух больших таблиц. Как оптимизировать?

**ОТВЕТ:**
**Стратегии оптимизации JOIN:**
1. Индексы на FK колонках
2. Выбор типа JOIN (Nested Loop vs Hash vs Merge)
3. Фильтрация ПЕРЕД JOIN
4. Денормализация при необходимости

**ПРИМЕР КОДА:**
```sql
-- ПЛОХО: JOIN без индексов
SELECT o.*, u.name 
FROM orders o 
JOIN users u ON o.user_id = u.id
WHERE o.created_at > '2026-01-01';

EXPLAIN ANALYZE ...
-- Hash Join  (cost=50000..500000 rows=100000)
--   Hash Cond: (o.user_id = u.id)
--   -> Seq Scan on orders o  (cost=0.00..200000 rows=100000)
--        Filter: (created_at > '2026-01-01')
--   -> Hash  (cost=25000..25000 rows=1000000)
--        -> Seq Scan on users u  (cost=0.00..25000 rows=1000000)
-- Execution Time: 5000 ms  ← МЕДЛЕННО!

-- ХОРОШО: индексы + фильтрация
CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_created_at ON orders(created_at);
CREATE INDEX idx_users_pkey ON users(id);  -- Primary key (уже есть)

SELECT o.*, u.name 
FROM orders o 
JOIN users u ON o.user_id = u.id
WHERE o.created_at > '2026-01-01';

EXPLAIN ANALYZE ...
-- Nested Loop  (cost=0.86..1500.00 rows=100000)
--   -> Index Scan using idx_orders_created_at on orders o  (cost=0.43..800.00)
--        Index Cond: (created_at > '2026-01-01')
--   -> Index Scan using users_pkey on users u  (cost=0.43..8.45 rows=1)
--        Index Cond: (id = o.user_id)
-- Execution Time: 150 ms  ← В 30 раз быстрее!

-- CTE для сложных запросов
WITH recent_orders AS (
    SELECT * FROM orders 
    WHERE created_at > '2026-01-01'
      AND status = 'COMPLETED'
),
active_users AS (
    SELECT * FROM users 
    WHERE last_login > '2025-12-01'
)
SELECT o.*, u.name
FROM recent_orders o
JOIN active_users u ON o.user_id = u.id;

-- Materialized CTE (PostgreSQL 12+)
WITH orders_summary AS MATERIALIZED (
    SELECT 
        user_id,
        COUNT(*) as order_count,
        SUM(total) as total_spent
    FROM orders
    GROUP BY user_id
)
SELECT u.name, os.order_count, os.total_spent
FROM users u
JOIN orders_summary os ON u.id = os.user_id;
```
```kotlin
// Kotlin: оптимизация через DTO Projection
interface OrderWithUserProjection {
    val id: Long
    val total: BigDecimal
    val userName: String  // Из JOIN
}

@Repository
interface OrderRepository : JpaRepository<Order, Long> {
    
    // Проекция вместо полных Entity
    @Query("""
        SELECT o.id as id, o.total as total, u.name as userName
        FROM Order o
        JOIN o.user u
        WHERE o.createdAt > :date
    """)
    fun findRecentOrdersProjection(@Param("date") date: LocalDateTime): List<OrderWithUserProjection>
    
    // Загружается ТОЛЬКО нужные колонки, не вся таблица
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #9 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое query planning overhead? Как использовать prepared statements для ускорения?

**ОТВЕТ:**
**Planning overhead**: время на построение плана запроса.

**Проблема**: для каждого запроса PostgreSQL:
1. Парсит SQL (parsing)
2. Строит план выполнения (planning)
3. Выполняет (execution)

**Prepared statements** кэшируют план → пропускают шаги 1-2.

**ПРИМЕР КОДА:**
```sql
-- Обычный запрос (каждый раз planning)
SELECT * FROM users WHERE id = 123;  -- Planning Time: 0.5ms
SELECT * FROM users WHERE id = 456;  -- Planning Time: 0.5ms
-- 1000 запросов = 500ms на planning

-- Prepared Statement
PREPARE get_user (bigint) AS
    SELECT * FROM users WHERE id = $1;

EXECUTE get_user(123);  -- Planning Time: 0.5ms (первый раз)
EXECUTE get_user(456);  -- Planning Time: 0ms (план закэширован!)
EXECUTE get_user(789);  -- Planning Time: 0ms

-- Проверка кэшированных планов
SELECT * FROM pg_prepared_statements;

-- name    | statement                           | prepare_time         | from_sql
-- get_user| SELECT * FROM users WHERE id = $1   | 2026-01-29 10:00:00 | false

-- Deallocate: удалить prepared statement
DEALLOCATE get_user;
```
```kotlin
// Kotlin: JPA использует prepared statements автоматически!
@Repository
interface UserRepository : JpaRepository<User, Long> {
    
    fun findByEmail(email: String): User?
    // Hibernate кэширует план запроса
}

// JDBC: явный prepared statement
@Service
class UserService(private val dataSource: DataSource) {
    
    fun findById(id: Long): User? {
        dataSource.connection.use { conn ->
            // Prepared statement
            val ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?")
            ps.setLong(1, id)
            
            val rs = ps.executeQuery()
            return if (rs.next()) {
                User(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    email = rs.getString("email")
                )
            } else {
                null
            }
        }
    }
}

// Connection pooling + prepared statement cache
@Configuration
class DataSourceConfig {
    
    @Bean
    fun dataSource(): DataSource {
        val config = HikariConfig()
        config.jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
        config.maximumPoolSize = 20
        
        // Кэш prepared statements
        config.addDataSourceProperty("cachePrepStmts", "true")
        config.addDataSourceProperty("prepStmtCacheSize", "250")
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        
        return HikariDataSource(config)
    }
}
```
───────────────────────────────────────────────────────────────────────────────

## Пагинация

### КЕЙС #10 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:** Почему OFFSET медленный на больших таблицах? Как реализовать Keyset Pagination?

**ОТВЕТ:**
**OFFSET проблема**: PostgreSQL читает и отбрасывает N строк.

`OFFSET 100000 LIMIT 100` = прочитать 100,100 строк, вернуть последние 100.

**Keyset Pagination**: использует WHERE с последним значением → O(log N).

**ПОЧЕМУ ЭТО ВАЖНО:**
- OFFSET на странице 1000 = секунды
- Keyset Pagination на странице 1000 = миллисекунды
- Критично для больших таблиц

**ПРИМЕР КОДА:**
```kotlin
// ПЛОХО: OFFSET (O(N) сложность)
@Query("SELECT o FROM Order o ORDER BY o.id DESC")
fun findPage(pageable: Pageable): Page<Order>

val page1000 = repository.findPage(PageRequest.of(999, 100))
// SQL: SELECT * FROM orders ORDER BY id DESC OFFSET 99900 LIMIT 100
// PostgreSQL должен прочитать и отбросить 99900 строк!

EXPLAIN ANALYZE ...
-- Limit  (cost=20000.00..20010.00 rows=100)
--   -> Index Scan Backward using orders_pkey on orders  (cost=0.43..200000.00 rows=1000000)
-- Execution Time: 2500ms  ← МЕДЛЕННО!

// ХОРОШО: Keyset Pagination (O(log N))
@Query("SELECT o FROM Order o WHERE o.id < :lastId ORDER BY o.id DESC")
fun findNextPage(@Param("lastId") lastId: Long, pageable: Pageable): List<Order>

// Первая страница
val firstPage = repository.findAll(PageRequest.of(0, 100, Sort.by("id").descending()))

// Следующие страницы
val lastId = firstPage.last().id
val nextPage = repository.findNextPage(lastId, PageRequest.of(0, 100))
// SQL: SELECT * FROM orders WHERE id < 99900 ORDER BY id DESC LIMIT 100

EXPLAIN ANALYZE ...
-- Limit  (cost=0.43..10.00 rows=100)
--   -> Index Scan Backward using orders_pkey on orders  (cost=0.43..5000.00 rows=50000)
--        Index Cond: (id < 99900)
-- Execution Time: 5ms  ← В 500 раз быстрее!

// Keyset для сортировки по нескольким колонкам
data class PageCursor(
    val lastCreatedAt: LocalDateTime,
    val lastId: Long
)

@Repository
interface OrderRepository : JpaRepository<Order, Long> {
    
    @Query("""
        SELECT o FROM Order o
        WHERE (o.createdAt < :#{#cursor.lastCreatedAt})
           OR (o.createdAt = :#{#cursor.lastCreatedAt} AND o.id < :#{#cursor.lastId})
        ORDER BY o.createdAt DESC, o.id DESC
    """)
    fun findNextPage(
        @Param("cursor") cursor: PageCursor,
        pageable: Pageable
    ): List<Order>
}

// Индекс для составного keyset
CREATE INDEX idx_orders_created_id ON orders(created_at DESC, id DESC);

// Использование
val firstPage = orderRepository.findAll(
    PageRequest.of(0, 100, Sort.by("createdAt", "id").descending())
)

val lastOrder = firstPage.last()
val cursor = PageCursor(lastOrder.createdAt, lastOrder.id)

val nextPage = orderRepository.findNextPage(cursor, PageRequest.ofSize(100))

// REST API с курсором
@RestController
class OrderController {
    
    @GetMapping("/api/orders")
    fun getOrders(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "100") limit: Int
    ): PageResponse<OrderDto> {
        val page = if (cursor == null) {
            // Первая страница
            orderRepository.findAll(
                PageRequest.of(0, limit, Sort.by("id").descending())
            )
        } else {
            // Следующая страница
            val pageCursor = decodeCursor(cursor)
            orderRepository.findNextPage(pageCursor, PageRequest.ofSize(limit))
        }
        
        val nextCursor = if (page.isNotEmpty()) {
            encodeCursor(page.last())
        } else {
            null
        }
        
        return PageResponse(
            data = page.map { it.toDto() },
            nextCursor = nextCursor,
            hasMore = page.size == limit
        )
    }
    
    private fun encodeCursor(order: Order): String {
        val cursor = "${order.createdAt.toEpochSecond()}_${order.id}"
        return Base64.getEncoder().encodeToString(cursor.toByteArray())
    }
    
    private fun decodeCursor(encoded: String): PageCursor {
        val decoded = String(Base64.getDecoder().decode(encoded))
        val (timestamp, id) = decoded.split("_")
        return PageCursor(
            LocalDateTime.ofEpochSecond(timestamp.toLong(), 0, ZoneOffset.UTC),
            id.toLong()
        )
    }
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #11 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое query optimization с помощью CTE (WITH clause)? Recursive CTE?

**ОТВЕТ:**
**CTE (Common Table Expression)**: именованный подзапрос для читаемости.

**Materialized CTE**: вычисляется один раз и кэшируется.
**Recursive CTE**: для иерархических данных (деревья, графы).

**ПРИМЕР КОДА:**
```sql
-- CTE для читаемости
WITH active_users AS (
    SELECT * FROM users WHERE status = 'ACTIVE'
),
recent_orders AS (
    SELECT * FROM orders WHERE created_at > NOW() - INTERVAL '30 days'
)
SELECT 
    u.name,
    COUNT(o.id) as order_count,
    SUM(o.total) as total_spent
FROM active_users u
LEFT JOIN recent_orders o ON u.id = o.user_id
GROUP BY u.id, u.name
HAVING SUM(o.total) > 1000
ORDER BY total_spent DESC;

-- Materialized CTE (вычисляется один раз)
WITH order_stats AS MATERIALIZED (
    SELECT 
        user_id,
        COUNT(*) as order_count,
        AVG(total) as avg_total
    FROM orders
    GROUP BY user_id
    -- Может занять 5 секунд для миллиона заказов
)
SELECT u.*, os.order_count, os.avg_total
FROM users u
JOIN order_stats os ON u.id = os.user_id  -- Использует MATERIALIZED результат
WHERE u.status = 'PREMIUM'
UNION ALL
SELECT u.*, os.order_count, os.avg_total
FROM users u
JOIN order_stats os ON u.id = os.user_id  -- Переиспользует тот же результат!
WHERE u.created_at > '2026-01-01';
-- order_stats вычисляется ОДИН раз

-- Без MATERIALIZED:
-- CTE может быть "inlined" → вычисляется несколько раз

-- Recursive CTE: иерархия категорий
CREATE TABLE categories (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255),
    parent_id BIGINT REFERENCES categories(id)
);

-- Получить всю иерархию от корня
WITH RECURSIVE category_tree AS (
    -- Базовый случай: корневые категории
    SELECT id, name, parent_id, 0 as level, name as path
    FROM categories
    WHERE parent_id IS NULL
    
    UNION ALL
    
    -- Рекурсивный случай: дочерние категории
    SELECT c.id, c.name, c.parent_id, ct.level + 1, ct.path || ' > ' || c.name
    FROM categories c
    JOIN category_tree ct ON c.parent_id = ct.id
    WHERE ct.level < 10  -- Защита от бесконечной рекурсии
)
SELECT * FROM category_tree ORDER BY path;

-- Результат:
-- id | name        | level | path
-- 1  | Electronics | 0     | Electronics
-- 2  | Computers   | 1     | Electronics > Computers
-- 3  | Laptops     | 2     | Electronics > Computers > Laptops

-- Recursive CTE: граф зависимостей
WITH RECURSIVE task_dependencies AS (
    SELECT id, name, depends_on, 0 as depth
    FROM tasks
    WHERE id = 42  -- Начальная задача
    
    UNION ALL
    
    SELECT t.id, t.name, t.depends_on, td.depth + 1
    FROM tasks t
    JOIN task_dependencies td ON t.id = td.depends_on
)
SELECT * FROM task_dependencies;
```
```kotlin
// Kotlin: использование CTE через native query
@Repository
interface CategoryRepository : JpaRepository<Category, Long> {
    
    @Query(
        value = """
            WITH RECURSIVE category_tree AS (
                SELECT id, name, parent_id, 0 as level
                FROM categories
                WHERE id = :rootId
                
                UNION ALL
                
                SELECT c.id, c.name, c.parent_id, ct.level + 1
                FROM categories c
                JOIN category_tree ct ON c.parent_id = ct.id
            )
            SELECT * FROM category_tree
        """,
        nativeQuery = true
    )
    fun findCategoryTree(@Param("rootId") rootId: Long): List<Map<String, Any>>
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #12 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает DISTINCT ON в PostgreSQL? В чём отличие от GROUP BY?

**ОТВЕТ:**
**DISTINCT ON**: возвращает первую строку для каждого уникального значения.

**GROUP BY**: агрегация (COUNT, SUM, AVG).

**ПРИМЕР КОДА:**
```sql
-- Задача: получить последний заказ для каждого пользователя

-- ПЛОХО: subquery (медленно)
SELECT o.*
FROM orders o
WHERE o.id IN (
    SELECT MAX(id)
    FROM orders
    GROUP BY user_id
);
-- Выполняется subquery для всей таблицы, затем IN

-- ХОРОШО: DISTINCT ON (быстро)
SELECT DISTINCT ON (user_id) *
FROM orders
ORDER BY user_id, created_at DESC;
-- Возвращает ПЕРВУЮ строку для каждого user_id
-- (после сортировки по created_at DESC = последний заказ)

-- С дополнительной сортировкой
SELECT DISTINCT ON (user_id) 
    id, user_id, total, created_at
FROM orders
ORDER BY user_id, created_at DESC, id DESC;
-- DISTINCT ON колонка должна быть ПЕРВОЙ в ORDER BY!

-- Window functions (альтернатива)
SELECT * FROM (
    SELECT 
        *,
        ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at DESC) as rn
    FROM orders
) ranked
WHERE rn = 1;
-- Тот же результат, но ROW_NUMBER позволяет взять TOP N для каждого user

-- TOP 3 заказа для каждого пользователя
SELECT * FROM (
    SELECT 
        *,
        ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY total DESC) as rn
    FROM orders
) ranked
WHERE rn <= 3;

-- GROUP BY vs DISTINCT ON
-- GROUP BY: агрегация
SELECT user_id, COUNT(*) as order_count, SUM(total) as total_spent
FROM orders
GROUP BY user_id;

-- DISTINCT ON: первая строка
SELECT DISTINCT ON (user_id) user_id, total, created_at
FROM orders
ORDER BY user_id, created_at DESC;
```
```kotlin
// Kotlin: DISTINCT ON через native query
@Repository
interface OrderRepository : JpaRepository<Order, Long> {
    
    @Query(
        value = """
            SELECT DISTINCT ON (user_id) *
            FROM orders
            ORDER BY user_id, created_at DESC
        """,
        nativeQuery = true
    )
    fun findLastOrderForEachUser(): List<Order>
    
    // Window function для TOP N
    @Query(
        value = """
            SELECT * FROM (
                SELECT *,
                    ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY total DESC) as rn
                FROM orders
            ) ranked
            WHERE rn <= :limit
        """,
        nativeQuery = true
    )
    fun findTopOrdersForEachUser(@Param("limit") limit: Int): List<Order>
}
```
───────────────────────────────────────────────────────────────────────────────

## Блокировки и concurrency

### КЕЙС #13 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
В чём разница между SELECT FOR UPDATE, FOR SHARE, FOR UPDATE SKIP LOCKED?

**ОТВЕТ:**
**Pessimistic locking** в PostgreSQL:
- `FOR UPDATE`: эксклюзивная блокировка (другие ждут)
- `FOR SHARE`: shared блокировка (читать можно, писать нельзя)
- `FOR UPDATE SKIP LOCKED`: пропускает заблокированные строки
- `FOR UPDATE NOWAIT`: ошибка вместо ожидания

**ПРИМЕР КОДА:**
```sql
-- FOR UPDATE: эксклюзивная блокировка
BEGIN;
SELECT * FROM products WHERE id = 1 FOR UPDATE;
-- Другие транзакции будут ЖДАТЬ

UPDATE products SET stock = stock - 1 WHERE id = 1;
COMMIT;

-- FOR SHARE: shared lock (несколько читателей, 0 писателей)
BEGIN;
SELECT * FROM products WHERE id = 1 FOR SHARE;
-- Другие транзакции могут SELECT FOR SHARE
-- Но НЕ могут UPDATE (будут ждать)
COMMIT;

-- FOR UPDATE NOWAIT: не ждать, вернуть ошибку
BEGIN;
SELECT * FROM products WHERE id = 1 FOR UPDATE NOWAIT;
-- ERROR: could not obtain lock on row in relation "products"
ROLLBACK;

-- FOR UPDATE SKIP LOCKED: пропустить заблокированные (для очередей)
-- Транзакция 1:
BEGIN;
SELECT * FROM tasks 
WHERE status = 'PENDING' 
ORDER BY created_at 
LIMIT 1 
FOR UPDATE SKIP LOCKED;
-- Вернёт task_id=1

UPDATE tasks SET status = 'PROCESSING' WHERE id = 1;

-- Транзакция 2 (одновременно):
SELECT * FROM tasks 
WHERE status = 'PENDING' 
ORDER BY created_at 
LIMIT 1 
FOR UPDATE SKIP LOCKED;
-- Вернёт task_id=2 (пропустит task_id=1, который заблокирован!)

-- Без SKIP LOCKED → вторая транзакция ЖДАЛА БЫ первую
```
```kotlin
// Kotlin: pessimistic locking в JPA
@Repository
interface ProductRepository : JpaRepository<Product, Long> {
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)  // FOR UPDATE
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    fun findByIdWithLock(@Param("id") id: Long): Product?
    
    @Lock(LockModeType.PESSIMISTIC_READ)  // FOR SHARE
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    fun findByIdWithSharedLock(@Param("id") id: Long): Product?
}

// SKIP LOCKED для очереди задач
@Repository
interface TaskRepository : JpaRepository<Task, Long> {
    
    @Query(
        value = """
            SELECT * FROM tasks
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun pollNextTask(): Task?
}

@Service
class TaskWorker(
    private val taskRepository: TaskRepository
) {
    
    @Scheduled(fixedDelay = 1000)
    @Transactional
    fun processNextTask() {
        val task = taskRepository.pollNextTask() ?: return
        
        task.status = TaskStatus.PROCESSING
        taskRepository.save(task)
        
        try {
            executeTask(task)
            task.status = TaskStatus.COMPLETED
        } catch (e: Exception) {
            task.status = TaskStatus.FAILED
            task.error = e.message
        }
        
        taskRepository.save(task)
    }
}

// Несколько воркеров могут работать параллельно без конфликтов!
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #14 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое deadlock в PostgreSQL? Как диагностировать и предотвратить?

**ОТВЕТ:**
**Deadlock**: две транзакции ждут друг друга → взаимная блокировка.

PostgreSQL автоматически detect и отменяет одну транзакцию.

**Причина**: блокировка ресурсов в разном порядке.

**ПРИМЕР КОДА:**
```sql
-- DEADLOCK сценарий
-- Транзакция 1:
BEGIN;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;  -- Блокирует account 1
-- ... делает что-то ...
UPDATE accounts SET balance = balance + 100 WHERE id = 2;  -- Ждёт блокировку account 2

-- Транзакция 2 (одновременно):
BEGIN;
UPDATE accounts SET balance = balance - 50 WHERE id = 2;   -- Блокирует account 2
-- ... делает что-то ...
UPDATE accounts SET balance = balance + 50 WHERE id = 1;   -- Ждёт блокировку account 1

-- DEADLOCK! Обе транзакции ждут друг друга

-- PostgreSQL через ~1 секунду:
-- ERROR: deadlock detected
-- DETAIL: Process 12345 waits for ShareLock on transaction 67890;
--         blocked by process 12346.
--         Process 12346 waits for ShareLock on transaction 67889;
--         blocked by process 12345.

-- РЕШЕНИЕ: блокировать в одинаковом порядке
-- Транзакция 1:
BEGIN;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;  -- Сначала ID=1
UPDATE accounts SET balance = balance + 100 WHERE id = 2;  -- Потом ID=2
COMMIT;

-- Транзакция 2:
BEGIN;
UPDATE accounts SET balance = balance + 50 WHERE id = 1;   -- Сначала ID=1
UPDATE accounts SET balance = balance - 50 WHERE id = 2;   -- Потом ID=2
COMMIT;
-- Нет deadlock!

-- Или явная блокировка в начале
BEGIN;
SELECT * FROM accounts WHERE id IN (1, 2) ORDER BY id FOR UPDATE;
-- Блокируем ОБЕ строки сразу в отсортированном порядке

UPDATE accounts SET balance = balance - 100 WHERE id = 1;
UPDATE accounts SET balance = balance + 100 WHERE id = 2;
COMMIT;

-- Диагностика deadlock
SELECT 
    blocked_locks.pid AS blocked_pid,
    blocked_activity.usename AS blocked_user,
    blocking_locks.pid AS blocking_pid,
    blocking_activity.usename AS blocking_user,
    blocked_activity.query AS blocked_statement,
    blocking_activity.query AS blocking_statement
FROM pg_catalog.pg_locks blocked_locks
JOIN pg_catalog.pg_stat_activity blocked_activity ON blocked_activity.pid = blocked_locks.pid
JOIN pg_catalog.pg_locks blocking_locks 
    ON blocking_locks.locktype = blocked_locks.locktype
    AND blocking_locks.database IS NOT DISTINCT FROM blocked_locks.database
    AND blocking_locks.relation IS NOT DISTINCT FROM blocked_locks.relation
    AND blocking_locks.pid != blocked_locks.pid
JOIN pg_catalog.pg_stat_activity blocking_activity ON blocking_activity.pid = blocking_locks.pid
WHERE NOT blocked_locks.granted;

-- Мониторинг deadlock
SELECT * FROM pg_stat_database WHERE datname = 'mydb';
-- deadlocks | ...
-- 15        | ...  ← 15 deadlock'ов с момента старта БД

-- Логи deadlock (postgresql.conf)
log_lock_waits = on
deadlock_timeout = 1s
log_statement = 'all'
```
```kotlin
// Kotlin: обработка deadlock
@Service
class AccountService(
    private val accountRepository: AccountRepository
) {
    
    @Transactional
    fun transfer(fromId: Long, toId: Long, amount: BigDecimal, maxRetries: Int = 3) {
        repeat(maxRetries) { attempt ->
            try {
                // Блокируем в отсортированном порядке
                val (firstId, secondId) = if (fromId < toId) {
                    fromId to toId
                } else {
                    toId to fromId
                }
                
                val first = accountRepository.findByIdWithLock(firstId)!!
                val second = accountRepository.findByIdWithLock(secondId)!!
                
                val from = if (first.id == fromId) first else second
                val to = if (first.id == toId) first else second
                
                require(from.balance >= amount) { "Insufficient funds" }
                
                from.balance -= amount
                to.balance += amount
                
                accountRepository.saveAll(listOf(from, to))
                
                return  // Success
                
            } catch (e: CannotAcquireLockException) {
                if (attempt == maxRetries - 1) {
                    throw DeadlockException("Transfer failed after $maxRetries attempts", e)
                }
                
                logger.warn("Deadlock detected, retrying... (attempt ${attempt + 1})")
                Thread.sleep(Random.nextLong(50, 200))  // Random backoff
            }
        }
    }
}

@Repository
interface AccountRepository : JpaRepository<Account, Long> {
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    fun findByIdWithLock(@Param("id") id: Long): Account?
}
```
───────────────────────────────────────────────────────────────────────────────

## Партиционирование

### КЕЙС #15 | Уровень: Senior
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Что такое table partitioning в PostgreSQL? Когда использовать Range vs List vs Hash?

**ОТВЕТ:**
**Partitioning**: разделение большой таблицы на меньшие части (partitions).

**Типы:**
- **Range**: по диапазону (даты, числа)
- **List**: по списку значений (регионы, статусы)
- **Hash**: равномерное распределение

**ПОЧЕМУ ЭТО ВАЖНО:**
- Быстрые запросы (partition pruning)
- Упрощённое удаление старых данных (DROP partition)
- Параллельное сканирование

**ПРИМЕР КОДА:**
```sql
-- RANGE partitioning по дате (архив заказов)
CREATE TABLE orders (
    id BIGINT,
    user_id BIGINT,
    total NUMERIC,
    created_at TIMESTAMP,
    status VARCHAR(20)
) PARTITION BY RANGE (created_at);

-- Создание партиций
CREATE TABLE orders_2024 PARTITION OF orders
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');

CREATE TABLE orders_2025 PARTITION OF orders
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');

CREATE TABLE orders_2026 PARTITION OF orders
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');

-- Default partition для будущих данных
CREATE TABLE orders_future PARTITION OF orders DEFAULT;

-- Запрос использует ТОЛЬКО нужные партиции
SELECT * FROM orders WHERE created_at >= '2026-01-01';
-- Использует ТОЛЬКО orders_2026 и orders_future (partition pruning)
-- НЕ сканирует orders_2024, orders_2025

EXPLAIN SELECT * FROM orders WHERE created_at >= '2026-01-01';
-- Append  (cost=0.00..1000.00 rows=10000)
--   -> Seq Scan on orders_2026  (cost=0.00..500.00 rows=5000)
--        Filter: (created_at >= '2026-01-01')
--   -> Seq Scan on orders_future  (cost=0.00..500.00 rows=5000)
--        Filter: (created_at >= '2026-01-01')

-- Удаление старых данных (мгновенно!)
DROP TABLE orders_2024;
-- Вместо: DELETE FROM orders WHERE created_at < '2025-01-01' (часы на больших таблицах)

-- LIST partitioning по региону
CREATE TABLE users (
    id BIGINT,
    name VARCHAR(255),
    country VARCHAR(50)
) PARTITION BY LIST (country);

CREATE TABLE users_us PARTITION OF users
    FOR VALUES IN ('USA', 'Canada');

CREATE TABLE users_eu PARTITION OF users
    FOR VALUES IN ('Germany', 'France', 'UK');

CREATE TABLE users_asia PARTITION OF users
    FOR VALUES IN ('China', 'Japan', 'India');

-- HASH partitioning для равномерного распределения
CREATE TABLE events (
    id BIGINT,
    user_id BIGINT,
    event_type VARCHAR(50),
    created_at TIMESTAMP
) PARTITION BY HASH (user_id);

CREATE TABLE events_0 PARTITION OF events FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE events_1 PARTITION OF events FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE events_2 PARTITION OF events FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE events_3 PARTITION OF events FOR VALUES WITH (MODULUS 4, REMAINDER 3);

-- Индексы на партициях
CREATE INDEX idx_orders_2026_user ON orders_2026(user_id);
-- Или глобальный индекс (PostgreSQL 11+)
CREATE INDEX idx_orders_user_global ON orders(user_id);

-- Партиции создаются автоматически
CREATE INDEX idx_orders_2024_user_id_idx ON orders_2024 (user_id);
CREATE INDEX idx_orders_2025_user_id_idx ON orders_2025 (user_id);
```
```kotlin
// Kotlin: работа с партициями
@Service
class OrderPartitionManager(
    private val jdbcTemplate: JdbcTemplate
) {
    
    @Scheduled(cron = "0 0 1 1 * *")  // 1-го числа каждого месяца
    fun createNextMonthPartition() {
        val nextMonth = LocalDate.now().plusMonths(1)
        val partitionName = "orders_${nextMonth.year}_${nextMonth.monthValue}"
        val startDate = nextMonth.atStartOfDay()
        val endDate = nextMonth.plusMonths(1).atStartOfDay()
        
        val sql = """
            CREATE TABLE IF NOT EXISTS $partitionName PARTITION OF orders
            FOR VALUES FROM ('$startDate') TO ('$endDate')
        """
        
        jdbcTemplate.execute(sql)
        
        logger.info("Created partition: $partitionName")
    }
    
    @Scheduled(cron = "0 0 2 1 * *")  // Каждый месяц удаляем старые
    fun dropOldPartitions() {
        val oldDate = LocalDate.now().minusYears(2)
        val partitionName = "orders_${oldDate.year}_${oldDate.monthValue}"
        
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS $partitionName")
            logger.info("Dropped old partition: $partitionName")
        } catch (e: Exception) {
            logger.error("Failed to drop partition $partitionName", e)
        }
    }
}

// Query к партиционированной таблице (прозрачно)
@Repository
interface OrderRepository : JpaRepository<Order, Long> {
    
    fun findByCreatedAtBetween(start: LocalDateTime, end: LocalDateTime): List<Order>
    // PostgreSQL автоматически использует partition pruning
}
```
───────────────────────────────────────────────────────────────────────────────

### КЕЙС #16 | Уровень: Middle
───────────────────────────────────────────────────────────────────────────────
**ВОПРОС:**
Как работает connection pooling? Почему важно правильно настроить pool size?

**ОТВЕТ:**
**Connection Pool**: переиспользование подключений к БД.

**Проблемы:**
- Слишком мало подключений → ожидание
- Слишком много → overhead на PostgreSQL

**Формула**: `connections = ((core_count * 2) + effective_spindle_count)`

**ПРИМЕР КОДА:**
```kotlin
// HikariCP конфигурация
@Configuration
class DataSourceConfig {
    
    @Bean
    fun dataSource(): DataSource {
        val config = HikariConfig()
        
        config.jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
        config.username = "user"
        config.password = "password"
        
        // Pool размер
        config.maximumPoolSize = 20  // Макс подключений
        config.minimumIdle = 5       // Минимум idle connections
        
        // Timeouts
        config.connectionTimeout = 30000  // 30 сек на получение connection
        config.idleTimeout = 600000       // 10 мин idle → закрыть
        config.maxLifetime = 1800000      // 30 мин макс жизнь connection
        
        // Validation
        config.connectionTestQuery = "SELECT 1"
        config.validationTimeout = 5000
        
        // Leak detection
        config.leakDetectionThreshold = 60000  // 1 минута
        
        // Performance
        config.addDataSourceProperty("cachePrepStmts", "true")
        config.addDataSourceProperty("prepStmtCacheSize", "250")
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        config.addDataSourceProperty("useServerPrepStmts", "true")
        
        return HikariDataSource(config)
    }
}

// Мониторинг pool
@Component
class ConnectionPoolHealthIndicator(
    private val dataSource: DataSource
) : HealthIndicator {
    
    override fun health(): Health {
        val hikariDataSource = dataSource.unwrap(HikariDataSource::class.java)
        val poolStats = hikariDataSource.hikariPoolMXBean
        
        val activeConnections = poolStats.activeConnections
        val totalConnections = poolStats.totalConnections
        val idleConnections = poolStats.idleConnections
        val threadsAwaitingConnection = poolStats.threadsAwaitingConnection
        
        return if (threadsAwaitingConnection > 0) {
            Health.down()
                .withDetail("active", activeConnections)
                .withDetail("idle", idleConnections)
                .withDetail("awaiting", threadsAwaitingConnection)
                .withDetail("reason", "Pool exhausted")
                .build()
        } else {
            Health.up()
                .withDetail("active", activeConnections)
                .withDetail("idle", idleConnections)
                .withDetail("total", totalConnections)
                .build()
        }
    }
}

// PostgreSQL: мониторинг подключений
// SELECT 
//     count(*) as total_connections,
//     count(*) FILTER (WHERE state = 'active') as active,
//     count(*) FILTER (WHERE state = 'idle') as idle
// FROM pg_stat_activity;

// Проверка лимита подключений
// SHOW max_connections;  -- 100 (default)

// Настройка max_connections (postgresql.conf)
max_connections = 200

// Connection leak detection
@Aspect
@Component
class ConnectionLeakDetector {
    
    @Around("execution(* javax.sql.DataSource.getConnection(..))")
    fun detectLeak(joinPoint: ProceedingJoinPoint): Any? {
        val connection = joinPoint.proceed() as Connection
        val stackTrace = Thread.currentThread().stackTrace
        
        // Логируем где получили connection
        logger.debug("Connection acquired: ${stackTrace[2]}")
        
        return ConnectionWrapper(connection, stackTrace)
    }
}
```
───────────────────────────────────────────────────────────────────────────────

---

📊 **Модель**: Claude Sonnet 4.5 | **Кейсов**: 25 | **Стоимость**: ~$3.00

*Версия: 1.0 | Январь 2026*

