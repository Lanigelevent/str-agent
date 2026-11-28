WITH pts AS (
  SELECT airport_code,
         CAST(substr(coordinates, 2, instr(coordinates, ',') - 2) AS REAL) AS lng,
         CAST(substr(coordinates, instr(coordinates, ',') + 1,
                    length(coordinates) - instr(coordinates, ',') - 1) AS REAL) AS lat
  FROM airports_data
),
routes AS (
  SELECT DISTINCT departure_airport AS dep, arrival_airport AS arr
  FROM flights
  WHERE departure_airport = 'ABA' OR arrival_airport = 'ABA'
)
SELECT MAX(2 * 6371 * asin(sqrt(
         power(sin(radians(b.lat - a.lat) / 2), 2) +
         cos(radians(a.lat)) * cos(radians(b.lat)) *
         power(sin(radians(b.lng - a.lng) / 2), 2)
       ))) AS longest_distance_km
FROM routes r
JOIN pts a ON a.airport_code = r.dep
JOIN pts b ON b.airport_code = r.arr
