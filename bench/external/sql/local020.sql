WITH runs AS (
  SELECT b.bowler, SUM(bs.runs_scored) AS r
  FROM ball_by_ball b JOIN batsman_scored bs
    ON bs.match_id=b.match_id AND bs.over_id=b.over_id AND bs.ball_id=b.ball_id AND bs.innings_no=b.innings_no
  GROUP BY b.bowler
), extras AS (
  SELECT b.bowler, SUM(e.extra_runs) AS r
  FROM ball_by_ball b JOIN extra_runs e
    ON e.match_id=b.match_id AND e.over_id=b.over_id AND e.ball_id=b.ball_id AND e.innings_no=b.innings_no
  WHERE e.extra_type IN ('wides','noballs')
  GROUP BY b.bowler
), wkts AS (
  SELECT b.bowler, COUNT(*) AS w
  FROM ball_by_ball b JOIN wicket_taken w
    ON w.match_id=b.match_id AND w.over_id=b.over_id AND w.ball_id=b.ball_id AND w.innings_no=b.innings_no
  WHERE w.kind_out NOT IN ('run out','retired hurt','retired out','obstructing the field')
  GROUP BY b.bowler
)
SELECT p.player_id AS bowler_id, p.player_name,
       ROUND((COALESCE(r.r,0)+COALESCE(e.r,0))*1.0/w.w, 2) AS bowling_average
FROM wkts w JOIN player p ON p.player_id=w.bowler
LEFT JOIN runs r ON r.bowler=w.bowler
LEFT JOIN extras e ON e.bowler=w.bowler
ORDER BY bowling_average ASC, p.player_name ASC
LIMIT 1;
