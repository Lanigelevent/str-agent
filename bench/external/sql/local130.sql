WITH eng AS (
  SELECT s.StudLastName AS last_name, ss.Grade AS grade
  FROM Student_Schedules ss
  JOIN Students s ON s.StudentID=ss.StudentID
  JOIN Classes cl ON cl.ClassID=ss.ClassID
  JOIN Subjects sub ON sub.SubjectID=cl.SubjectID
  WHERE ss.ClassStatus=2 AND sub.CategoryID='ENG'
), ranked AS (
  SELECT e.last_name, e.grade,
         (SELECT COUNT(*) FROM eng x WHERE x.grade >= e.grade)*1.0/(SELECT COUNT(*) FROM eng) AS pct
  FROM eng e
)
SELECT last_name AS StudLastName, grade AS Grade,
       CASE WHEN pct <= 0.2 THEN 'First'
            WHEN pct <= 0.4 THEN 'Second'
            WHEN pct <= 0.6 THEN 'Third'
            WHEN pct <= 0.8 THEN 'Fourth'
            ELSE 'Fifth' END AS QUINTILE
FROM ranked
ORDER BY grade DESC;
