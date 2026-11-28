WITH wins AS (
  SELECT home_team_api_id AS tid FROM "Match" WHERE home_team_goal > away_team_goal
  UNION ALL
  SELECT away_team_api_id FROM "Match" WHERE away_team_goal > home_team_goal
)
SELECT t.team_long_name, COUNT(*) AS total_wins
FROM wins w
JOIN Team t ON t.team_api_id = w.tid
GROUP BY w.tid, t.team_long_name
ORDER BY total_wins DESC, t.team_long_name ASC
LIMIT 10
