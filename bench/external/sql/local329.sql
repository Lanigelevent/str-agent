SELECT COUNT(DISTINCT i.session) AS "COUNT(DISTINCT INPUT_LOG.SESSION)"
FROM form_log i
WHERE i.path='/regist/input'
  AND EXISTS (
    SELECT 1 FROM form_log c
    WHERE c.session=i.session AND c.path='/regist/confirm' AND c.stamp > i.stamp
  );
