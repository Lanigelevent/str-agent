WITH ent AS (
  SELECT EntertainerID,
         MAX(CASE WHEN StyleStrength=1 THEN StyleID END) AS s1,
         MAX(CASE WHEN StyleStrength=2 THEN StyleID END) AS s2
  FROM Entertainer_Styles
  GROUP BY EntertainerID
), cust AS (
  SELECT CustomerID,
         MAX(CASE WHEN PreferenceSeq=1 THEN StyleID END) AS p1,
         MAX(CASE WHEN PreferenceSeq=2 THEN StyleID END) AS p2
  FROM Musical_Preferences
  GROUP BY CustomerID
)
SELECT e.EntStageName, c.CustLastName
FROM ent
JOIN Entertainers e ON e.EntertainerID=ent.EntertainerID
JOIN cust ON ((ent.s1=cust.p1 AND ent.s2=cust.p2) OR (ent.s1=cust.p2 AND ent.s2=cust.p1))
JOIN Customers c ON c.CustomerID=cust.CustomerID
ORDER BY e.EntStageName, c.CustLastName;
