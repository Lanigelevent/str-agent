WITH countries AS (
  SELECT Country FROM customers GROUP BY Country HAVING COUNT(*) > 4
), totals AS (
  SELECT c.Country, SUM(inv.Total) AS total_sales
  FROM invoices inv
  JOIN customers c ON c.CustomerId=inv.CustomerId
  JOIN countries co ON co.Country=c.Country
  GROUP BY c.Country
), ordered AS (
  SELECT total_sales,
         ROW_NUMBER() OVER (ORDER BY total_sales) AS rn,
         COUNT(*) OVER () AS n
  FROM totals
)
SELECT ROUND(AVG(total_sales), 2) AS Median_total_sales
FROM ordered
WHERE rn IN ((n+1)/2, (n+2)/2);
