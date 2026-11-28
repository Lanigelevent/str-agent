WITH drv AS (
  SELECT r.year, res.driver_id, SUM(res.points) AS pts
  FROM results res JOIN races r ON r.race_id = res.race_id
  GROUP BY r.year, res.driver_id
),
ctor AS (
  SELECT r.year, res.constructor_id, SUM(res.points) AS pts
  FROM results res JOIN races r ON r.race_id = res.race_id
  GROUP BY r.year, res.constructor_id
),
drv_max AS (SELECT year, MAX(pts) AS best FROM drv GROUP BY year),
ctor_max AS (SELECT year, MAX(pts) AS best FROM ctor GROUP BY year)
SELECT dm.year
FROM drv_max dm
JOIN ctor_max cm ON cm.year = dm.year
ORDER BY dm.best + cm.best ASC, dm.year ASC
LIMIT 3
