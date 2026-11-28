WITH driver_season AS (
  SELECT r.year, res.driver_id, SUM(res.points) AS pts
  FROM results res
  JOIN races r ON r.race_id = res.race_id
  WHERE r.year >= 2001
  GROUP BY r.year, res.driver_id
  HAVING SUM(res.points) > 0
),
season_min AS (
  SELECT year, MIN(pts) AS min_pts FROM driver_season GROUP BY year
),
low_driver AS (
  SELECT ds.year, ds.driver_id
  FROM driver_season ds
  JOIN season_min sm ON sm.year = ds.year AND sm.min_pts = ds.pts
),
season_ctor AS (
  SELECT DISTINCT ld.year, res.constructor_id
  FROM low_driver ld
  JOIN races r ON r.year = ld.year
  JOIN results res ON res.race_id = r.race_id AND res.driver_id = ld.driver_id
)
SELECT c.name AS constructor_name, COUNT(*) AS seasons_with_lowest
FROM season_ctor sc
JOIN constructors c ON c.constructor_id = sc.constructor_id
GROUP BY c.name
ORDER BY COUNT(*) DESC, c.name ASC
LIMIT 5
