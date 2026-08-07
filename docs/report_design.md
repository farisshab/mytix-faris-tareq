# MyTix Report Design Notes

Insight into design choices for the reports and the organizer toolkit, kept separate from the
schema notes and the query notes. Written as a note to future us. None of these change the schema; they are all query-side.

Ownership on the report side (Phase 4):

- Tareq: R4, R7, R8, and the organizer toolkit (resale and seat capacity).
- Faris: R1, R2, R3, R5, R6, R9 (the revenue/counting spine and the text report).

R1, R3, and R5 all sit on the same `order -> performance -> venue` group-by, which
is why they went to one person. R4/R7/R8 all reuse the `listing` table and the
availability CTE from Q4-Q7. A couple of R5 notes for Faris are at the bottom.


## Shared rule: what "gross revenue" and "sold" count

Both count active tickets only (`ticket.status = 'ACTIVE'`). A cancelled ticket was
fully refunded (schema decision 23), so counting its money would overstate what the
organizer actually kept, and its seat is back in the pool so it is not really sold.
Same rule everywhere it comes up: R1 and R3 on Faris's side, R7 on mine.

## R1: Revenue by city / by venue
Two variants, same shared rule as everywhere else: "sold" and "gross revenue"
count active tickets only (a cancelled ticket was refunded, so its money
doesn't really belong to the organizer)

- The date range filters `orders.order_datetime` (when the sale happened),
  not the performance date. This shows when the money actually came in, not
  when the show happens.
- By-venue is scoped to one city at a time (prompted)

## R2: Event & performance counts

Four groupings: segment+genre, country, country+city, and country+city+venue.
Segment/genre is an event-level attribute (`event.genre_id`), so that
grouping counts every performance of a matching event regardless of where it happens.
The other three are venue-location groupings, joined performance -> venue.

- Events: `COUNT(DISTINCT event_id)`. Performances: a plain row count, since
  every performance belongs to exactly one venue and can't be double-counted.
- Worth knowing, not a bug: event counts do NOT sum cleanly from a more specific grouping
  to a more broad one, at ANY level of this hierarchy (city -> country, or country -> everything).
  The rule is: `SUM(events per subgroup) >= events in the parent group`, and the gap is the count
  of events that span more than one subgroup.
    - For example, country level (verified on the real data): 24 total distinct events vs. 17 (Canada) + 13 (USA) = 30.
      A gap of 6 events touring across both countries (e.g. Arctic Monkeys: World Tour has performances in both).
- Cancelled performances are still counted here.

## R3: Organizer revenue ranking

Same active-tickets-only revenue definition as R1 (and R7 on Tareq's side).
No date range.


## R4: Scalper flag

Find the scalpers, then report them by city. A customer qualifies as a scalper on
their total past-year activity: they bought at least ten tickets and listed more
than half of them. The report then lists each scalper under every city where they
bought, showing their in-city counts next to the totals.

- "Listed" means the customer has a `listing` row (any status: ACTIVE, SOLD, or
  WITHDRAWN) for a ticket they bought, matching the listing's seller to the
  purchaser. The spec says listed, not sold: the intent to flip is the signal, and
  the system is meant to flag this before a sale even happens.
- The 10-and-half thresholds are global, over all their past-year tickets, not per
  city. We first read this as a per-city test, but the more natural reading of
  "listed more than half of all the tickets they purchased, provided they purchased
  at least ten" is that the counts are over all their tickets, with "for every city"
  describing how the output is grouped. It also fits the sample data, where the
  planted scalpers spread their buys across a few cities and would slip past a
  per-city threshold.
- "Purchased" is a primary-market ticket (from an `orders` row). Past year is the
  order date within a rolling 365 days. City is the venue's city.

## R5: Customer order ranking

- Order count is a plain count of `orders` rows -- primary market only.
  Note: A resale purchase never created an `orders` row (per design decision).
- Each order maps to exactly one city via `orders.performance_id ->
  performance.venue_id -> venue.city`, so per-city counting can't double-count
  an order
- An order counts even if its tickets were later cancelled

## R6: Cancellations, past year

- **Customers with most cancelled tickets**: attributed to the ticket's CURRENT owner
  (latest row in `ticket_ownership`), not whoever originally placed the order.
  Since the right to cancel follows the current owner, the ticket cancellation
  should count towards the owner.
- Only `cancel_type = 'CUSTOMER'` counts, because the *organizer* cancelling a
  performance should not count toward the customer.
- **Organizers with most cancelled performances**: staightforward --
  `performance.status = 'CANCELLED'` within the past year, grouped by
  `event.organizer_id`.


## R7: Sell-through

For each performance, the fraction of its sellable capacity that sold. Also per
tier. And, for a given month, the performances by city that sold out or sold under
a quarter.

- Sellable capacity: the reserved seats in the performance's assigned, non-blocked
  sections, plus the GA capacity of its assigned GA sections.
- Sold counts active only, per the shared rule. Reserved sold is a `seat_hold` row
  with `hold_type = 'SOLD'` whose ticket is active; GA sold is the count of active
  GA tickets. Rate = sold/sellable.
- Per tier is the same math scoped to the sections in one tier.
- Monthly city report: a performance belongs to the given month by its
  `performance_datetime` (the show date, not the sale date). Sold out is rate >=
  100%, under a quarter is rate < 25%, grouped by the venue's city.
- Performances with zero sellable capacity (no sections assigned yet) are excluded for
  the sake of meaningful reporting.


## R8: Resale report

Per event, some resale stats, plus a top-10 events list for a period.

- A completed resale is a `listing` with status SOLD (it has a buyer and a close
  time). Active and withdrawn listings do not count.
- Per event: the number of completed resales, the average dollar markup
  (`avg(list_price - face_value)`), and the fraction of completed resales priced
  exactly at the cap.
- Markup is a dollar amount, not a percentage. Assumption: the spec says "markup
  over face value" with no unit and points at the dollar figure, so we choose to report
  dollars.
- "At cap" means `list_price` equals `face_value * resale_cap_pct / 100`, compared
  rounded to cents (schema decision 26).
- The "fraction at cap" is over completed resales, matching the other two per-event
  figures, so all three describe the same population.
- Top 10 events by resale volume in a given period: volume is the count of completed
  resales whose close time falls in the period, highest first. This part is
  period-bounded; the per-event stats above are all-time.


## Organizer toolkit

Suggests how to price and structure a new performance, based on comparable past
performances. Three parts: pick the comparables, turn them into a suggestion, and
(save for later) estimate the revenue change for a price tweak.

**Picking comparables.** Past, non-cancelled performances whose event is the same
genre, in a venue of similar capacity (within +/-40% of the new venue's capacity),
same city, within the last 24 months. Genre is the one filter we never relax: a jazz
show should not be priced off a stadium rock tour. If fewer than 5 comparables
match, widen in this order until we clear 5: city -> country -> anywhere, then the
capacity band to +/-60% and then +/-100%, then recency to 36 months. If even the
loosest filter finds nothing, fall back to a flat default and say so. We show which
rung we landed on so the suggestion is transparent ("based on 7 comparable rock
performances in Toronto").

**The idea.**
- Number of tiers: the median tier count of the comparables, fallback 3, clamped to
  1..4 since that covers real practice. We count only tiers that have sections
  assigned, so the tier count always agrees with the capacity shares below.
- Prices: pool the face values of sold (active) tickets across the comparables,
  split that pool into K evenly spaced percentile bands, and take the median of each
  band as the price for that tier. Premium is the top band. Round to the nearest $5
  so it reads like real pricing. Basing this on tickets that actually sold means
  demand is already baked in, rather than trusting list prices that may not have
  moved. If two bands collapse to the same price (a genre that sold almost
  everything at one price), we nudge the lower tier down so tiers stay distinct.
- Capacity shares: for each comparable, the fraction of its capacity in its top
  tier, next tier, and so on; average those across comparables. These are targets;
  the organizer still maps whole sections, so they approximate.
- No extra scaling for venue size or city beyond what the comparable filter already
  does.

**Revenue-change estimate (extra credit, worry about it later).** For a price change 
on a tier, estimate the expected revenue change with a constant-elasticity demand model:
`Q(P) = Q0 * (P/P0)^e`, revenue is `P * Q`. Estimate the elasticity `e` by
regressing `log(sell-through)` on `log(price)` across the comparable tiers (using
sell-through normalizes for venue size); if there are too few points or the fit is
degenerate, fall back to `e = -1.2` and flag it. Two guards: predicted quantity is
clamped to the tier's capacity (a price cut cannot sell more seats than exist), and
for a brand-new performance the baseline quantity is itself predicted from
comparable sell-through, not observed, so the estimate is directional guidance, not
a promise. Deferred until the core reports and the base suggestion work.

**Where it runs.** In `OrganizerToolkitMenu`. SQL pulls the comparable rows,
Java does the stats.


## For Faris: R5 notes (yours to finalize)

- Number of orders is a count of `orders` rows, primary market only. Buying a resale
  is not an order (schema decision 27).
- Each order sits in exactly one city, since `orders.performance_id` is NOT NULL and
  a performance has one venue and one city, so per-city counting never
  double-counts.
- The overall ranking takes a user-supplied date range; the per-city ranking uses
  the past-year window and only ranks customers with at least 2 orders that year.
- Ties: DENSE_RANK.
- An order counts as placed even if it was later cancelled, since R5 is about
  ordering activity, not revenue.


## Build order

R4, R7, and R8 first (SQL verified against the loaded data, then the Java to run
them from the reports menu), then the toolkit's base suggestion, then the
extra-credit revenue estimate last.
