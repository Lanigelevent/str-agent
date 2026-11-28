WITH delivered AS (
  SELECT CAST(strftime('%Y', order_delivered_customer_date) AS INTEGER) AS yr,
         CAST(strftime('%m', order_delivered_customer_date) AS INTEGER) AS mon
  FROM olist_orders
  WHERE order_status = 'delivered'
    AND order_delivered_customer_date IS NOT NULL
    AND strftime('%Y', order_delivered_customer_date) IN ('2016', '2017', '2018')
),
annual AS (
  SELECT yr, COUNT(*) AS cnt FROM delivered GROUP BY yr
),
low_year AS (
  SELECT yr FROM annual ORDER BY cnt ASC, yr ASC LIMIT 1
)
SELECT MAX(monthly.cnt) AS highest_monthly_volume
FROM (SELECT mon, COUNT(*) AS cnt FROM delivered
      WHERE yr = (SELECT yr FROM low_year) GROUP BY mon) monthly
