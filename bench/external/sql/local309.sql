WITH last_race AS (
  SELECT year, MAX(race_id) AS mid FROM races GROUP BY year
),
drv_standing AS (
  SELECT r.year,
         d.forename || ' ' || d.surname AS nm,
         ROW_NUMBER() OVER (PARTITION BY r.year ORDER BY ds.points DESC, ds.position ASC, d.surname) AS rn
  FROM driver_standings ds
  JOIN races r ON r.race_id = ds.race_id
  JOIN last_race lr ON lr.year = r.year AND lr.mid = r.race_id
  JOIN drivers d ON d.driver_id = ds.driver_id
),
ctor_standing AS (
  SELECT r.year, c.name AS nm,
         ROW_NUMBER() OVER (PARTITION BY r.year ORDER BY cs.points DESC, cs.position ASC, c.name) AS rn
  FROM constructor_standings cs
  JOIN races r ON r.race_id = cs.race_id
  JOIN last_race lr ON lr.year = r.year AND lr.mid = r.race_id
  JOIN constructors c ON c.constructor_id = cs.constructor_id
),
drv_results AS (
  SELECT r.year, d.forename || ' ' || d.surname AS nm,
         ROW_NUMBER() OVER (PARTITION BY r.year ORDER BY SUM(res.points) DESC, d.surname) AS rn
  FROM results res
  JOIN races r ON r.race_id = res.race_id
  JOIN drivers d ON d.driver_id = res.driver_id
  GROUP BY r.year, d.driver_id
),
ctor_results AS (
  SELECT r.year, c.name AS nm,
         ROW_NUMBER() OVER (PARTITION BY r.year ORDER BY SUM(res.points) DESC, c.name) AS rn
  FROM results res
  JOIN races r ON r.race_id = res.race_id
  JOIN constructors c ON c.constructor_id = res.constructor_id
  GROUP BY r.year, c.constructor_id
),
yrs AS (SELECT DISTINCT year FROM races)
SELECT yrs.year,
       COALESCE((SELECT nm FROM drv_standing WHERE drv_standing.year = yrs.year AND rn = 1),
                (SELECT nm FROM drv_results WHERE drv_results.year = yrs.year AND rn = 1)) AS driver_full_name,
       COALESCE((SELECT nm FROM ctor_standing WHERE ctor_standing.year = yrs.year AND rn = 1),
                (SELECT nm FROM ctor_results WHERE ctor_results.year = yrs.year AND rn = 1)) AS constructor_name
FROM yrs
ORDER BY yrs.year
