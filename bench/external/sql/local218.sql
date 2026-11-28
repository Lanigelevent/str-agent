WITH team_season AS (
  SELECT home_team_api_id AS tid, season, SUM(home_team_goal) AS goals
  FROM "Match"
  GROUP BY home_team_api_id, season
  UNION ALL
  SELECT away_team_api_id, season, SUM(away_team_goal)
  FROM "Match"
  GROUP BY away_team_api_id, season
),
combined AS (
  SELECT tid, season, SUM(goals) AS goals
  FROM team_season
  GROUP BY tid, season
),
best AS (
  SELECT tid, MAX(goals) AS best_goals
  FROM combined
  GROUP BY tid
),
ordered AS (
  SELECT best_goals,
         ROW_NUMBER() OVER (ORDER BY best_goals) AS rn,
         COUNT(*) OVER () AS n
  FROM best
)
SELECT AVG(best_goals) AS median_highest_season_goals
FROM ordered
WHERE rn IN ((n + 1) / 2, (n + 2) / 2)
