WITH pts AS (
  SELECT airport_code,
         CAST(substr(coordinates, 2, instr(coordinates, ',') - 2) AS REAL) AS lng,
         CAST(substr(coordinates, instr(coordinates, ',') + 1,
                    length(coordinates) - instr(coordinates, ',') - 1) AS REAL) AS lat
  FROM airports_data
),
dist AS (
  SELECT 2 * 6371 * asin(sqrt(
           power(sin(radians(b.lat - a.lat) / 2), 2) +
           cos(radians(a.lat)) * cos(radians(b.lat)) *
           power(sin(radians(b.lng - a.lng) / 2), 2)
         )) AS d
  FROM (SELECT DISTINCT departure_airport AS dep, arrival_airport AS arr FROM flights) f
  JOIN pts a ON a.airport_code = f.dep
  JOIN pts b ON b.airport_code = f.arr
),
binned AS (
  SELECT CASE WHEN d >= 6000 THEN 6000 ELSE CAST(d / 1000 AS INTEGER) * 1000 END AS dist_bin
  FROM dist
)
SELECT COUNT(*) AS output
FROM binned
GROUP BY dist_bin
ORDER BY COUNT(*) ASC, dist_bin ASC
LIMIT 1
