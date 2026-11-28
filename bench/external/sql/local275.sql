WITH cma AS (
  SELECT product_id, mth, qty,
         AVG(qty) OVER (
           PARTITION BY product_id ORDER BY mth
           ROWS BETWEEN 6 PRECEDING AND 5 FOLLOWING
         ) AS moving_avg
  FROM monthly_sales
  WHERE mth >= '2016-01-01'
), ratios AS (
  SELECT product_id, mth, qty*1.0/moving_avg AS ratio
  FROM cma
  WHERE moving_avg IS NOT NULL
)
SELECT p.name AS output
FROM ratios r JOIN products p ON p.id=r.product_id
WHERE r.mth >= '2017-01-01' AND r.mth < '2018-01-01'
GROUP BY r.product_id, p.name
HAVING COUNT(*) = 12 AND MIN(r.ratio) > 2
ORDER BY p.name;
