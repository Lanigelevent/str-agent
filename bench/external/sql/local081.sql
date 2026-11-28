WITH spend AS (
  SELECT o.customerid, SUM(od.unitprice*od.quantity) AS total
  FROM orders o JOIN order_details od ON od.orderid=o.orderid
  WHERE o.orderdate LIKE '1998%'
  GROUP BY o.customerid
), grouped AS (
  SELECT s.customerid, t.groupname
  FROM spend s
  JOIN customergroupthreshold t
    ON s.total >= t.rangebottom AND s.total <= t.rangetop
), total_customers AS (
  SELECT COUNT(DISTINCT customerid) AS n FROM orders WHERE orderdate LIKE '1998%'
)
SELECT g.groupname,
       COUNT(*) AS CUSTOMER_COUNT,
       ROUND(COUNT(*)*100.0/(SELECT n FROM total_customers), 2) AS PERCENTAGE
FROM grouped g
GROUP BY g.groupname
ORDER BY CUSTOMER_COUNT DESC;
