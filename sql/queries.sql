-- MyTix application SQL reference

-- The queries and operations that the app runs, as verified SQL. In the app these
-- will be PreparedStatements, with every `?` as a bound parameter. This file is a
-- reference and a report source; it will not run by the graders, so nothing here
-- loads or changes data on its own. Just a good point of reference for the
-- SQL that the app runs.
--
-- Q4-Q7 (and organizer operations) is Tareq's half. Faris' Q1-Q3 and the customer operations
-- belong alongside these. The reasoning behind each choice lives in docs/query_design.md.


-----------------------------------------------------------------------
-- QUERIES
-----------------------------------------------------------------------

-- Q1: performances within a radius of a lat/long, ranked by distance (default)
-- or by cheapest/most expensive available ticket. Haversine in km via ACOS,
-- clamped to [-1,1] to avoid floating-point rounding pushing the argument
-- outside ACOS's domain (which would silently return NULL). Default radius
-- 50km if the caller passes none; ranking mode chosen by the app before this
-- string is built (never from raw user input). Reuses performance_availability
-- for cheapest_price so it matches Q4/Q5's definition exactly.
-- Binds: lat, lng, lat, radius_km.
WITH distances AS (
  SELECT p.performance_id, e.title, v.name AS venue, v.city, p.performance_datetime,
         (6371 * ACOS(LEAST(1, GREATEST(-1,
             COS(RADIANS(?)) * COS(RADIANS(v.latitude)) * COS(RADIANS(v.longitude) - RADIANS(?))
             + SIN(RADIANS(?)) * SIN(RADIANS(v.latitude))
         )))) AS distance_km,
         pa.cheapest_price
  FROM performance p
  JOIN venue v ON v.venue_id = p.venue_id
  JOIN event e ON e.event_id = p.event_id
  LEFT JOIN performance_availability pa ON pa.performance_id = p.performance_id
  WHERE p.status = 'SCHEDULED' AND p.performance_datetime >= NOW()
)
SELECT * FROM distances WHERE distance_km <= ?
ORDER BY distance_km ASC;
-- (or ORDER BY cheapest_price IS NULL, cheapest_price ASC/DESC for the two price-ranked modes)


-- Q2: performances in the same or adjacent postal code. "Adjacent" =
-- same first-3-characters (Canadian FSA); an exact match is a special case
-- of this, so one condition covers both halves of the spec's requirement.
-- Binds: postal_code, postal_code.
SELECT p.performance_id, e.title, v.name AS venue, v.postal_code, v.city,
       p.performance_datetime, pa.cheapest_price
FROM performance p
JOIN venue v ON v.venue_id = p.venue_id
JOIN event e ON e.event_id = p.event_id
LEFT JOIN performance_availability pa ON pa.performance_id = p.performance_id
WHERE p.status = 'SCHEDULED' AND p.performance_datetime >= NOW()
  AND LEFT(v.postal_code, 2) = LEFT(?, 2)
ORDER BY p.performance_datetime;


-- Q3: exact address -> the venue, then its upcoming performances. Two
-- statements: find the venue (bind: address), then list its performances
-- (bind: venue_id). No fuzzy matching - "exact" per the spec.
SELECT venue_id, name, city, country FROM venue WHERE address = ?;

SELECT p.performance_id, e.title, v.name AS venue, v.city, p.performance_datetime,
       pa.cheapest_price
FROM performance p
JOIN venue v ON v.venue_id = p.venue_id
JOIN event e ON e.event_id = p.event_id
LEFT JOIN performance_availability pa ON pa.performance_id = p.performance_id
WHERE p.venue_id = ? AND p.status = 'SCHEDULED' AND p.performance_datetime >= NOW()
ORDER BY p.performance_datetime;

-- Q6: seat-map summary for one performance. One row per section with its tier,
-- price, and live available / sold / blocked counts. Reserved and GA are two
-- halves of a UNION. Bind performance_id four times (once per half's joins).
SELECT s.section_name, s.section_type, pt.tier_code, pt.price,
       sc.total_seats - COALESCE(hc.sold,0) - COALESCE(hc.blocked,0) AS available,
       COALESCE(hc.sold,0) AS sold, COALESCE(hc.blocked,0) AS blocked
FROM section s
JOIN performance_section_tier pst ON pst.performance_id=? AND pst.section_id=s.section_id
JOIN price_tier pt ON pt.tier_id=pst.tier_id
JOIN (SELECT r.section_id, COUNT(*) total_seats
      FROM seat se JOIN seat_row r ON r.row_id=se.row_id GROUP BY r.section_id) sc
     ON sc.section_id=s.section_id
LEFT JOIN (SELECT r.section_id, SUM(sh.hold_type='SOLD') sold, SUM(sh.hold_type='BLOCKED') blocked
           FROM seat_hold sh JOIN seat se ON se.seat_id=sh.seat_id JOIN seat_row r ON r.row_id=se.row_id
           WHERE sh.performance_id=? GROUP BY r.section_id) hc
     ON hc.section_id=s.section_id
WHERE s.section_type='RESERVED'
UNION ALL
SELECT s.section_name, s.section_type, pt.tier_code, pt.price,
       s.ga_capacity - COALESCE(gc.sold,0) AS available, COALESCE(gc.sold,0) AS sold, 0 AS blocked
FROM section s
JOIN performance_section_tier pst ON pst.performance_id=? AND pst.section_id=s.section_id
JOIN price_tier pt ON pt.tier_id=pst.tier_id
LEFT JOIN (SELECT t.ga_section_id section_id, COUNT(*) sold
           FROM ticket t JOIN orders o ON o.order_id=t.order_id
           WHERE o.performance_id=? AND t.status='ACTIVE' AND t.ga_section_id IS NOT NULL
           GROUP BY t.ga_section_id) gc
     ON gc.section_id=s.section_id
WHERE s.section_type='GA'
ORDER BY section_name;


-- Q7: best available. The q consecutive open seats in one row with the lowest
-- total price, within an optional budget. Gaps-and-islands method finds each run of
-- consecutive open seats; we rank by cheapest row and take one block.
-- Binds in order: performance_id, performance_id, q (HAVING), q (end_seat),
-- q (seats), q (total), budget, budget, q.
WITH available_seats AS (
  SELECT se.seat_number, r.row_id, r.row_name, s.section_name, pt.price
  FROM seat se JOIN seat_row r ON r.row_id=se.row_id
  JOIN section s ON s.section_id=r.section_id
  JOIN performance_section_tier pst ON pst.performance_id=? AND pst.section_id=s.section_id
  JOIN price_tier pt ON pt.tier_id=pst.tier_id
  WHERE s.section_type='RESERVED'
    AND NOT EXISTS (SELECT 1 FROM seat_hold sh WHERE sh.performance_id=? AND sh.seat_id=se.seat_id)
),
islands AS (
  SELECT row_id, row_name, section_name, price, seat_number,
         seat_number - ROW_NUMBER() OVER (PARTITION BY row_id ORDER BY seat_number) AS island_key
  FROM available_seats
),
runs AS (
  SELECT section_name, row_name, price, MIN(seat_number) AS start_seat
  FROM islands GROUP BY row_id, row_name, section_name, price, island_key
  HAVING COUNT(*) >= ?
)
SELECT section_name, row_name, start_seat, start_seat + ? - 1 AS end_seat,
       ? AS seats, price AS price_each, ? * price AS total_price
FROM runs
WHERE (? IS NULL OR ? >= (? * price))
ORDER BY total_price, section_name, row_name, start_seat
LIMIT 1;


-- Shared availability, used by Q4 and Q5 (should/could be used for Faris' Q1 ranking).
-- Per performance: how many tickets are open, the cheapest open ticket, and
-- flags for whether any reserved / GA availability is left. Written as a view so
-- there is one definition instead of the same math copied into each query.
-- (Pending a quick confirm with Faris; if we would rather not add a view, inline
-- this same body as a CTE at the top of Q4 and Q5.)
CREATE OR REPLACE VIEW performance_availability AS
SELECT performance_id,
       SUM(available) AS available_count,
       MIN(CASE WHEN available>0 THEN price END) AS cheapest_price,
       MAX(section_type='RESERVED' AND available>0) AS has_reserved_avail,
       MAX(section_type='GA' AND available>0) AS has_ga_avail
FROM (
  SELECT pst.performance_id, s.section_type, pt.price,
         sc.total_seats - COALESCE(hc.sold,0) - COALESCE(hc.blocked,0) AS available
  FROM performance_section_tier pst
  JOIN section s ON s.section_id=pst.section_id AND s.section_type='RESERVED'
  JOIN price_tier pt ON pt.tier_id=pst.tier_id
  JOIN (SELECT r.section_id, COUNT(*) total_seats FROM seat se JOIN seat_row r ON r.row_id=se.row_id GROUP BY r.section_id) sc
    ON sc.section_id=s.section_id
  LEFT JOIN (SELECT sh.performance_id, r.section_id, SUM(sh.hold_type='SOLD') sold, SUM(sh.hold_type='BLOCKED') blocked
             FROM seat_hold sh JOIN seat se ON se.seat_id=sh.seat_id JOIN seat_row r ON r.row_id=se.row_id
             GROUP BY sh.performance_id, r.section_id) hc
    ON hc.performance_id=pst.performance_id AND hc.section_id=s.section_id
  UNION ALL
  SELECT pst.performance_id, s.section_type, pt.price, s.ga_capacity - COALESCE(g.sold,0) AS available
  FROM performance_section_tier pst
  JOIN section s ON s.section_id=pst.section_id AND s.section_type='GA'
  JOIN price_tier pt ON pt.tier_id=pst.tier_id
  LEFT JOIN (SELECT o.performance_id, t.ga_section_id section_id, COUNT(*) sold
             FROM ticket t JOIN orders o ON o.order_id=t.order_id
             WHERE t.status='ACTIVE' AND t.ga_section_id IS NOT NULL
             GROUP BY o.performance_id, t.ga_section_id) g
    ON g.performance_id=pst.performance_id AND g.section_id=s.section_id
) section_avail
GROUP BY performance_id;


-- Q5: the big filterable search. Every filter is optional via (? IS NULL OR ...),
-- so any combination works from one query. Binds are the pairs city, segment,
-- genre, date-from, date-to, price-low, price-high, min-available, then the
-- section-type param three times (null / 'RESERVED' / 'GA').
SELECT p.performance_id, e.title, v.name AS venue, v.city, seg.segment_name, g.genre_name,
       p.performance_datetime, pa.available_count, pa.cheapest_price
FROM performance p
JOIN performance_availability pa ON pa.performance_id=p.performance_id
JOIN event e   ON e.event_id=p.event_id
JOIN venue v   ON v.venue_id=p.venue_id
JOIN genre g   ON g.genre_id=e.genre_id
JOIN segment seg ON seg.segment_id=g.segment_id
WHERE p.status='SCHEDULED' AND p.performance_datetime>=NOW()
  AND (? IS NULL OR v.city=?)
  AND (? IS NULL OR seg.segment_name=?)
  AND (? IS NULL OR g.genre_name=?)
  AND (? IS NULL OR p.performance_datetime>=?)
  AND (? IS NULL OR p.performance_datetime<=?)
  AND (? IS NULL OR pa.cheapest_price>=?)
  AND (? IS NULL OR pa.cheapest_price<=?)
  AND (? IS NULL OR pa.available_count>=?)
  AND (? IS NULL OR (?='RESERVED' AND pa.has_reserved_avail=1) OR (?='GA' AND pa.has_ga_avail=1))
ORDER BY p.performance_datetime;


-- Q4: date range plus a minimum number of available tickets. This is the temporal
-- refinement of the location searches, so Faris' Q1-Q3 geo predicate ANDs in where
-- marked. Binds: date-from, date-to, min-available.
SELECT p.performance_id, e.title, v.city, p.performance_datetime, pa.available_count
FROM performance p
JOIN performance_availability pa ON pa.performance_id=p.performance_id
JOIN event e ON e.event_id=p.event_id
JOIN venue v ON v.venue_id=p.venue_id
WHERE p.status='SCHEDULED'
  AND p.performance_datetime BETWEEN ? AND ?
  AND pa.available_count >= ?
  -- AND <Faris' Q1-Q3 geo predicate: distance / postal code / exact address>
ORDER BY p.performance_datetime;


-----------------------------------------------------------------------
-- ORGANIZER OPERATIONS
-----------------------------------------------------------------------
-- Every op runs in its own transaction and checks ownership inside the statement,
-- so it can only ever touch an event the signed-in organizer owns. Binds noted
-- per op; organizer_id comes from the session.

-- create_event
INSERT INTO event (organizer_id, title, genre_id, resale_cap_pct) VALUES (?, ?, ?, ?);

-- add_performance. INSERT ... SELECT so ownership gates it; zero rows inserted
-- means the event was not theirs. Binds: event_id, venue_id, datetime, event_id, organizer_id.
INSERT INTO performance (event_id, venue_id, performance_datetime)
SELECT ?, ?, ? FROM event e WHERE e.event_id=? AND e.organizer_id=?;

-- price_performance (one transaction). Insert the tiers, assign every section,
-- then check completeness before COMMIT; if the check fails, ROLLBACK.
INSERT INTO price_tier (performance_id, tier_code, price) VALUES (?, ?, ?); -- repeat, >= 2 tiers
INSERT INTO performance_section_tier (performance_id, section_id, tier_id) VALUES (?, ?, ?); -- repeat, one per section
-- completeness gate: require tiers >= 2 AND assigned = venue_sections
SELECT (SELECT COUNT(*) FROM price_tier WHERE performance_id=?) AS tiers,
       (SELECT COUNT(*) FROM performance_section_tier WHERE performance_id=?) AS assigned,
       (SELECT COUNT(*) FROM section s JOIN performance p ON p.venue_id=s.venue_id
        WHERE p.performance_id=?) AS venue_sections;

-- update_tier_price. Guarded: owner, future performance, and no active sale in any
-- of the tier's sections. Zero rows changed means refused; run a small diagnostic
-- read to tell the organizer why. Binds: new_price, tier_id, organizer_id.
UPDATE price_tier pt
JOIN performance p ON p.performance_id=pt.performance_id
JOIN event e ON e.event_id=p.event_id
SET pt.price=?
WHERE pt.tier_id=? AND e.organizer_id=? AND p.performance_datetime>NOW()
  AND NOT (
    EXISTS (SELECT 1 FROM seat_hold sh JOIN seat se ON se.seat_id=sh.seat_id JOIN seat_row r ON r.row_id=se.row_id
            JOIN performance_section_tier pst ON pst.section_id=r.section_id AND pst.performance_id=sh.performance_id
            WHERE sh.performance_id=pt.performance_id AND sh.hold_type='SOLD' AND pst.tier_id=pt.tier_id)
    OR EXISTS (SELECT 1 FROM ticket t JOIN orders o ON o.order_id=t.order_id
            JOIN performance_section_tier pst ON pst.section_id=t.ga_section_id AND pst.performance_id=o.performance_id
            WHERE o.performance_id=pt.performance_id AND t.status='ACTIVE' AND pst.tier_id=pt.tier_id)
  );

-- block_seat. INSERT ... SELECT gated on ownership; the seat_hold primary key
-- refuses a seat that is already sold or blocked (a duplicate-key error). Zero
-- rows means not their event. Binds: performance_id, seat_id, performance_id, organizer_id.
INSERT INTO seat_hold (performance_id, seat_id, hold_type, ticket_id)
SELECT ?, ?, 'BLOCKED', NULL
FROM performance p JOIN event e ON e.event_id=p.event_id
WHERE p.performance_id=? AND e.organizer_id=? AND p.status='SCHEDULED' AND p.performance_datetime>NOW();

-- unblock_seat. Scoped to BLOCKED rows, so a sold seat can never be freed this way
-- (freeing a sold seat can only happen through a cancellation). Binds: performance_id, seat_id, organizer_id.
DELETE sh FROM seat_hold sh
JOIN performance p ON p.performance_id=sh.performance_id
JOIN event e ON e.event_id=p.event_id
WHERE sh.performance_id=? AND sh.seat_id=? AND sh.hold_type='BLOCKED' AND e.organizer_id=?;

-- cancel_performance (one transaction). Step 1 is the owner gate: if it changes
-- zero rows, ROLLBACK and refuse. Then cancel the tickets, free the inventory, and
-- withdraw any live listings for those tickets. Each step binds performance_id
-- (step 1 also binds organizer_id).
UPDATE performance p JOIN event e ON e.event_id=p.event_id
SET p.status='CANCELLED', p.cancelled_at=NOW()
WHERE p.performance_id=? AND e.organizer_id=? AND p.status='SCHEDULED';

UPDATE ticket t JOIN orders o ON o.order_id=t.order_id
SET t.status='CANCELLED', t.cancel_type='PERFORMANCE', t.cancelled_at=NOW()
WHERE o.performance_id=? AND t.status='ACTIVE';

DELETE FROM seat_hold WHERE performance_id=?;

UPDATE listing l JOIN ticket t ON t.ticket_id=l.ticket_id JOIN orders o ON o.order_id=t.order_id
SET l.status='WITHDRAWN', l.closed_at=NOW()
WHERE o.performance_id=? AND l.status='ACTIVE';