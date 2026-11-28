WITH ball_runs AS (
  SELECT b.match_id, b.innings_no, b.over_id, b.bowler, SUM(bs.runs_scored) AS runs
  FROM ball_by_ball b JOIN batsman_scored bs
    ON bs.match_id=b.match_id AND bs.over_id=b.over_id AND bs.ball_id=b.ball_id AND bs.innings_no=b.innings_no
  GROUP BY b.match_id, b.innings_no, b.over_id, b.bowler
), extra_runs_per_over AS (
  SELECT b.match_id, b.innings_no, b.over_id, b.bowler, SUM(e.extra_runs) AS runs
  FROM ball_by_ball b JOIN extra_runs e
    ON e.match_id=b.match_id AND e.over_id=b.over_id AND e.ball_id=b.ball_id AND e.innings_no=b.innings_no
  GROUP BY b.match_id, b.innings_no, b.over_id, b.bowler
), overs AS (
  SELECT b.match_id, b.innings_no, b.over_id, b.bowler,
         COALESCE(br.runs,0)+COALESCE(er.runs,0) AS runs
  FROM ball_by_ball b
  LEFT JOIN ball_runs br ON br.match_id=b.match_id AND br.innings_no=b.innings_no AND br.over_id=b.over_id AND br.bowler=b.bowler
  LEFT JOIN extra_runs_per_over er ON er.match_id=b.match_id AND er.innings_no=b.innings_no AND er.over_id=b.over_id AND er.bowler=b.bowler
  GROUP BY b.match_id, b.innings_no, b.over_id, b.bowler
), max_per_match AS (
  SELECT match_id, MAX(runs) AS max_runs FROM overs GROUP BY match_id
)
SELECT p.player_name AS bowler_name, o.match_id, o.runs AS runs_in_over
FROM overs o
JOIN max_per_match m ON m.match_id=o.match_id AND m.max_runs=o.runs
JOIN player p ON p.player_id=o.bowler
ORDER BY o.runs DESC, o.match_id ASC
LIMIT 3;
