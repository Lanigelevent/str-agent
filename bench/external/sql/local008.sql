WITH career AS (
  SELECT player_id, SUM(g) AS games, SUM(r) AS runs, SUM(h) AS hits, SUM(hr) AS homers
  FROM batting
  GROUP BY player_id
),
pick AS (
  SELECT 'games_played' AS metric, player_id, value FROM (
    SELECT player_id, games AS value FROM career ORDER BY games DESC, player_id LIMIT 1)
  UNION ALL
  SELECT 'runs', player_id, value FROM (
    SELECT player_id, runs AS value FROM career ORDER BY runs DESC, player_id LIMIT 1)
  UNION ALL
  SELECT 'hits', player_id, value FROM (
    SELECT player_id, hits AS value FROM career ORDER BY hits DESC, player_id LIMIT 1)
  UNION ALL
  SELECT 'home_runs', player_id, value FROM (
    SELECT player_id, homers AS value FROM career ORDER BY homers DESC, player_id LIMIT 1)
)
SELECT p.metric, pl.name_given, p.value
FROM pick p JOIN player pl ON pl.player_id = p.player_id
