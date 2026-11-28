SELECT AVG((julianday(final_game) - julianday(debut)) / 365.0) AS avg_career_span_years
FROM player
WHERE debut IS NOT NULL AND final_game IS NOT NULL
