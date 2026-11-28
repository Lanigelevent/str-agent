SELECT pl.product_id, p.name,
       COUNT(*) AS TOTAL_FIFO_PICKS,
       SUM(pl.qty) AS TOTAL_UNITS_PICKED,
       ROUND(SUM(pl.qty)*1.0/COUNT(*), 4) AS AVERAGE_UNITS_PER_FIFO_PICK
FROM picking_line pl
JOIN products p ON p.id=pl.product_id
WHERE pl.order_id=421
GROUP BY pl.product_id, p.name
ORDER BY pl.product_id;
