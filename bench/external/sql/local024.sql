WITH per_match AS (
  SELECT b.match_id, b.striker AS player_id, SUM(bs.runs_scored) AS runs
  FROM ball_by_ball b JOIN batsman_scored bs
    ON bs.match_id=b.match_id AND bs.over_id=b.over_id AND bs.ball_id=b.ball_id AND bs.innings_no=b.innings_no
  GROUP BY b.match_id, b.striker
), runs AS (
  SELECT player_id, SUM(runs) AS total FROM per_match GROUP BY player_id
), apps AS (
  SELECT player_id, COUNT(DISTINCT match_id) AS matches FROM player_match GROUP BY player_id
), career AS (
  SELECT r.player_id, r.total*1.0/a.matches AS avg_runs_per_match
  FROM runs r JOIN apps a ON a.player_id=r.player_id
)
SELECT p.country_name,
       ROUND(AVG(c.avg_runs_per_match), 6) AS country_batting_average
FROM career c JOIN player p ON p.player_id=c.player_id
WHERE p.country_name IS NOT NULL
GROUP BY p.country_name
ORDER BY country_batting_average DESC
LIMIT 5;
