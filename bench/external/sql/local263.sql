WITH piv AS (
  SELECT name, version, step,
         MAX(CASE WHEN model='Stack' THEN test_score END) AS stack_score,
         MAX(CASE WHEN model<>'Stack' THEN test_score END) AS max_other
  FROM model_score
  GROUP BY name, version, step
), step_status AS (
  SELECT name, version,
         MAX(CASE WHEN max_other < stack_score THEN 1 ELSE 0 END) AS any_strong,
         MAX(CASE WHEN max_other = stack_score THEN 1 ELSE 0 END) AS any_soft
  FROM piv
  GROUP BY name, version
), model_status AS (
  SELECT ss.name, ss.version,
         CASE WHEN any_strong=1 THEN 'strong' WHEN any_soft=1 THEN 'soft' ELSE 'none' END AS status
  FROM step_status ss
), labeled AS (
  SELECT DISTINCT ms.name, ms.version, ms.status, mo.L1_model
  FROM model_status ms
  JOIN model mo ON mo.name=ms.name AND mo.version=ms.version
  WHERE ms.status <> 'none'
), counts AS (
  SELECT status, L1_model, COUNT(*) AS occurrences
  FROM labeled
  GROUP BY status, L1_model
), ranked AS (
  SELECT status, L1_model, occurrences,
         ROW_NUMBER() OVER (PARTITION BY status ORDER BY occurrences DESC, L1_model ASC) AS rn
  FROM counts
)
SELECT status AS STATUS, L1_model AS L1_MODEL, occurrences AS OCCURRENCES
FROM ranked WHERE rn=1
ORDER BY status;
