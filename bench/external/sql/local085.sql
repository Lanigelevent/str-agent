WITH e AS (
  SELECT employeeid, COUNT(*) AS total_orders,
         SUM(CASE WHEN shippeddate IS NOT NULL AND requireddate IS NOT NULL
                   AND shippeddate >= requireddate THEN 1 ELSE 0 END) AS late_orders
  FROM orders
  GROUP BY employeeid
  HAVING COUNT(*) > 50
)
SELECT employeeid AS EMPLOYEE_ID,
       late_orders AS LATE_ORDERS,
       ROUND(late_orders*100.0/total_orders, 6) AS LATE_ORDER_PERCENTAGE
FROM e
ORDER BY LATE_ORDER_PERCENTAGE DESC
LIMIT 3;
