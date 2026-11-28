WITH d AS (
  SELECT DISTINCT stamp, session, path FROM activity_log
), r AS (
  SELECT session, path,
         ROW_NUMBER() OVER (PARTITION BY session ORDER BY stamp) AS rf,
         ROW_NUMBER() OVER (PARTITION BY session ORDER BY stamp DESC) AS rl
  FROM d
), fe AS (
  SELECT session, path FROM r WHERE rf=1
  UNION
  SELECT session, path FROM r WHERE rl=1
)
SELECT path AS WEB_PAGE, COUNT(DISTINCT session) AS UNIQUE_SESSIONS_COUNT
FROM fe
GROUP BY path
ORDER BY UNIQUE_SESSIONS_COUNT DESC, WEB_PAGE;
