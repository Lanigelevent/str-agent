WITH seq AS (
  SELECT path,
         LAG(path, 1) OVER (PARTITION BY session ORDER BY stamp, rowid) AS prev1,
         LAG(path, 2) OVER (PARTITION BY session ORDER BY stamp, rowid) AS prev2
  FROM activity_log
  WHERE path IS NOT NULL
)
SELECT path AS third_page, COUNT(*) AS occurrences
FROM seq
WHERE prev1 = '/detail' AND prev2 = '/detail'
GROUP BY path
ORDER BY occurrences DESC, third_page ASC
LIMIT 3
