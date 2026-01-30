# SQL задачи для собеседований

**Java/Kotlin Backend Developer | Middle/Senior**

## Базовые задачи

### ЗАДАЧА #1 | Уровень: Middle
**УСЛОВИЕ:** Дана таблица `employees` (id, name, salary, department_id). Найти сотрудников с зарплатой выше средней по их отделу.

**РЕШЕНИЕ:**
```sql
-- Вариант 1: подзапрос
SELECT e.id, e.name, e.salary, e.department_id
FROM employees e
WHERE e.salary > (
    SELECT AVG(e2.salary)
    FROM employees e2
    WHERE e2.department_id = e.department_id
);

-- Вариант 2: window function (эффективнее)
SELECT id, name, salary, department_id
FROM (
    SELECT 
        id,
        name,
        salary,
        department_id,
        AVG(salary) OVER (PARTITION BY department_id) as avg_dept_salary
    FROM employees
) subquery
WHERE salary > avg_dept_salary;
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Window functions часто эффективнее коррелированных подзапросов
- Понимание PARTITION BY критично для аналитических запросов

### ЗАДАЧА #2 | Уровень: Middle
**УСЛОВИЕ:** Таблицы `orders` (id, customer_id, order_date, amount) и `customers` (id, name). Найти топ-5 клиентов по сумме заказов за последние 30 дней.

**РЕШЕНИЕ:**
```sql
SELECT 
    c.id,
    c.name,
    COUNT(o.id) as order_count,
    SUM(o.amount) as total_amount
FROM customers c
INNER JOIN orders o ON c.id = o.customer_id
WHERE o.order_date >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY c.id, c.name
ORDER BY total_amount DESC
LIMIT 5;

-- С обработкой клиентов без заказов (LEFT JOIN)
SELECT 
    c.id,
    c.name,
    COALESCE(COUNT(o.id), 0) as order_count,
    COALESCE(SUM(o.amount), 0) as total_amount
FROM customers c
LEFT JOIN orders o ON c.id = o.customer_id 
    AND o.order_date >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY c.id, c.name
ORDER BY total_amount DESC
LIMIT 5;
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- JOIN + GROUP BY + агрегатные функции — основа аналитики
- COALESCE обрабатывает NULL значения
- INNER vs LEFT JOIN влияет на результат

### ЗАДАЧА #3 | Уровень: Senior
**УСЛОВИЕ:** Таблица `transactions` (id, user_id, amount, created_at). Найти пользователей, у которых было 3+ транзакции подряд с возрастающей суммой.

**РЕШЕНИЕ:**
```sql
WITH ranked_transactions AS (
    SELECT 
        user_id,
        amount,
        created_at,
        LAG(amount, 1) OVER (PARTITION BY user_id ORDER BY created_at) as prev_amount_1,
        LAG(amount, 2) OVER (PARTITION BY user_id ORDER BY created_at) as prev_amount_2,
        ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at) as rn
    FROM transactions
)
SELECT DISTINCT user_id
FROM ranked_transactions
WHERE rn >= 3
  AND amount > prev_amount_1
  AND prev_amount_1 > prev_amount_2;

-- Альтернативный подход: LEAD для проверки следующих значений
WITH transaction_sequences AS (
    SELECT 
        user_id,
        amount,
        created_at,
        LEAD(amount, 1) OVER (PARTITION BY user_id ORDER BY created_at) as next_amount_1,
        LEAD(amount, 2) OVER (PARTITION BY user_id ORDER BY created_at) as next_amount_2
    FROM transactions
)
SELECT DISTINCT user_id
FROM transaction_sequences
WHERE amount < next_amount_1
  AND next_amount_1 < next_amount_2;
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- LAG/LEAD позволяют сравнивать с соседними строками
- CTE делает запрос читабельным
- Проверка последовательностей — частая задача на собеседованиях

### ЗАДАЧА #4 | Уровень: Middle
**УСЛОВИЕ:** Таблица `products` (id, name, category, price). Найти самый дорогой и самый дешёвый товар в каждой категории.

**РЕШЕНИЕ:**
```sql
-- Вариант 1: window functions
WITH ranked_products AS (
    SELECT 
        id,
        name,
        category,
        price,
        ROW_NUMBER() OVER (PARTITION BY category ORDER BY price DESC) as rn_desc,
        ROW_NUMBER() OVER (PARTITION BY category ORDER BY price ASC) as rn_asc
    FROM products
)
SELECT 
    category,
    MAX(CASE WHEN rn_desc = 1 THEN name END) as most_expensive_product,
    MAX(CASE WHEN rn_desc = 1 THEN price END) as max_price,
    MAX(CASE WHEN rn_asc = 1 THEN name END) as cheapest_product,
    MAX(CASE WHEN rn_asc = 1 THEN price END) as min_price
FROM ranked_products
WHERE rn_desc = 1 OR rn_asc = 1
GROUP BY category;

-- Вариант 2: DISTINCT ON (PostgreSQL specific)
(
    SELECT DISTINCT ON (category)
        category,
        name as most_expensive_product,
        price as max_price,
        NULL::VARCHAR as cheapest_product,
        NULL::NUMERIC as min_price
    FROM products
    ORDER BY category, price DESC
)
UNION ALL
(
    SELECT DISTINCT ON (category)
        category,
        NULL::VARCHAR as most_expensive_product,
        NULL::NUMERIC as max_price,
        name as cheapest_product,
        price as min_price
    FROM products
    ORDER BY category, price ASC
);
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- ROW_NUMBER для ранжирования — частая задача
- DISTINCT ON — мощная фича PostgreSQL
- Комбинация CASE + aggregate для pivot

### ЗАДАЧА #5 | Уровень: Senior
**УСЛОВИЕ:** Таблица `orders` (id, customer_id, order_date, amount). Вычислить running total (накопительную сумму) для каждого клиента.

**РЕШЕНИЕ:**
```sql
SELECT 
    id,
    customer_id,
    order_date,
    amount,
    SUM(amount) OVER (
        PARTITION BY customer_id 
        ORDER BY order_date 
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) as running_total
FROM orders
ORDER BY customer_id, order_date;

-- Альтернатива (без явного указания frame):
SELECT 
    id,
    customer_id,
    order_date,
    amount,
    SUM(amount) OVER (PARTITION BY customer_id ORDER BY order_date) as running_total
FROM orders
ORDER BY customer_id, order_date;

-- С дополнительной аналитикой
SELECT 
    id,
    customer_id,
    order_date,
    amount,
    SUM(amount) OVER w as running_total,
    AVG(amount) OVER w as running_avg,
    COUNT(*) OVER w as order_number
FROM orders
WINDOW w AS (PARTITION BY customer_id ORDER BY order_date)
ORDER BY customer_id, order_date;
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Running total — базовая аналитическая функция
- Понимание window frame (ROWS vs RANGE)
- WINDOW clause для переиспользования определения окна

## Сложные JOIN

### ЗАДАЧА #6 | Уровень: Middle
**УСЛОВИЕ:** Таблицы `users` (id, name), `posts` (id, user_id, created_at), `comments` (id, post_id, user_id, created_at). Найти пользователей, которые написали пост, но никогда не комментировали чужие посты.

**РЕШЕНИЕ:**
```sql
SELECT DISTINCT u.id, u.name
FROM users u
INNER JOIN posts p ON u.id = p.user_id
WHERE NOT EXISTS (
    SELECT 1
    FROM comments c
    INNER JOIN posts p2 ON c.post_id = p2.id
    WHERE c.user_id = u.id
      AND p2.user_id != u.id  -- Чужой пост
);

-- Альтернатива через LEFT JOIN
SELECT DISTINCT u.id, u.name
FROM users u
INNER JOIN posts p ON u.id = p.user_id
LEFT JOIN comments c ON c.user_id = u.id
LEFT JOIN posts p2 ON c.post_id = p2.id AND p2.user_id != u.id
WHERE c.id IS NULL;

-- Через EXCEPT (PostgreSQL)
SELECT DISTINCT u.id, u.name
FROM users u
INNER JOIN posts p ON u.id = p.user_id
EXCEPT
SELECT DISTINCT u.id, u.name
FROM users u
INNER JOIN comments c ON u.id = c.user_id
INNER JOIN posts p ON c.post_id = p.id
WHERE p.user_id != u.id;
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- NOT EXISTS vs LEFT JOIN + IS NULL — разные performance характеристики
- EXCEPT для set operations
- Понимание коррелированных подзапросов

### ЗАДАЧА #7 | Уровень: Senior
**УСЛОВИЕ:** Таблица `employees` (id, name, manager_id). Вывести иерархию: для каждого сотрудника показать всех его подчинённых (прямых и косвенных).

**РЕШЕНИЕ:**
```sql
-- Recursive CTE для обхода дерева
WITH RECURSIVE employee_hierarchy AS (
    -- Базовый случай: начальники (manager_id IS NULL)
    SELECT 
        id,
        name,
        manager_id,
        name as hierarchy_path,
        0 as level
    FROM employees
    WHERE manager_id IS NULL
    
    UNION ALL
    
    -- Рекурсивный случай: подчинённые
    SELECT 
        e.id,
        e.name,
        e.manager_id,
        eh.hierarchy_path || ' -> ' || e.name as hierarchy_path,
        eh.level + 1 as level
    FROM employees e
    INNER JOIN employee_hierarchy eh ON e.manager_id = eh.id
)
SELECT 
    id,
    name,
    manager_id,
    hierarchy_path,
    level
FROM employee_hierarchy
ORDER BY hierarchy_path;

-- Найти всех подчинённых конкретного менеджера
WITH RECURSIVE subordinates AS (
    SELECT id, name, manager_id, 0 as level
    FROM employees
    WHERE id = :manager_id  -- ID менеджера
    
    UNION ALL
    
    SELECT e.id, e.name, e.manager_id, s.level + 1
    FROM employees e
    INNER JOIN subordinates s ON e.manager_id = s.id
)
SELECT * FROM subordinates WHERE id != :manager_id;
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Recursive CTE для иерархических данных
- Обход графов в SQL
- Частая задача: organizational charts, категории товаров

### ЗАДАЧА #8 | Уровень: Senior
**УСЛОВИЕ:** Таблицы `products` (id, name), `orders` (id, product_id, quantity, order_date). Найти пары товаров, которые часто покупают вместе (в одном заказе).

**РЕШЕНИЕ:**
```sql
-- Self-join для поиска пар товаров в одном заказе
SELECT 
    p1.name as product1,
    p2.name as product2,
    COUNT(DISTINCT o1.id) as times_bought_together
FROM orders o1
INNER JOIN orders o2 ON o1.id = o2.id AND o1.product_id < o2.product_id
INNER JOIN products p1 ON o1.product_id = p1.id
INNER JOIN products p2 ON o2.product_id = p2.id
GROUP BY p1.id, p1.name, p2.id, p2.name
HAVING COUNT(DISTINCT o1.id) >= 10  -- Минимум 10 раз вместе
ORDER BY times_bought_together DESC
LIMIT 20;

-- С расчётом % от общего количества покупок каждого товара
WITH product_pairs AS (
    SELECT 
        o1.product_id as product1_id,
        o2.product_id as product2_id,
        COUNT(DISTINCT o1.id) as pair_count
    FROM orders o1
    INNER JOIN orders o2 ON o1.id = o2.id AND o1.product_id < o2.product_id
    GROUP BY o1.product_id, o2.product_id
),
product_totals AS (
    SELECT 
        product_id,
        COUNT(DISTINCT id) as total_orders
    FROM orders
    GROUP BY product_id
)
SELECT 
    p1.name as product1,
    p2.name as product2,
    pp.pair_count,
    ROUND(100.0 * pp.pair_count / pt1.total_orders, 2) as pct_of_product1,
    ROUND(100.0 * pp.pair_count / pt2.total_orders, 2) as pct_of_product2
FROM product_pairs pp
INNER JOIN products p1 ON pp.product1_id = p1.id
INNER JOIN products p2 ON pp.product2_id = p2.id
INNER JOIN product_totals pt1 ON pp.product1_id = pt1.product_id
INNER JOIN product_totals pt2 ON pp.product2_id = pt2.product_id
WHERE pp.pair_count >= 10
ORDER BY pp.pair_count DESC
LIMIT 20;
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Self-join для поиска связей внутри таблицы
- Market basket analysis
- o1.product_id < o2.product_id избегает дубликатов пар

## Оптимизация запросов

### ЗАДАЧА #9 | Уровень: Senior
**УСЛОВИЕ:** Запрос медленный. Таблица `orders` (50M строк). Оптимизировать:
```sql
SELECT o.id, o.customer_id, c.name, o.amount
FROM orders o
JOIN customers c ON o.customer_id = c.id
WHERE o.order_date >= '2025-01-01'
  AND o.status IN ('PENDING', 'PROCESSING')
ORDER BY o.created_at DESC
LIMIT 100;
```

**РЕШЕНИЕ:**
```sql
-- Шаг 1: Анализируем план выполнения
EXPLAIN ANALYZE
SELECT o.id, o.customer_id, c.name, o.amount
FROM orders o
JOIN customers c ON o.customer_id = c.id
WHERE o.order_date >= '2025-01-01'
  AND o.status IN ('PENDING', 'PROCESSING')
ORDER BY o.created_at DESC
LIMIT 100;

-- Шаг 2: Создаём индексы
CREATE INDEX idx_orders_status_date_created 
ON orders(status, order_date, created_at DESC);

-- Composite index для WHERE + ORDER BY
-- Порядок колонок важен: status (фильтр) → order_date (фильтр) → created_at (сортировка)

CREATE INDEX idx_orders_customer_id ON orders(customer_id);
-- Для JOIN с customers

-- Шаг 3: Переписываем запрос (если нужно)
-- Вариант 1: избегаем сортировки, если индекс уже упорядочен
SELECT o.id, o.customer_id, c.name, o.amount
FROM orders o
JOIN customers c ON o.customer_id = c.id
WHERE o.status IN ('PENDING', 'PROCESSING')
  AND o.order_date >= '2025-01-01'
ORDER BY o.created_at DESC
LIMIT 100;

-- Вариант 2: если customer data редко меняется — денормализация
-- Добавляем customer_name в orders
SELECT o.id, o.customer_id, o.customer_name, o.amount
FROM orders o
WHERE o.status IN ('PENDING', 'PROCESSING')
  AND o.order_date >= '2025-01-01'
ORDER BY o.created_at DESC
LIMIT 100;
-- Избегаем JOIN

-- Вариант 3: партиционирование по order_date
CREATE TABLE orders_2025_01 PARTITION OF orders
FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

-- Запрос автоматически будет сканировать только нужную партицию
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- EXPLAIN ANALYZE — первый шаг оптимизации
- Правильный порядок колонок в composite index критичен
- Денормализация vs нормализация — trade-off
- Партиционирование для больших таблиц

### ЗАДАЧА #10 | Уровень: Senior
**УСЛОВИЕ:** Оптимизировать подсчёт активных пользователей за каждый день последнего месяца. Таблица `user_activities` (user_id, activity_date, action_type) — 1B строк.

**РЕШЕНИЕ:**
```sql
-- ❌ ПЛОХО: медленный GROUP BY на огромной таблице
SELECT 
    activity_date,
    COUNT(DISTINCT user_id) as active_users
FROM user_activities
WHERE activity_date >= CURRENT_DATE - INTERVAL '30 days'
GROUP BY activity_date
ORDER BY activity_date;

-- ✅ ХОРОШО: материализованное представление с инкрементальным обновлением
CREATE MATERIALIZED VIEW daily_active_users AS
SELECT 
    activity_date,
    COUNT(DISTINCT user_id) as active_users
FROM user_activities
GROUP BY activity_date;

CREATE UNIQUE INDEX ON daily_active_users(activity_date);

-- Обновление только новых данных (ежедневно)
REFRESH MATERIALIZED VIEW CONCURRENTLY daily_active_users;

-- Запрос теперь быстрый
SELECT *
FROM daily_active_users
WHERE activity_date >= CURRENT_DATE - INTERVAL '30 days'
ORDER BY activity_date;

-- ✅ Ещё лучше: отдельная агрегационная таблица
CREATE TABLE daily_user_stats (
    activity_date DATE PRIMARY KEY,
    active_users INT,
    new_users INT,
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Инкрементальное обновление через scheduled job
INSERT INTO daily_user_stats (activity_date, active_users, new_users)
SELECT 
    CURRENT_DATE - 1 as activity_date,
    COUNT(DISTINCT user_id) as active_users,
    COUNT(DISTINCT CASE WHEN is_first_activity THEN user_id END) as new_users
FROM user_activities
WHERE activity_date = CURRENT_DATE - 1
ON CONFLICT (activity_date) DO UPDATE
SET active_users = EXCLUDED.active_users,
    new_users = EXCLUDED.new_users,
    updated_at = NOW();

-- ✅ HyperLogLog для approximate COUNT DISTINCT (PostgreSQL)
CREATE EXTENSION IF NOT EXISTS hll;

ALTER TABLE daily_user_stats ADD COLUMN user_hll hll;

-- Более эффективное хранение для COUNT DISTINCT
UPDATE daily_user_stats
SET user_hll = (
    SELECT hll_add_agg(hll_hash_integer(user_id))
    FROM user_activities
    WHERE activity_date = daily_user_stats.activity_date
);

-- Быстрый approximate count
SELECT 
    activity_date,
    hll_cardinality(user_hll)::bigint as active_users_approx
FROM daily_user_stats
WHERE activity_date >= CURRENT_DATE - INTERVAL '30 days';
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- COUNT DISTINCT на больших таблицах медленный
- Материализованные представления для агрегаций
- Инкрементальные обновления вместо полного пересчёта
- HyperLogLog для approximate counting

## Сложная аналитика

### ЗАДАЧА #11 | Уровень: Senior
**УСЛОВИЕ:** Таблица `sales` (id, product_id, sale_date, amount). Найти месяцы, в которых продажи выросли минимум на 20% по сравнению с предыдущим месяцем.

**РЕШЕНИЕ:**
```sql
WITH monthly_sales AS (
    SELECT 
        DATE_TRUNC('month', sale_date) as month,
        SUM(amount) as total_sales
    FROM sales
    GROUP BY DATE_TRUNC('month', sale_date)
),
sales_with_prev AS (
    SELECT 
        month,
        total_sales,
        LAG(total_sales) OVER (ORDER BY month) as prev_month_sales
    FROM monthly_sales
)
SELECT 
    month,
    total_sales,
    prev_month_sales,
    ROUND(100.0 * (total_sales - prev_month_sales) / prev_month_sales, 2) as growth_pct
FROM sales_with_prev
WHERE prev_month_sales IS NOT NULL
  AND total_sales >= prev_month_sales * 1.2
ORDER BY month;

-- С дополнительной аналитикой: YoY (Year over Year)
WITH monthly_sales AS (
    SELECT 
        DATE_TRUNC('month', sale_date) as month,
        SUM(amount) as total_sales
    FROM sales
    GROUP BY DATE_TRUNC('month', sale_date)
)
SELECT 
    month,
    total_sales,
    LAG(total_sales, 1) OVER (ORDER BY month) as prev_month_sales,
    LAG(total_sales, 12) OVER (ORDER BY month) as same_month_last_year_sales,
    ROUND(100.0 * (total_sales - LAG(total_sales, 1) OVER (ORDER BY month)) 
        / LAG(total_sales, 1) OVER (ORDER BY month), 2) as mom_growth_pct,
    ROUND(100.0 * (total_sales - LAG(total_sales, 12) OVER (ORDER BY month)) 
        / LAG(total_sales, 12) OVER (ORDER BY month), 2) as yoy_growth_pct
FROM monthly_sales
ORDER BY month;
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- LAG для сравнения с предыдущими периодами
- MoM (Month over Month) и YoY (Year over Year) — стандартная бизнес-аналитика
- DATE_TRUNC для группировки по периодам

### ЗАДАЧА #12 | Уровень: Senior
**УСЛОВИЕ:** Таблица `events` (user_id, event_type, event_time). Найти пользователей, у которых между событиями 'page_view' и 'purchase' прошло менее 5 минут.

**РЕШЕНИЕ:**
```sql
WITH page_views AS (
    SELECT user_id, event_time as page_view_time
    FROM events
    WHERE event_type = 'page_view'
),
purchases AS (
    SELECT user_id, event_time as purchase_time
    FROM events
    WHERE event_type = 'purchase'
)
SELECT DISTINCT pv.user_id
FROM page_views pv
INNER JOIN purchases p ON pv.user_id = p.user_id
WHERE p.purchase_time > pv.page_view_time
  AND p.purchase_time <= pv.page_view_time + INTERVAL '5 minutes';

-- Вариант 2: через LEAD (если нужна последовательность событий)
WITH ordered_events AS (
    SELECT 
        user_id,
        event_type,
        event_time,
        LEAD(event_type) OVER (PARTITION BY user_id ORDER BY event_time) as next_event_type,
        LEAD(event_time) OVER (PARTITION BY user_id ORDER BY event_time) as next_event_time
    FROM events
)
SELECT DISTINCT user_id
FROM ordered_events
WHERE event_type = 'page_view'
  AND next_event_type = 'purchase'
  AND next_event_time <= event_time + INTERVAL '5 minutes';

-- Вариант 3: для сложных последовательностей (funnel analysis)
WITH event_sequences AS (
    SELECT 
        user_id,
        event_time,
        event_type,
        LAG(event_type) OVER (PARTITION BY user_id ORDER BY event_time) as prev_event,
        LAG(event_time) OVER (PARTITION BY user_id ORDER BY event_time) as prev_event_time
    FROM events
)
SELECT 
    user_id,
    event_time as purchase_time,
    prev_event_time as page_view_time,
    EXTRACT(EPOCH FROM (event_time - prev_event_time)) / 60 as minutes_between
FROM event_sequences
WHERE event_type = 'purchase'
  AND prev_event = 'page_view'
  AND event_time <= prev_event_time + INTERVAL '5 minutes';
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Funnel analysis — критичный навык для продуктовой аналитики
- LEAD/LAG для анализа последовательностей
- Интервалы времени в PostgreSQL

### ЗАДАЧА #13 | Уровень: Middle
**УСЛОВИЕ:** Таблица `products` (id, name, price). Найти медианную цену.

**РЕШЕНИЕ:**
```sql
-- Вариант 1: PERCENTILE_CONT (PostgreSQL, SQL Standard)
SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY price) as median_price
FROM products;

-- Вариант 2: через ROW_NUMBER (работает везде)
WITH ranked_products AS (
    SELECT 
        price,
        ROW_NUMBER() OVER (ORDER BY price) as rn,
        COUNT(*) OVER () as total_count
    FROM products
)
SELECT AVG(price) as median_price
FROM ranked_products
WHERE rn IN (
    (total_count + 1) / 2,  -- Нечётное количество
    (total_count + 2) / 2   -- Чётное количество (усредняем два средних)
);

-- Вариант 3: с группировкой по категориям
SELECT 
    category,
    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY price) as median_price,
    PERCENTILE_CONT(0.25) WITHIN GROUP (ORDER BY price) as q1,
    PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY price) as q3
FROM products
GROUP BY category;
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Медиана устойчивее к выбросам, чем среднее
- PERCENTILE_CONT — ordered-set aggregate
- Квартили (Q1, Q3) для выявления выбросов

## Транзакции и блокировки

### ЗАДАЧА #14 | Уровень: Senior
**УСЛОВИЕ:** Реализовать atomic decrement для inventory. Таблица `inventory` (product_id, quantity). Уменьшить quantity, но только если >= requested_quantity.

**РЕШЕНИЕ:**
```sql
-- Вариант 1: UPDATE с WHERE (атомарная операция)
UPDATE inventory
SET quantity = quantity - :requested_quantity
WHERE product_id = :product_id
  AND quantity >= :requested_quantity
RETURNING quantity;

-- Если UPDATE вернул 0 строк → недостаточно товара

-- Вариант 2: SELECT FOR UPDATE (пессимистичная блокировка)
BEGIN;

SELECT quantity
FROM inventory
WHERE product_id = :product_id
FOR UPDATE;  -- Блокируем строку

-- В приложении проверяем quantity >= requested_quantity
-- Если достаточно:
UPDATE inventory
SET quantity = quantity - :requested_quantity
WHERE product_id = :product_id;

COMMIT;

-- Вариант 3: CTE с RETURNING для одного запроса
WITH inventory_check AS (
    SELECT product_id, quantity
    FROM inventory
    WHERE product_id = :product_id
    FOR UPDATE
),
inventory_update AS (
    UPDATE inventory
    SET quantity = quantity - :requested_quantity
    WHERE product_id = :product_id
      AND quantity >= :requested_quantity
    RETURNING product_id, quantity
)
SELECT 
    CASE 
        WHEN iu.product_id IS NOT NULL THEN 'SUCCESS'
        ELSE 'INSUFFICIENT_STOCK'
    END as status,
    COALESCE(iu.quantity, ic.quantity) as current_quantity
FROM inventory_check ic
LEFT JOIN inventory_update iu ON ic.product_id = iu.product_id;

-- Kotlin код
@Transactional
fun reserveStock(productId: Long, quantity: Int): ReservationResult {
    val updated = jdbcTemplate.update(
        """
        UPDATE inventory
        SET quantity = quantity - ?
        WHERE product_id = ?
          AND quantity >= ?
        """,
        quantity, productId, quantity
    )
    
    return if (updated > 0) {
        ReservationResult.Success
    } else {
        ReservationResult.InsufficientStock
    }
}
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Race condition при конкурентных UPDATE
- UPDATE с WHERE — атомарная операция (не нужен SELECT)
- FOR UPDATE для сложной логики с проверками

### ЗАДАЧА #15 | Уровень: Middle
**УСЛОВИЕ:** Таблица `accounts` (id, balance). Реализовать transfer между двумя аккаунтами без deadlock.

**РЕШЕНИЕ:**
```sql
-- ❌ ПРОБЛЕМА: возможен deadlock
-- Transaction 1: блокирует account_id=1, ждёт account_id=2
-- Transaction 2: блокирует account_id=2, ждёт account_id=1

-- ✅ РЕШЕНИЕ: всегда блокируем в одном порядке (по возрастанию id)
BEGIN;

-- Блокируем оба аккаунта в порядке возрастания id
SELECT id, balance
FROM accounts
WHERE id IN (:from_account_id, :to_account_id)
ORDER BY id  -- ВАЖНО: всегда в одном порядке
FOR UPDATE;

-- Проверяем баланс
-- (в приложении)

-- Списываем
UPDATE accounts
SET balance = balance - :amount
WHERE id = :from_account_id;

-- Зачисляем
UPDATE accounts
SET balance = balance + :amount
WHERE id = :to_account_id;

COMMIT;

-- Kotlin реализация
@Transactional
fun transfer(fromAccountId: Long, toAccountId: Long, amount: BigDecimal) {
    // Блокируем в порядке возрастания
    val (firstId, secondId) = if (fromAccountId < toAccountId) {
        fromAccountId to toAccountId
    } else {
        toAccountId to fromAccountId
    }
    
    val accounts = jdbcTemplate.query(
        "SELECT id, balance FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
        { rs, _ -> rs.getLong("id") to rs.getBigDecimal("balance") },
        firstId, secondId
    ).toMap()
    
    val fromBalance = accounts[fromAccountId] ?: throw AccountNotFoundException()
    
    if (fromBalance < amount) {
        throw InsufficientFundsException()
    }
    
    jdbcTemplate.update(
        "UPDATE accounts SET balance = balance - ? WHERE id = ?",
        amount, fromAccountId
    )
    
    jdbcTemplate.update(
        "UPDATE accounts SET balance = balance + ? WHERE id = ?",
        amount, toAccountId
    )
}
```

**ПОЧЕМУ ЭТО ВАЖНО:**
- Deadlock — частая проблема в конкурентных системах
- Блокировка в одном порядке предотвращает deadlock
- FOR UPDATE + ORDER BY критично для корректности

---

📊 **Модель**: Claude Sonnet 4.5 | **Задач**: 15 | **Стоимость**: ~$0.90

*Версия: 1.0 | Январь 2026*

