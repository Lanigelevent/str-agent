WITH per_match AS (
  SELECT b.match_id, b.striker, SUM(bs.runs_scored) AS runs
  FROM ball_by_ball b JOIN batsman_scored bs
    ON bs.match_id=b.match_id AND bs.over_id=b.over_id AND bs.ball_id=b.ball_id AND bs.innings_no=b.innings_no
  GROUP BY b.match_id, b.striker
), qualified AS (
  SELECT striker FROM per_match GROUP BY striker HAVING MAX(runs) > 50
), totals AS (
  SELECT pm.striker, SUM(pm.runs) AS total
  FROM per_match pm JOIN qualified q ON q.striker=pm.striker
  GROUP BY pm.striker
)
SELECT ROUND(AVG(total), 6) AS avg_total_runs FROM totals;
