WITH months(mon) AS (
  VALUES (1), (2), (3), (4), (5), (6), (7), (8), (9), (10), (11), (12)
),
delivered AS (
  SELECT CAST(strftime('%Y', order_delivered_customer_date) AS INTEGER) AS yr,
         CAST(strftime('%m', order_delivered_customer_date) AS INTEGER) AS mon
  FROM olist_orders
  WHERE order_status = 'delivered'
    AND order_delivered_customer_date IS NOT NULL
    AND strftime('%Y', order_delivered_customer_date) IN ('2016', '2017', '2018')
)
SELECT m.mon AS month,
       SUM(CASE WHEN d.yr = 2016 THEN 1 ELSE 0 END) AS "2016",
       SUM(CASE WHEN d.yr = 2017 THEN 1 ELSE 0 END) AS "2017",
       SUM(CASE WHEN d.yr = 2018 THEN 1 ELSE 0 END) AS "2018"
FROM months m
LEFT JOIN delivered d ON d.mon = m.mon
GROUP BY m.mon
ORDER BY m.mon
