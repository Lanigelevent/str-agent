WITH artist_sales AS (
  SELECT al.ArtistId, SUM(ii.UnitPrice*ii.Quantity) AS sales
  FROM invoice_items ii
  JOIN tracks t ON t.TrackId=ii.TrackId
  JOIN albums al ON al.AlbumId=t.AlbumId
  GROUP BY al.ArtistId
), ranked AS (
  SELECT ArtistId, sales,
         ROW_NUMBER() OVER (ORDER BY sales DESC, ArtistId ASC) AS rn_top,
         ROW_NUMBER() OVER (ORDER BY sales ASC, ArtistId ASC) AS rn_low
  FROM artist_sales
), chosen AS (
  SELECT ArtistId AS artist_id, 'top' AS side FROM ranked WHERE rn_top=1
  UNION ALL
  SELECT ArtistId, 'low' FROM ranked WHERE rn_low=1
), customer_spend AS (
  SELECT ch.side, inv.CustomerId, SUM(ii.UnitPrice*ii.Quantity) AS spent
  FROM chosen ch
  JOIN tracks t ON t.AlbumId IN (SELECT AlbumId FROM albums WHERE ArtistId=ch.artist_id)
  JOIN invoice_items ii ON ii.TrackId=t.TrackId
  JOIN invoices inv ON inv.InvoiceId=ii.InvoiceId
  GROUP BY ch.side, inv.CustomerId
), averages AS (
  SELECT side, AVG(spent) AS avg_spent FROM customer_spend GROUP BY side
)
SELECT ROUND(ABS(MAX(CASE WHEN side='top' THEN avg_spent END) -
                 MAX(CASE WHEN side='low' THEN avg_spent END)), 6) AS AbsoluteAverageDifference
FROM averages;
