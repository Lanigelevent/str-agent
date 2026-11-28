WITH cast_gender AS (
  SELECT mc.MID, p.Gender
  FROM "M_Cast" mc JOIN Person p ON p.PID=TRIM(mc.PID)
), movie_info AS (
  SELECT m.MID, CAST(substr(m.year, -4) AS INTEGER) AS yr
  FROM Movie m
), per_movie AS (
  SELECT mi.MID, mi.yr,
         CASE WHEN EXISTS (SELECT 1 FROM cast_gender cg WHERE cg.MID=mi.MID)
               AND NOT EXISTS (SELECT 1 FROM cast_gender cg WHERE cg.MID=mi.MID AND (cg.Gender IS NULL OR cg.Gender <> 'Female'))
              THEN 1 ELSE 0 END AS female_only
  FROM movie_info mi
)
SELECT yr AS YEAR, COUNT(*) AS TOTAL_MOVIES,
       ROUND(SUM(female_only)*100.0/COUNT(*), 2) AS PERCENTAGE_EXCLUSIVELY_FEMALE
FROM per_movie
GROUP BY yr
ORDER BY yr;
