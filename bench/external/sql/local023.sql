WITH season_matches AS (
  SELECT match_id FROM match WHERE season_id=5
), batting AS (
  SELECT b.striker AS player_id, bs.runs_scored
  FROM ball_by_ball b
  JOIN batsman_scored bs
    ON bs.match_id=b.match_id AND bs.over_id=b.over_id AND bs.ball_id=b.ball_id AND bs.innings_no=b.innings_no
  JOIN season_matches sm ON sm.match_id=b.match_id
), runs AS (
  SELECT player_id, SUM(runs_scored) AS total_runs FROM batting GROUP BY player_id
), apps AS (
  SELECT plm.player_id, COUNT(DISTINCT plm.match_id) AS matches
  FROM player_match plm JOIN season_matches sm ON sm.match_id=plm.match_id
  GROUP BY plm.player_id
), outs AS (
  SELECT wt.player_out AS player_id, COUNT(*) AS dismissals
  FROM wicket_taken wt JOIN season_matches sm ON sm.match_id=wt.match_id
  GROUP BY wt.player_out
)
SELECT p.player_name,
       ROUND(r.total_runs*1.0/a.matches, 4) AS avg_runs_per_match,
       ROUND(r.total_runs*1.0/o.dismissals, 4) AS batting_average
FROM runs r
JOIN apps a ON a.player_id=r.player_id
JOIN outs o ON o.player_id=r.player_id
JOIN player p ON p.player_id=r.player_id
ORDER BY avg_runs_per_match DESC
LIMIT 5;
