WITH RECURSIVE top AS (
  SELECT id FROM packaging WHERE id NOT IN (SELECT contains_id FROM packaging_relations)
), expand(root, node, mult) AS (
  SELECT id, id, 1 FROM top
  UNION ALL
  SELECT e.root, pr.contains_id, e.mult * pr.qty
  FROM expand e JOIN packaging_relations pr ON pr.packaging_id=e.node
), leaf_totals AS (
  SELECT e.root, SUM(e.mult) AS total
  FROM expand e
  WHERE NOT EXISTS (SELECT 1 FROM packaging_relations pr WHERE pr.packaging_id=e.node)
  GROUP BY e.root
)
SELECT ROUND(AVG(total), 1) AS AVERAGE_TOTAL_QUANTITY FROM leaf_totals;
