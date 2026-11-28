SELECT L1_model AS model_category, COUNT(*) AS total_count
FROM model
GROUP BY L1_model
ORDER BY total_count DESC, L1_model ASC
LIMIT 1;
