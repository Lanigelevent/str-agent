WITH delivered AS (
  SELECT c.customer_unique_id, o.order_id
  FROM olist_orders o
  JOIN olist_customers c ON c.customer_id = o.customer_id
  WHERE o.order_status = 'delivered'
),
per_cust AS (
  SELECT customer_unique_id, COUNT(DISTINCT order_id) AS delivered_orders
  FROM delivered
  GROUP BY customer_unique_id
),
pay AS (
  SELECT d.customer_unique_id, SUM(p.payment_value) AS total_payment
  FROM (SELECT DISTINCT customer_unique_id, order_id FROM delivered) d
  JOIN olist_order_payments p ON p.order_id = d.order_id
  GROUP BY d.customer_unique_id
)
SELECT pc.customer_unique_id,
       pc.delivered_orders,
       pay.total_payment / pc.delivered_orders AS avg_payment_value
FROM per_cust pc
LEFT JOIN pay ON pay.customer_unique_id = pc.customer_unique_id
ORDER BY pc.delivered_orders DESC, pc.customer_unique_id ASC
LIMIT 3
