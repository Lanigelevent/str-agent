WITH per_match AS (
  SELECT b.match_id, b.striker, SUM(bs.runs_scored) AS runs
  FROM ball_by_ball b JOIN batsman_scored bs
    ON bs.match_id=b.match_id AND bs.over_id=b.over_id AND bs.ball_id=b.ball_id AND bs.innings_no=b.innings_no
  GROUP BY b.match_id, b.striker
)
SELECT DISTINCT p.player_name
FROM per_match pm
JOIN match m ON m.match_id=pm.match_id
JOIN player_match plm ON plm.match_id=pm.match_id AND plm.player_id=pm.striker
JOIN player p ON p.player_id=pm.striker
WHERE pm.runs >= 100
  AND m.match_winner IS NOT NULL
  AND m.match_winner <> plm.team_id
ORDER BY p.player_name;
