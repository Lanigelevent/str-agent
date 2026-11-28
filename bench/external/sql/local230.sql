WITH top_genres AS (
  SELECT g.genre, COUNT(DISTINCT g.movie_id) AS n
  FROM genre g JOIN ratings r ON r.movie_id=g.movie_id
  WHERE r.avg_rating > 8
  GROUP BY g.genre
  ORDER BY n DESC, g.genre ASC
  LIMIT 3
)
SELECT n.name AS director, COUNT(DISTINCT dm.movie_id) AS movie_count
FROM director_mapping dm
JOIN names n ON n.id=dm.name_id
JOIN genre g ON g.movie_id=dm.movie_id
JOIN ratings r ON r.movie_id=dm.movie_id
JOIN top_genres tg ON tg.genre=g.genre
WHERE r.avg_rating > 8
GROUP BY n.name
ORDER BY movie_count DESC, n.name ASC
LIMIT 4;
