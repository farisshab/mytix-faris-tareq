# MyTix Query Design Notes

Design choices for the queries and reports, kept separate from the schema notes
(design_decisions.md). Same convention as the design decisions doc, casual but precise. Each choice is here to keep physical notes so we can come back to it and stay consistent while we build.

Ownership on the query side (Phase 3): Tareq has Q4-Q7 and the organizer ops;
Faris has Q1-Q3 and the customer ops. (Tentative as of 08/01)

## Q1-Q3 - Location searches

The three location-based searches: neabry by distance, by postal code, and by exact address. All three reuse `perf-avail` (from Decision 8).

**Decision 18 - distance in km via the standard Haversine formula, with the argument clamped to [-1, 1].**
MySQL has no built-in function for plain lat/long, so it's the formula written directly in SQL:
```
6371 * ACOS(COS(lat1)*COS(lat2)*COS(lng2-lng1) + SIN(lat1)*SIN(lat2))
```
all in radians, where 6371 = Earth's radius im km.
Due to floating point rounding, the ACOS argument can reach slightly outside of [-1, 1], so we wrap everything in `LEAST(1, GREATEST(-1, ...))`

**Decision 19 - default search radius is 50km, user can override**
The project handout specified that the user should have a choice of the distance along with a default provided.
In our case, the default radius will be 50km.

**Decision 20 - ranking mode is chosen in Java, not passed as raw SQL**
The spec sheet asked for three ranking behaviours, with nearest-first being default. Rather than building the order from raw user input, the menu choice maps to one of three hardcoded ORDER BY strings in Java before we build the query.

**Decision 21 - a sold-out (or not-yet-priced) performance sorts last, in either price direction.**
`cheapest_price` comes from `perf_avail`, which only has a row for a performance with at least one priced, available section. A performance with no tiers assigned yet, or one that's sold out, has no row there, so the LEFT JOIN gives NULL, which causes these performances to appear at the top of the list. `ORDER BY cheapest_price IS NULL, cheapest_price ASC/DESC` forces NULLs to be at the end.

**Decision 22 - adjacent postal code is one with the same first 2 characters (also covers same postal code)**
The project handout specifies adjacency, but does not define it. True adjacency is not achievable from the postal code string itself without an external geographic table. The assumption is that a postal code's first two characters are within the same region, so this covers both adjacent postal codes, and the postal code specified itself.

**Decision 23 - Q3 is an exact string match on `venue.address`**
The project handout calls it an "exact search". If nothing matches exactly, the query returns zero rows and the app says so rather than guessing a near match.

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


## Organizer operations - creating and managing events

The write side of the organizer's world: making events and performances, pricing
them, blocking seats, and cancelling. Two ideas run through all of them - only the
event's own organizer may touch it, and each operation either fully happens or not
at all.

**Decision 12 - who's allowed is checked inside the statement, not before it.**
Every organizer op has an owner rule: the spec is firm that only the organizer of an
event can cancel its performances, and the same goes for pricing and blocking. The
signed-in user (from Faris' login) gives us the acting user_id. Rather than run a
SELECT to check ownership and then a second statement to do the work, we fold the
ownership check straight into the UPDATE/DELETE itself, e.g.
`... WHERE tier_id = ? AND e.organizer_id = ?`. If the user isn't the owner the
statement simply touches zero rows, and we read that "0 rows" as "not your event."
This closes the little gap where something could change between the check and the
action, and it means an op physically cannot alter a row it doesn't own. (Depends on
the session handing us the current user_id, got to line up with Faris.)

**Decision 13 - one operation, one transaction.**
The spec keeps saying "all suitable information should be updated," which is really
asking for all-or-nothing. So each operation is wrapped in a single transaction
(autocommit off, commit at the end, roll back on any error or refused rule). Most
ops are one single statement anyway, but these two have to be atomic: pricing and cancelling a performance (its tiers plus every section assignment, and the cascade below). A performance should never be half-priced or half-cancelled.

**Decision 14 - the create-and-price flow, done in incremental steps.**
Building a sellable performance is a handful of separate operations, one per menu
item on Faris' organizer menu: create the event (organizer from the session, title,
genre, and the resale cap, which lives on the event), add a bare performance to it
(venue + date), define its price tiers, assign its sections to those tiers, and set
or change the resale cap. Each is its own small operation rather than one big guided
flow.

(This is a change from an earlier draft that made pricing one atomic op that rolled
back unless every section was assigned in a single go. I switched to the
incremental version because it matches the menu Faris already built and the way the
spec itself lists the steps, and because a half-priced performance isn't actually
broken data, it's just a setup still in progress. Booking only ever offers sections
that have a tier, so an unassigned section simply isn't sellable yet, very manageable.)

To keep that from biting us later, the assign-sections op ends by checking
completeness and warning if any section still has no tier ("2 sections have no tier
yet and can't be sold until you assign them") rather than leaving it silent. The
composite foreign key still guarantees each assignment's tier belongs to this
performance, so we never recheck that part.

**Decision 15 - updating a tier's price, and when we refuse.**
An organizer can change a tier's price on a future performance, but only if nothing
has sold in that tier yet. "Sold in that tier" is really about the tier's sections: a
tier maps to sections, and a ticket counts if its section is one of them (a reserved
ticket's section comes from its seat, a GA ticket's from ga_section_id). So the check
is "is there any active ticket for this performance in a section that belongs to this
tier." We do the change as one guarded UPDATE; owner check, future performance, and
NOT EXISTS(a sale in this tier), all in the WHERE, so a sale sneaking in at the last
second just makes the update match nothing, no race. Since "zero rows changed"
doesn't say why it was refused, if that happens we run a small read-only follow up to
work out the reason (past show? not owner? already sold in that tier? etc.) purely so we can give the organizer a clear message.

**Decision 16 - blocking and unblocking a seat.**
Blocking a seat is just an insert into seat_hold as a BLOCKED row, and the seat_hold
primary key does the hard part for free: if the seat is already sold or blocked, the
insert clashes on the key and fails, which is exactly the spec's "you can't block a
sold seat." We write it as INSERT ... SELECT so the organizer's ownership is checked
in the same statement (the SELECT only produces a row if they own the event and the
show is a scheduled, future one). Two outcomes, two messages: a key clash means the
seat is sold or already blocked; zero rows inserted means it wasn't their event.
Unblocking is a DELETE scoped to BLOCKED rows only, so you can never "unblock" a sold
seat (freeing a sold seat only ever happens through a cancellation). Blocking is
reserved-only, since GA has no seats to block.

**Decision 17 - cancelling a performance, the full cascade.**
The organizer can cancel a whole performance at any time, and it has to leave everything tidy. One transaction, in order:
1. Flip the performance to CANCELLED (this UPDATE also carries the owner check and the
   "not already cancelled" check, so it's the gate for the whole thing; if it touches
   zero rows we roll back and refuse).
2. Turn every still-active ticket into CANCELLED with cancel_type PERFORMANCE (tickets
   a customer already cancelled keep their CUSTOMER reason).
3. Delete all of the performance's seat_holds, sold and blocked alike, which is the
   spec's "mark the seats available again."
4. Withdraw any active resale listings for those tickets, so nobody buys a listing for
   a ticket that no longer exists.

Refunds are implied; a cancelled ticket is itself the refund record, and the
cancellation is "recorded" by the performance's status and each ticket's cancel_type.
Step 4 isn't spelled out in the spec, but a live listing pointing at a cancelled
ticket is a loose end worth closing.
