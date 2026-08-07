-- Reports R4, R7, R8 (Tareq's half). Reference copy of the SQL the app runs, kept
-- readable here so we can re-check it. The graders run these through the
-- Java interface, not this file. Design notes and assumptions are in
-- docs/report_design.md. R1, R2, R3, R5, R6, R9 are Faris's.
--

-- R1: Revenue by city / by venue. "Sold"/revenue count active tickets only
-- (shared rule, see top). Date range filters orders.order_datetime (sale
-- date), not the performance date.

-- R1a: by city (parameters: from, to).
SELECT v.city, COUNT(*) AS tickets_sold, SUM(t.face_value) AS gross_revenue
FROM ticket t
JOIN orders o      ON o.order_id = t.order_id
JOIN performance p ON p.performance_id = o.performance_id
JOIN venue v       ON v.venue_id = p.venue_id
WHERE t.status = 'ACTIVE' AND o.order_datetime >= ? AND o.order_datetime < ?
GROUP BY v.city
ORDER BY gross_revenue DESC;

-- R1b: by venue within one city (parameters: city, from, to).
SELECT v.venue_id, v.name, COUNT(*) AS tickets_sold, SUM(t.face_value) AS gross_revenue
FROM ticket t
JOIN orders o      ON o.order_id = t.order_id
JOIN performance p ON p.performance_id = o.performance_id
JOIN venue v       ON v.venue_id = p.venue_id
WHERE t.status = 'ACTIVE' AND v.city = ? AND o.order_datetime >= ? AND o.order_datetime < ?
GROUP BY v.venue_id, v.name
ORDER BY gross_revenue DESC;


-- R2: Event & performance counts. Segment/genre is event-level so it counts
-- every performance of a matching event; the other three group by venue
-- location. Events don't sum cleanly across country/city/venue groupings for
-- touring events (a performance in two countries counts toward both) --
-- performance counts always do, since a performance has exactly one venue.

-- R2a: per segment & genre.
SELECT sg.segment_name, g.genre_name,
       COUNT(DISTINCT e.event_id) AS events, COUNT(p.performance_id) AS performances
FROM event e
JOIN genre g   ON g.genre_id = e.genre_id
JOIN segment sg ON sg.segment_id = g.segment_id
LEFT JOIN performance p ON p.event_id = e.event_id
GROUP BY sg.segment_name, g.genre_name
ORDER BY sg.segment_name, g.genre_name;

-- R2b: per country.
SELECT v.country, COUNT(DISTINCT e.event_id) AS events, COUNT(p.performance_id) AS performances
FROM performance p
JOIN venue v ON v.venue_id = p.venue_id
JOIN event e ON e.event_id = p.event_id
GROUP BY v.country
ORDER BY v.country;

-- R2c: per country & city.
SELECT v.country, v.city, COUNT(DISTINCT e.event_id) AS events, COUNT(p.performance_id) AS performances
FROM performance p
JOIN venue v ON v.venue_id = p.venue_id
JOIN event e ON e.event_id = p.event_id
GROUP BY v.country, v.city
ORDER BY v.country, v.city;

-- R2d: per country, city & venue.
SELECT v.country, v.city, v.venue_id, v.name,
       COUNT(DISTINCT e.event_id) AS events, COUNT(p.performance_id) AS performances
FROM performance p
JOIN venue v ON v.venue_id = p.venue_id
JOIN event e ON e.event_id = p.event_id
GROUP BY v.country, v.city, v.venue_id, v.name
ORDER BY v.country, v.city, v.name;


-- R3: Organizer revenue ranking. Same active-tickets-only revenue as R1/R7.
-- No date range -- the spec doesn't ask for one here (unlike R1). Ties use
-- DENSE_RANK, same convention as R5/R6.

-- R3a: overall.
SELECT u.user_id AS organizer_id, u.full_name, SUM(t.face_value) AS gross_revenue,
       DENSE_RANK() OVER (ORDER BY SUM(t.face_value) DESC) AS rnk
FROM ticket t
JOIN orders o      ON o.order_id = t.order_id
JOIN performance p ON p.performance_id = o.performance_id
JOIN event e       ON e.event_id = p.event_id
JOIN users u       ON u.user_id = e.organizer_id
WHERE t.status = 'ACTIVE'
GROUP BY u.user_id, u.full_name
ORDER BY rnk, organizer_id;

-- R3b: per country (ranks reset per country).
SELECT v.country, u.user_id AS organizer_id, u.full_name, SUM(t.face_value) AS gross_revenue,
       DENSE_RANK() OVER (PARTITION BY v.country ORDER BY SUM(t.face_value) DESC) AS rnk
FROM ticket t
JOIN orders o      ON o.order_id = t.order_id
JOIN performance p ON p.performance_id = o.performance_id
JOIN venue v       ON v.venue_id = p.venue_id
JOIN event e       ON e.event_id = p.event_id
JOIN users u       ON u.user_id = e.organizer_id
WHERE t.status = 'ACTIVE'
GROUP BY v.country, u.user_id, u.full_name
ORDER BY v.country, rnk, organizer_id;

-- R3c: one city (parameter: city).
SELECT u.user_id AS organizer_id, u.full_name, SUM(t.face_value) AS gross_revenue,
       DENSE_RANK() OVER (ORDER BY SUM(t.face_value) DESC) AS rnk
FROM ticket t
JOIN orders o      ON o.order_id = t.order_id
JOIN performance p ON p.performance_id = o.performance_id
JOIN venue v       ON v.venue_id = p.venue_id
JOIN event e       ON e.event_id = p.event_id
JOIN users u       ON u.user_id = e.organizer_id
WHERE t.status = 'ACTIVE' AND v.city = ?
GROUP BY u.user_id, u.full_name
ORDER BY rnk, organizer_id;


-- R4: Scalper flag (global thresholds, reported per city).
-- A customer qualifies on their total past-year activity: bought at least ten
-- tickets and listed more than half of them. We then show them under every city
-- where they bought, with in-city counts next to the totals. "Listed" means the
-- customer has a listing for a ticket they bought (seller = purchaser).

WITH bought AS (
  SELECT o.customer_id, v.city, t.ticket_id,
         EXISTS (SELECT 1 FROM listing l
                 WHERE l.ticket_id = t.ticket_id
                   AND l.seller_id = o.customer_id) AS listed
  FROM ticket t
  JOIN orders o      ON o.order_id = t.order_id
  JOIN performance p ON p.performance_id = o.performance_id
  JOIN venue v       ON v.venue_id = p.venue_id
  WHERE o.order_datetime >= NOW() - INTERVAL 1 YEAR
),
scalper AS (
  SELECT customer_id, COUNT(*) AS bought_total, SUM(listed) AS listed_total
  FROM bought
  GROUP BY customer_id
  HAVING bought_total >= 10 AND listed_total > bought_total / 2
)
SELECT b.city, b.customer_id, u.full_name,
       s.bought_total, s.listed_total,
       COUNT(*)      AS bought_in_city,
       SUM(b.listed) AS listed_in_city
FROM bought b
JOIN scalper s ON s.customer_id = b.customer_id
JOIN users u   ON u.user_id = b.customer_id
GROUP BY b.city, b.customer_id, u.full_name, s.bought_total, s.listed_total
ORDER BY b.city, s.listed_total DESC, b.customer_id;

-- R5: Customer order ranking. Plain orders count, primary market only
-- (resale never creates an orders row). An order counts even if its tickets
-- were later cancelled -- this is about ordering activity, not revenue.

-- R5a: overall, in a date range (parameters: from, to).
SELECT o.customer_id, u.full_name, COUNT(*) AS order_count,
       DENSE_RANK() OVER (ORDER BY COUNT(*) DESC) AS rnk
FROM orders o
JOIN users u ON u.user_id = o.customer_id
WHERE o.order_datetime >= ? AND o.order_datetime < ?
GROUP BY o.customer_id, u.full_name
ORDER BY rnk, o.customer_id;

-- R5b: per city, past year, customers with >= 2 orders that year.
SELECT v.city, o.customer_id, u.full_name, COUNT(*) AS order_count,
       DENSE_RANK() OVER (PARTITION BY v.city ORDER BY COUNT(*) DESC) AS rnk
FROM orders o
JOIN performance p ON p.performance_id = o.performance_id
JOIN venue v       ON v.venue_id = p.venue_id
JOIN users u       ON u.user_id = o.customer_id
WHERE o.order_datetime >= NOW() - INTERVAL 1 YEAR
GROUP BY v.city, o.customer_id, u.full_name
HAVING COUNT(*) >= 2
ORDER BY v.city, rnk, o.customer_id;

-- R6: Cancellations, past year. Customers attributed by CURRENT owner
-- (design_decisions.md Decision 28), cancel_type = 'CUSTOMER' only (a
-- performance-cancellation-induced cancel isn't the customer's own choice).

-- R6a: customers with most cancelled tickets.
SELECT tow.owner_id AS customer_id, u.full_name, COUNT(*) AS cancelled_count,
       DENSE_RANK() OVER (ORDER BY COUNT(*) DESC) AS rnk
FROM ticket t
JOIN ticket_ownership tow ON tow.ticket_id = t.ticket_id
JOIN users u ON u.user_id = tow.owner_id
WHERE t.status = 'CANCELLED' AND t.cancel_type = 'CUSTOMER'
  AND t.cancelled_at >= NOW() - INTERVAL 1 YEAR
  AND tow.acquired_at = (SELECT MAX(tow2.acquired_at) FROM ticket_ownership tow2 WHERE tow2.ticket_id = t.ticket_id)
GROUP BY tow.owner_id, u.full_name
ORDER BY rnk, customer_id;

-- R6b: organizers with most cancelled performances.
SELECT e.organizer_id, u.full_name, COUNT(*) AS cancelled_count,
       DENSE_RANK() OVER (ORDER BY COUNT(*) DESC) AS rnk
FROM performance p
JOIN event e ON e.event_id = p.event_id
JOIN users u ON u.user_id = e.organizer_id
WHERE p.status = 'CANCELLED' AND p.cancelled_at >= NOW() - INTERVAL 1 YEAR
GROUP BY e.organizer_id, u.full_name
ORDER BY rnk, organizer_id;


-- R7: Sell-through. Shared building block first: for every assigned section of a
-- performance, its sellable capacity and how much of it sold. Reserved sellable is
-- the section's seats minus blocked ones; GA sellable is ga_capacity. Sold is SOLD
-- seat_holds for reserved (cancelling deletes the hold, so these are all active) and
-- active GA tickets for GA. Each assigned section maps to one tier, so this rolls up
-- to a tier or a whole performance.

-- Reusable CTE bundle.
-- WITH
--   assigned : the performance's priced sections and their tier + type
--   sec_seats : physical seat count per reserved section
--   res_holds : sold / blocked reserved seats per performance + section
--   ga_sold : active GA tickets per performance + section
--   section_level : sellable and sold per (performance, section, tier)

-- R7a: sell-through per performance.
WITH assigned AS (
  SELECT pst.performance_id, pst.section_id, pst.tier_id, s.section_type, s.ga_capacity
  FROM performance_section_tier pst
  JOIN section s ON s.section_id = pst.section_id
),
sec_seats AS (
  SELECT sr.section_id, COUNT(*) AS seats
  FROM seat_row sr JOIN seat st ON st.row_id = sr.row_id
  GROUP BY sr.section_id
),
res_holds AS (
  SELECT sh.performance_id, sr.section_id,
         SUM(sh.hold_type = 'SOLD')    AS sold,
         SUM(sh.hold_type = 'BLOCKED') AS blocked
  FROM seat_hold sh
  JOIN seat st     ON st.seat_id = sh.seat_id
  JOIN seat_row sr ON sr.row_id = st.row_id
  GROUP BY sh.performance_id, sr.section_id
),
ga_sold AS (
  SELECT o.performance_id, t.ga_section_id AS section_id, COUNT(*) AS sold
  FROM ticket t JOIN orders o ON o.order_id = t.order_id
  WHERE t.status = 'ACTIVE' AND t.ga_section_id IS NOT NULL
  GROUP BY o.performance_id, t.ga_section_id
),
section_level AS (
  SELECT a.performance_id, a.section_id, a.tier_id,
         CASE WHEN a.section_type = 'RESERVED'
              THEN COALESCE(ss.seats,0) - COALESCE(rh.blocked,0)
              ELSE a.ga_capacity END AS sellable,
         CASE WHEN a.section_type = 'RESERVED'
              THEN COALESCE(rh.sold,0)
              ELSE COALESCE(gs.sold,0) END AS sold
  FROM assigned a
  LEFT JOIN sec_seats ss ON ss.section_id = a.section_id
  LEFT JOIN res_holds rh ON rh.performance_id = a.performance_id AND rh.section_id = a.section_id
  LEFT JOIN ga_sold   gs ON gs.performance_id = a.performance_id AND gs.section_id = a.section_id
)
SELECT p.performance_id, e.title, v.name AS venue, v.city, p.performance_datetime,
       SUM(sl.sellable) AS sellable, SUM(sl.sold) AS sold,
       ROUND(SUM(sl.sold) / NULLIF(SUM(sl.sellable),0), 4) AS sell_through
FROM section_level sl
JOIN performance p ON p.performance_id = sl.performance_id
JOIN event e       ON e.event_id = p.event_id
JOIN venue v       ON v.venue_id = p.venue_id
GROUP BY p.performance_id, e.title, v.name, v.city, p.performance_datetime
ORDER BY sell_through DESC;

-- R7b: sell-through per tier for one performance (parameter: performance_id).
-- Same CTE bundle as R7a, different tail.
WITH assigned AS (
  SELECT pst.performance_id, pst.section_id, pst.tier_id, s.section_type, s.ga_capacity
  FROM performance_section_tier pst
  JOIN section s ON s.section_id = pst.section_id
),
sec_seats AS (
  SELECT sr.section_id, COUNT(*) AS seats
  FROM seat_row sr JOIN seat st ON st.row_id = sr.row_id
  GROUP BY sr.section_id
),
res_holds AS (
  SELECT sh.performance_id, sr.section_id,
         SUM(sh.hold_type = 'SOLD')    AS sold,
         SUM(sh.hold_type = 'BLOCKED') AS blocked
  FROM seat_hold sh
  JOIN seat st     ON st.seat_id = sh.seat_id
  JOIN seat_row sr ON sr.row_id = st.row_id
  GROUP BY sh.performance_id, sr.section_id
),
ga_sold AS (
  SELECT o.performance_id, t.ga_section_id AS section_id, COUNT(*) AS sold
  FROM ticket t JOIN orders o ON o.order_id = t.order_id
  WHERE t.status = 'ACTIVE' AND t.ga_section_id IS NOT NULL
  GROUP BY o.performance_id, t.ga_section_id
),
section_level AS (
  SELECT a.performance_id, a.section_id, a.tier_id,
         CASE WHEN a.section_type = 'RESERVED'
              THEN COALESCE(ss.seats,0) - COALESCE(rh.blocked,0)
              ELSE a.ga_capacity END AS sellable,
         CASE WHEN a.section_type = 'RESERVED'
              THEN COALESCE(rh.sold,0)
              ELSE COALESCE(gs.sold,0) END AS sold
  FROM assigned a
  LEFT JOIN sec_seats ss ON ss.section_id = a.section_id
  LEFT JOIN res_holds rh ON rh.performance_id = a.performance_id AND rh.section_id = a.section_id
  LEFT JOIN ga_sold   gs ON gs.performance_id = a.performance_id AND gs.section_id = a.section_id
)
SELECT pt.tier_code, pt.price,
       SUM(sl.sellable) AS sellable, SUM(sl.sold) AS sold,
       ROUND(SUM(sl.sold) / NULLIF(SUM(sl.sellable),0), 4) AS sell_through
FROM section_level sl
JOIN price_tier pt ON pt.tier_id = sl.tier_id
WHERE sl.performance_id = ?
GROUP BY pt.tier_id, pt.tier_code, pt.price
ORDER BY pt.price DESC;

-- R7c: for a given month, performances by city that sold out or sold under a quarter
-- (parameters: year, month). Same CTE bundle, plus a per-performance rollup that
-- drops zero-sellable performances.
WITH assigned AS (
  SELECT pst.performance_id, pst.section_id, pst.tier_id, s.section_type, s.ga_capacity
  FROM performance_section_tier pst
  JOIN section s ON s.section_id = pst.section_id
),
sec_seats AS (
  SELECT sr.section_id, COUNT(*) AS seats
  FROM seat_row sr JOIN seat st ON st.row_id = sr.row_id
  GROUP BY sr.section_id
),
res_holds AS (
  SELECT sh.performance_id, sr.section_id,
         SUM(sh.hold_type = 'SOLD')    AS sold,
         SUM(sh.hold_type = 'BLOCKED') AS blocked
  FROM seat_hold sh
  JOIN seat st     ON st.seat_id = sh.seat_id
  JOIN seat_row sr ON sr.row_id = st.row_id
  GROUP BY sh.performance_id, sr.section_id
),
ga_sold AS (
  SELECT o.performance_id, t.ga_section_id AS section_id, COUNT(*) AS sold
  FROM ticket t JOIN orders o ON o.order_id = t.order_id
  WHERE t.status = 'ACTIVE' AND t.ga_section_id IS NOT NULL
  GROUP BY o.performance_id, t.ga_section_id
),
section_level AS (
  SELECT a.performance_id, a.section_id, a.tier_id,
         CASE WHEN a.section_type = 'RESERVED'
              THEN COALESCE(ss.seats,0) - COALESCE(rh.blocked,0)
              ELSE a.ga_capacity END AS sellable,
         CASE WHEN a.section_type = 'RESERVED'
              THEN COALESCE(rh.sold,0)
              ELSE COALESCE(gs.sold,0) END AS sold
  FROM assigned a
  LEFT JOIN sec_seats ss ON ss.section_id = a.section_id
  LEFT JOIN res_holds rh ON rh.performance_id = a.performance_id AND rh.section_id = a.section_id
  LEFT JOIN ga_sold   gs ON gs.performance_id = a.performance_id AND gs.section_id = a.section_id
),
perf_rate AS (
  SELECT sl.performance_id, SUM(sl.sellable) AS sellable, SUM(sl.sold) AS sold,
         SUM(sl.sold) / NULLIF(SUM(sl.sellable),0) AS rate
  FROM section_level sl
  GROUP BY sl.performance_id
  HAVING SUM(sl.sellable) > 0
)
SELECT v.city, pr.performance_id, e.title, p.performance_datetime,
       pr.sellable, pr.sold, ROUND(pr.rate,4) AS rate,
       CASE WHEN pr.rate >= 1 THEN 'SOLD OUT' ELSE 'UNDER QUARTER' END AS flag
FROM perf_rate pr
JOIN performance p ON p.performance_id = pr.performance_id
JOIN venue v       ON v.venue_id = p.venue_id
JOIN event e       ON e.event_id = p.event_id
WHERE YEAR(p.performance_datetime) = ? AND MONTH(p.performance_datetime) = ?
  AND (pr.rate >= 1 OR pr.rate < 0.25)
ORDER BY v.city, flag, rate;


-- R8: Resale report. A completed resale is a SOLD listing. Markup is the dollar
-- amount over face value. "At cap" is list_price = face_value * cap% / 100, rounded
-- to cents. The at-cap fraction is over completed resales, matching the other stats.

-- R8a: per-event resale stats (all-time).
SELECT e.event_id, e.title,
       COUNT(*) AS completed_resales,
       ROUND(AVG(l.list_price - t.face_value), 2) AS avg_markup,
       ROUND(SUM(ROUND(l.list_price,2) = ROUND(t.face_value * e.resale_cap_pct/100, 2)) / COUNT(*), 4) AS frac_at_cap
FROM listing l
JOIN ticket t      ON t.ticket_id = l.ticket_id
JOIN orders o      ON o.order_id = t.order_id
JOIN performance p ON p.performance_id = o.performance_id
JOIN event e       ON e.event_id = p.event_id
WHERE l.status = 'SOLD'
GROUP BY e.event_id, e.title
ORDER BY completed_resales DESC, e.event_id;

-- R8b: top 10 events by resale volume in a period (parameters: from, to).
SELECT e.event_id, e.title, COUNT(*) AS resale_volume
FROM listing l
JOIN ticket t      ON t.ticket_id = l.ticket_id
JOIN orders o      ON o.order_id = t.order_id
JOIN performance p ON p.performance_id = o.performance_id
JOIN event e       ON e.event_id = p.event_id
WHERE l.status = 'SOLD' AND l.closed_at >= ? AND l.closed_at < ?
GROUP BY e.event_id, e.title
ORDER BY resale_volume DESC, e.event_id
LIMIT 10;
