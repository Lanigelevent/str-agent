WITH director_films AS (
  SELECT mc.PID AS actor, md.PID AS director, COUNT(DISTINCT mc.MID) AS films
  FROM "M_Cast" mc JOIN "M_Director" md ON md.MID=mc.MID
  GROUP BY mc.PID, md.PID
), yash AS (
  SELECT PID FROM Person WHERE Name='Yash Chopra'
), per_actor AS (
  SELECT actor,
         SUM(CASE WHEN director IN (SELECT PID FROM yash) THEN films ELSE 0 END) AS yash_films,
         MAX(CASE WHEN director NOT IN (SELECT PID FROM yash) THEN films ELSE 0 END) AS other_max
  FROM director_films
  GROUP BY actor
)
SELECT COUNT(*) AS ACTORS_WITH_YASH_CHOPRA_AS_TOP_COLLABORATOR
FROM per_actor
WHERE yash_films > COALESCE(other_max, 0);
