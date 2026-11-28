WITH dm AS (
  SELECT DISTINCT dm.name_id, dm.movie_id, m.date_published, m.duration, r.avg_rating, r.total_votes
  FROM director_mapping dm
  JOIN movies m ON m.id=dm.movie_id
  JOIN ratings r ON r.movie_id=m.id
), gaps AS (
  SELECT name_id,
         julianday(date_published) - julianday(LAG(date_published) OVER (PARTITION BY name_id ORDER BY date_published)) AS gap
  FROM dm
  WHERE date_published IS NOT NULL
), stats AS (
  SELECT name_id,
         COUNT(*) AS movie_count,
         COUNT(DISTINCT movie_id) AS movies,
         ROUND(AVG(duration), 0) AS avg_duration,
         ROUND(AVG(avg_rating) + 0.000000001, 2) AS avg_rating,
         SUM(total_votes) AS total_votes,
         MIN(avg_rating) AS min_rating,
         MAX(avg_rating) AS max_rating,
         SUM(duration) AS total_duration
  FROM dm
  GROUP BY name_id
), inter AS (
  SELECT name_id, ROUND(AVG(gap), 0) AS avg_inter FROM gaps GROUP BY name_id
)
SELECT s.name_id AS DIRECTOR_ID, n.name AS DIRECTOR_NAME,
       s.movie_count AS MOVIE_COUNT,
       COALESCE(i.avg_inter, 0) AS AVG_INTER_MOVIE_DURATION,
       s.avg_rating AS AVG_RATING,
       s.total_votes AS TOTAL_VOTES,
       s.min_rating AS MIN_RATING,
       s.max_rating AS MAX_RATING,
       s.total_duration AS TOTAL_MOVIE_DURATION
FROM stats s
JOIN names n ON n.id=s.name_id
LEFT JOIN inter i ON i.name_id=s.name_id
ORDER BY s.movie_count DESC, s.total_duration DESC
LIMIT 9;
