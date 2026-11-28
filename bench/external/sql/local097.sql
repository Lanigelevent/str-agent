WITH years AS (
  SELECT DISTINCT CAST(substr(year, -4) AS INTEGER) AS yr FROM Movie
), windows AS (
  SELECT y.yr AS start_year, COUNT(m.MID) AS total_movies
  FROM years y
  JOIN Movie m ON CAST(substr(m.year, -4) AS INTEGER) BETWEEN y.yr AND y.yr+9
  GROUP BY y.yr
)
SELECT start_year, total_movies
FROM windows
ORDER BY total_movies DESC, start_year ASC
LIMIT 1;
