WITH sales AS (
  SELECT salespersonid, CAST(substr(orderdate,1,4) AS INTEGER) AS yr,
         SUM(totaldue) AS total_sales
  FROM salesorderheader
  WHERE salespersonid IS NOT NULL
  GROUP BY salespersonid, yr
), quota AS (
  SELECT BusinessEntityID AS sp, CAST(substr(QuotaDate,1,4) AS INTEGER) AS yr,
         SUM(SalesQuota) AS annual_quota
  FROM SalesPersonQuotaHistory
  GROUP BY BusinessEntityID, yr
)
SELECT s.salespersonid, s.yr AS year,
       ROUND(s.total_sales, 4) AS total_sales,
       q.annual_quota,
       ROUND(s.total_sales - q.annual_quota, 6) AS difference
FROM sales s
JOIN quota q ON q.sp=s.salespersonid AND q.yr=s.yr
ORDER BY s.salespersonid, s.yr;
