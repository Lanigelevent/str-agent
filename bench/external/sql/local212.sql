WITH drv AS (
  SELECT d.driver_id,
         COUNT(*) AS total_deliveries,
         COUNT(DISTINCT o.order_created_year || '-' || o.order_created_month || '-' || o.order_created_day) AS active_days
  FROM deliveries d
  JOIN orders o ON o.order_id = d.delivery_order_id
  WHERE d.driver_id IS NOT NULL
  GROUP BY d.driver_id
)
SELECT driver_id,
       CAST(total_deliveries AS REAL) / active_days AS avg_daily_deliveries
FROM drv
ORDER BY avg_daily_deliveries DESC, driver_id ASC
LIMIT 5
