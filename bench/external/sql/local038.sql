SELECT a.first_name || ' ' || a.last_name AS actor_full_name
FROM film_actor fa
JOIN actor a ON a.actor_id=fa.actor_id
JOIN film f ON f.film_id=fa.film_id
JOIN film_category fc ON fc.film_id=f.film_id
JOIN category c ON c.category_id=fc.category_id
JOIN language l ON l.language_id=f.language_id
WHERE c.name='Children'
  AND f.rating IN ('G','PG')
  AND f.length <= 120
  AND CAST(f.release_year AS INTEGER) BETWEEN 2000 AND 2010
  AND l.name='English'
GROUP BY a.actor_id, a.first_name, a.last_name
ORDER BY COUNT(DISTINCT f.film_id) DESC, a.last_name ASC
LIMIT 1;
