WITH dur AS (
  SELECT m.winner_id, m.loser_id,
         CAST(substr(m.duration, 1, instr(m.duration, ':') - 1) AS INTEGER) * 60
         + CAST(substr(m.duration, instr(m.duration, ':') + 1) AS INTEGER) AS sec
  FROM Matches m
  JOIN Belts b ON b.id = m.title_id
  WHERE b.name LIKE '%NXT%'
    AND m.duration LIKE '%:%'
    AND IFNULL(m.title_change, 0) = 0
)
SELECT w.name AS wrestler1, l.name AS wrestler2
FROM dur d
JOIN Wrestlers w ON w.id = CAST(d.winner_id AS INTEGER)
JOIN Wrestlers l ON l.id = CAST(d.loser_id AS INTEGER)
ORDER BY d.sec ASC, w.name ASC, l.name ASC
LIMIT 1
