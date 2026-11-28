WITH scores AS (
  SELECT ms.StyleID, ms.StyleName,
         SUM(CASE WHEN mp.PreferenceSeq=1 THEN 3
                  WHEN mp.PreferenceSeq=2 THEN 2
                  WHEN mp.PreferenceSeq=3 THEN 1 ELSE 0 END) AS weighted_score
  FROM Musical_Styles ms
  JOIN Musical_Preferences mp ON mp.StyleID=ms.StyleID
  GROUP BY ms.StyleID, ms.StyleName
), avg_score AS (
  SELECT AVG(weighted_score) AS a FROM scores
)
SELECT s.StyleName,
       ROUND(ABS(s.weighted_score - a.a), 3) AS AbsoluteDifference
FROM scores s CROSS JOIN avg_score a
ORDER BY s.StyleName;
