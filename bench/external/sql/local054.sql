WITH artist_sales AS (
  SELECT al.ArtistId, SUM(ii.UnitPrice*ii.Quantity) AS sales
  FROM invoice_items ii
  JOIN tracks t ON t.TrackId=ii.TrackId
  JOIN albums al ON al.AlbumId=t.AlbumId
  GROUP BY al.ArtistId
), best AS (
  SELECT ArtistId FROM artist_sales
  ORDER BY sales DESC, ArtistId ASC
  LIMIT 1
)
SELECT c.FirstName, SUM(ii.UnitPrice*ii.Quantity) AS TOTALSPENT
FROM invoice_items ii
JOIN invoices inv ON inv.InvoiceId=ii.InvoiceId
JOIN customers c ON c.CustomerId=inv.CustomerId
JOIN tracks t ON t.TrackId=ii.TrackId
JOIN albums al ON al.AlbumId=t.AlbumId
JOIN best b ON b.ArtistId=al.ArtistId
GROUP BY c.CustomerId, c.FirstName
HAVING SUM(ii.UnitPrice*ii.Quantity) < 1
ORDER BY c.FirstName;
