# MyTix Query Design Notes

Design choices for the queries and reports, kept separate from the schema notes
(design_decisions.md). Same convention as the design decisions doc, casual but precise. Each choice is here to keep physical notes so we can come back to it and stay consistent while we build.

Ownership on the query side (Phase 3): Tareq has Q4-Q7 and the organizer ops;
Faris has Q1-Q3 and the customer ops. (Tentative as of 08/01)


## Q6 - Seat-map summary

For a given performance, one row per section: its tier and price, how many seats
are available (or GA capacity remaining), how many are sold, and how many are
blocked.

**Decision 1 - one unified query, written as `reserved-half UNION ALL GA-half`.**
Both section types come back in a single result:
```
section_name | section_type | tier_code | price | available | sold | blocked
```
Each half is simple and readable on its own, and it keeps the whole thing in one
SQL statement (to be applied in Java) rather than stitching two result sets together
in Java. Cleaner than one giant CASE query.

**Decision 2 - sold and available are current/active, not historical.**
The seat map shows what is bookable right now, after cancellations.
- Reserved: counts come straight off `seat_hold` (it carries `performance_id`).
  SOLD rows -> sold, BLOCKED rows -> blocked, and
  `available = total section seats - sold - blocked`. This is automatic: a
  cancellation deletes the seat_hold row, so a freed seat returns to available
  with no additional work.
- GA: a ticket has no `performance_id` (schema Decision 15b), so GA sold is
  counted through `ticket -> orders -> performance`, filtered to
  `status = 'ACTIVE'` so cancelled GA tickets don't count. GA has no blocking, so
  `blocked = 0` and `available = ga_capacity - active GA tickets`.

Why active-only? If cancelled tickets counted as sold, the map would understate
availability and contradict the booking op, which can resell a cancelled seat.


## Q7 - Best available (q consecutive seats)

Given a performance, a count q, and an optional budget, find the q consecutive
seats in the same row (consecutive seat numbers) with the lowest total price, if
such seats exist.

**Decision 3 - "consecutive" means strict integer-consecutive available seats in
one row.** Seats n, n+1, ..., n+q-1, same row, all available (not SOLD, not
BLOCKED). We made `seat_number` an INT precisely so this is well defined, and our
sections number seats 1..N, so integer-adjacency = physical adjacency.

**Decision 4 - pricing shortcut: rank by cheapest qualifying row.** Decided that every seat in a row shares one price (a row sits in one section, a section maps to one tier for the performance). So any q consecutive seats in a row cost `q * price` - the total depends only on which row, not which block within it. "Lowest total" therefore collapses to "cheapest qualifying row," so Q7 prices rows, not every possible block. To be explained in later reports.

**Decision 5 - output is one summary row; ties broken deterministically.**
```
section_name | row_name | start_seat | end_seat | seats (q) | price_each | total_price
```
Enough to read the answer and to book the exact seats. Tie-break order:
`total_price -> section_name -> row_name -> start_seat`, then LIMIT 1, so the
result is reproducible (matters for grading and our fixed-seed data). If nothing
qualifies, the query returns zero rows (the app says "no block found").

**Decision 6 - technique: gaps-and-islands with a window function.**
1. Base set = available reserved seats for the performance (seats in the venue's
   reserved sections, minus any seat with a `seat_hold` row for that performance),
   joined out to the section's tier price.
2. Row key within each row:
   `seat_number - ROW_NUMBER() OVER (PARTITION BY row_id ORDER BY seat_number)`
   is constant across a run of consecutive available seats and jumps at each
   sold/blocked gap. `(row_id, island_key)` identifies each run.
3. `GROUP BY row_id, island_key HAVING COUNT(*) >= q`. The block is
   `[MIN(seat_number), MIN(seat_number) + q - 1]`.
4. Rank the qualifying islands within rows by the tie-break rule and LIMIT 1.
Chosen over a q-dependent self-join: one window function handles any q, reads as
a clean CTE pipeline, and MySQL 8 window functions/CTEs are allowed.

**Decision 7 - budget is optional, and it caps the whole block, not each seat.**
A customer either names a budget or leaves it out, so we treat it as an optional
filter rather than a required one. Since every seat in the block costs the same,
the total is just `q * price`, and the budget check is simply `q * price <= budget`.
We apply it before we rank, so a block the customer can't afford never even enters
the running. In SQL that's the usual nullable-parameter trick,
`(? IS NULL OR ? >= q * price)`; when no budget is passed the clause quietly does
nothing. And if nothing qualifies, whether because no row has a long enough run or
none of them fit the budget, the query simply returns nothing and the app says "no
block found." The awkward looking edge cases take care of themselves: asking for a
single seat (q=1) just returns the cheapest open seat, and a general-admission-only
show returns nothing, since it has no rows to line consecutive seats up in.


## Q4 and Q5 - Searching and filtering performances

Q5 is the big "filter performances by anything, in any combination" search. Q4 is
a smaller cousin: the same kind of search, but specifically a date range plus a
minimum number of available tickets, layered on top of the location searches
(Q1-Q3). They share a lot, so we let them share a core.

**Decision 8 - both lean on one shared "availability" summary per performance.**
Almost every filter here needs to know two things about a show: how many tickets
are still open, and what the cheapest open ticket costs. Rather than work that out
separately in each query, we compute it once. Basically Q6's math, but rolled up
across every performance instead of just one. For each show we get:
- `available_count`: total open tickets (open reserved seats + leftover GA capacity),
- `cheapest_price`: the price of the cheapest section that still has something open,
- two flags for whether any reserved or any GA availability is left.

We can keep this as a shared view so Q1, Q4 and Q5 all read from the same
definition (rememebr to confirm with Faris, since his Q1 ranking will also want the
"cheapest available ticket").

**Decision 9 - "any combination" is one query, not hand-built SQL strings.**
Q5 lets the user mix and match filters (city, segment, genre, date range, price
range, minimum availability, reserved vs GA) however they like. The clean way to do
that in a single statement is to make every filter optional with the pattern
`(? IS NULL OR <the filter>)`: if the caller leaves a filter blank we pass NULL and
that line does nothing, otherwise it applies. So one query covers every combination,
we never glue SQL together by hand (can get quite buggy), and it stays a single call from Java.

**Decision 10 - what the filters actually mean.**
- "Cheapest available ticket in a price range" filters on `cheapest_price`: the
  cheapest ticket you could actually buy right now, not the cheapest tier on paper.
- "At least N available" filters on `available_count`, counting open reserved seats
  and remaining GA capacity together.
- "Reserved or general admission" keeps shows that still have something open of that
  type, using the two availability flags.
- By default we only show upcoming, non-cancelled performances, since a search is
  about what you can still go to.

**Decision 11 - Q4 is Q5's date-and-availability filters bolted onto a location
search.**
Q4's own contribution is small: a date range and a minimum-availability
count. What makes it "Q4" rather than "Q5" is that it refines the location searches
Q1-Q3 (nearby by distance, by postal code, by exact address), which are Faris'. So
Q4 = our availability core + date range + minimum available, AND-ed with whichever
location filter he builds. Marked as a coordination point, so I'll reuse his distance
and postal logic instead of writing my own.
