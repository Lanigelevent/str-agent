WITH wins AS (
  SELECT bs.BowlerID, bs.MatchID, bs.GameNumber, bs.HandiCapScore,
         t.TourneyDate, t.TourneyLocation
  FROM Bowler_Scores bs
  JOIN Tourney_Matches tm ON tm.MatchID=bs.MatchID
  JOIN Tournaments t ON t.TourneyID=tm.TourneyID
  WHERE bs.WonGame=1 AND bs.HandiCapScore <= 190
    AND t.TourneyLocation IN ('Thunderbird Lanes','Totem Lanes','Bolero Lanes')
), qual AS (
  SELECT BowlerID FROM wins GROUP BY BowlerID
  HAVING COUNT(DISTINCT TourneyLocation)=3
)
SELECT b.BowlerID, b.BowlerFirstName, b.BowlerLastName,
       w.MatchID, w.GameNumber, w.HandiCapScore, w.TourneyDate, w.TourneyLocation
FROM wins w
JOIN qual q ON q.BowlerID=w.BowlerID
JOIN Bowlers b ON b.BowlerID=w.BowlerID
ORDER BY b.BowlerID, w.TourneyDate;
