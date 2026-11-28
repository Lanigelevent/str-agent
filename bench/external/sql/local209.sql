WITH store_totals AS (
  SELECT store_id, COUNT(*) AS total_orders
  FROM orders
  GROUP BY store_id
),
delivered AS (
  SELECT o.store_id, COUNT(DISTINCT o.order_id) AS delivered_orders
  FROM orders o
  JOIN deliveries d ON d.delivery_order_id = o.order_id
  WHERE d.delivery_status = 'DELIVERED'
  GROUP BY o.store_id
)
SELECT st.store_id,
       s.store_name,
       st.total_orders,
       COALESCE(dl.delivered_orders, 0) AS delivered_orders,
       CAST(COALESCE(dl.delivered_orders, 0) AS REAL) / st.total_orders AS ratio
FROM store_totals st
JOIN stores s ON s.store_id = st.store_id
LEFT JOIN delivered dl ON dl.store_id = st.store_id
ORDER BY st.total_orders DESC, st.store_id ASC
LIMIT 1
