WITH piv AS (
  SELECT name, version, step,
         MAX(CASE WHEN model='Stack' THEN test_score END) AS stack_score,
         MAX(CASE WHEN model<>'Stack' THEN test_score END) AS max_other
  FROM model_score
  GROUP BY name, version, step
), strong AS (
  SELECT name, COUNT(*) AS strong_count
  FROM piv
  WHERE max_other < stack_score
  GROUP BY name
), sol AS (
  SELECT name, COUNT(*) AS solution_count FROM solution GROUP BY name
)
SELECT s.name
FROM strong s JOIN sol ON sol.name=s.name
WHERE s.strong_count > sol.solution_count
ORDER BY s.name;
