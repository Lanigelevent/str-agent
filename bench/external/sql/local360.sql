WITH d AS (
  SELECT DISTINCT stamp, session, path, search_type FROM activity_log
), r AS (
  SELECT session, path, search_type,
         ROW_NUMBER() OVER (PARTITION BY session ORDER BY stamp) AS rn
  FROM d
), f AS (
  SELECT session, MIN(rn) AS rn
  FROM r
  WHERE path LIKE '/detail%' OR path='/complete'
  GROUP BY session
), counts AS (
  SELECT f.session, f.rn,
         (SELECT COUNT(*) FROM r x
          WHERE x.session=f.session AND x.rn < f.rn
            AND COALESCE(x.search_type,'') <> '') AS cnt
  FROM f
)
SELECT r.session, r.path, NULLIF(r.search_type, '') AS search_type
FROM counts c JOIN r ON r.session=c.session AND r.rn=c.rn
WHERE c.cnt = (SELECT MIN(cnt) FROM counts)
ORDER BY r.session;
