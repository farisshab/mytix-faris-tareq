# MyTix Design Notes

Our running record of the data model: the tables, their keys, the functional
dependencies, and the reasoning behind each call. It feeds straight into
report.pdf (the schema, keys, FDs, normal-form justification, and the
"conceptual problems and assumptions" sections), so it's worth keeping honest.

A couple of conventions we stick to:

- Every table has a surrogate `<thing>_id` integer primary key, plus a unique
  constraint on its natural key. More on why in decision 1.
- In `sql/schema.sql`, two tables are renamed to dodge MySQL reserved words:
  `order` becomes `orders`, and `row` becomes `seat_row`. Same design, just
  safe to type. The names below use the design-note spelling.

Status: all 20 tables are designed, and schema.sql + drop.sql are written and
smoke-tested (every table builds, key constraints verified) as of Jul 25.


## The tables

### Users and cards
```
users(user_id, full_name, address, email, date_of_birth, role, is_deleted)
  PK user_id
  UNIQUE(email)
  role is CUSTOMER or ORGANIZER

credit_card(card_id, customer_id, card_number, cardholder_name,
            expiry_month, expiry_year)
  PK card_id
  FK customer_id -> users
```

### Venues, sections, rows, seats
```
venue(venue_id, name, latitude, longitude, address, postal_code, city, country)
  PK venue_id

section(section_id, venue_id, section_name, section_type, ga_capacity)
  PK section_id
  UNIQUE(venue_id, section_name)
  section_type is RESERVED or GA
  CHECK: GA sections set ga_capacity; reserved sections leave it null

seat_row(row_id, section_id, row_name)
  PK row_id
  UNIQUE(section_id, row_name)

seat(seat_id, row_id, seat_number)
  PK seat_id
  UNIQUE(row_id, seat_number)
```

### Taxonomy and artists
```
segment(segment_id, segment_name)          e.g. Music, Arts & Theatre, Sports
  PK segment_id
  UNIQUE(segment_name)

genre(genre_id, segment_id, genre_name)    e.g. Rock, Musical, Basketball
  PK genre_id
  UNIQUE(segment_id, genre_name)

artist(artist_id, artist_name, artist_type)   artist_type is INDIVIDUAL or TEAM
  PK artist_id

event_artist(event_id, artist_id, billing_order)
  PK (event_id, artist_id)
  billing_order is HEADLINER, SPECIAL_GUEST, or OPENING_ACT
```

### Events and performances
```
event(event_id, organizer_id, title, genre_id, resale_cap_pct)
  PK event_id
  FK organizer_id -> users, genre_id -> genre
  points at a genre only, never a segment (see decision 6)

performance(performance_id, event_id, venue_id, performance_datetime,
            status, cancelled_at)
  PK performance_id
  FK event_id -> event, venue_id -> venue
  status is SCHEDULED or CANCELLED
```

### Pricing: tiers and section-to-tier
```
price_tier(tier_id, performance_id, tier_code, price)
  PK tier_id
  UNIQUE(performance_id, tier_code)    'P1', 'P2' within a performance
  UNIQUE(performance_id, tier_id)      target for the assignment's composite FK

performance_section_tier(performance_id, section_id, tier_id)
  PK (performance_id, section_id)
  FK section_id -> section
  FK (performance_id, tier_id) -> price_tier(performance_id, tier_id)
```

### Orders, tickets, and live seat inventory
```
orders(order_id, customer_id, performance_id, order_datetime,
       pay_card_number, pay_cardholder, pay_expiry)
  PK order_id
  FK customer_id -> users, performance_id -> performance
  pay_* is a snapshot of the card used (see decision 17)

ticket(ticket_id, order_id, seat_id, ga_section_id, face_value,
       status, cancelled_at, cancel_type)
  PK ticket_id
  FK order_id -> orders, seat_id -> seat, ga_section_id -> section
  CHECK: exactly one of seat_id / ga_section_id is set (reserved vs GA)
  status is ACTIVE or CANCELLED; cancel_type is CUSTOMER or PERFORMANCE

seat_hold(performance_id, seat_id, hold_type, ticket_id)
  PK (performance_id, seat_id)
  FK performance_id -> performance, seat_id -> seat, ticket_id -> ticket
  hold_type is SOLD or BLOCKED
  CHECK: SOLD rows carry a ticket_id, BLOCKED rows don't
```

### Resale and ownership
```
ticket_ownership(ownership_id, ticket_id, owner_id, acquired_at, acquired_via)
  PK ownership_id
  UNIQUE(ticket_id, acquired_at)
  acquired_via is PURCHASE or RESALE

listing(listing_id, ticket_id, seller_id, list_price, status,
        created_at, closed_at, buyer_id)
  PK listing_id
  UNIQUE(active_listing_key)    one active listing per ticket, via a generated column
  status is ACTIVE, SOLD, or WITHDRAWN
```

### Reviews
```
review(review_id, customer_id, performance_id,
       event_rating, venue_rating, comment_text, created_at)
  PK review_id
  UNIQUE(customer_id, performance_id)    one review per performance attended
  CHECK: event_rating and venue_rating are each between 1 and 5
```


## Functional dependencies

```
venue_id                 -> name, latitude, longitude, address, postal_code, city, country
section_id               -> venue_id, section_name, section_type, ga_capacity
(venue_id, section_name) -> section_id
row_id                   -> section_id, row_name
(section_id, row_name)   -> row_id
seat_id                  -> row_id, seat_number
(row_id, seat_number)    -> seat_id

segment_id               -> segment_name
genre_id                 -> segment_id, genre_name
artist_id                -> artist_name, artist_type
(event_id, artist_id)    -> billing_order
event_id                 -> organizer_id, title, genre_id, resale_cap_pct
performance_id           -> event_id, venue_id, performance_datetime, status, cancelled_at

tier_id                      -> performance_id, tier_code, price
(performance_id, tier_code)  -> tier_id
(performance_id, section_id) -> tier_id
tier_id                      -> performance_id   (holds inside performance_section_tier)

user_id                  -> full_name, address, email, date_of_birth, role, is_deleted
email                    -> user_id
card_id                  -> customer_id, card_number, cardholder_name, expiry_month, expiry_year
order_id                 -> customer_id, performance_id, order_datetime, pay_card_number, pay_cardholder, pay_expiry
ticket_id                -> order_id, seat_id, ga_section_id, face_value, status, cancelled_at, cancel_type

(performance_id, seat_id) -> hold_type, ticket_id
ticket_id                 -> performance_id, seat_id   (for SOLD holds; a 2nd candidate key of seat_hold)

ownership_id             -> ticket_id, owner_id, acquired_at, acquired_via
(ticket_id, acquired_at) -> owner_id, acquired_via
listing_id               -> ticket_id, seller_id, list_price, status, created_at, closed_at, buyer_id
review_id                -> customer_id, performance_id, event_rating, venue_rating, comment_text, created_at
(customer_id, performance_id) -> event_rating, venue_rating, comment_text, created_at
```


## Normal form

Every relation is in BCNF except one: `performance_section_tier`, which we keep
in 3NF on purpose (decision 10). In each BCNF table the only nontrivial
dependencies are the candidate keys themselves, surrogate or natural, so there's
nothing left to decompose.


## Design decisions

1. **Surrogate key plus a unique natural key on every table.** The surrogate id
   gives other tables one clean column to point at; the unique constraint keeps
   the real identity rule, like "a seat is unique by number within its row."
   That unique natural key is also the candidate-key FD we cite in the
   normalization writeup, so the DDL and the theory are the same fact said twice.

2. **Rows get their own table.** Q7 ("q consecutive seats in the same row")
   turns into a clean group-by on `row_id` instead of grouping on text, and it
   keeps `row_id -> row_name` out of the seat table so nothing is duplicated.

3. **One `section` table with a type flag, not subtype tables.** Keeps
   `section_id` a single FK target and makes "is this GA?" a cheap column read
   across Q5, Q6, and booking. The check ties `ga_capacity` to the type; the
   "GA has no rows" rule is handled in application code.

4. **Venue and datetime live on `performance`, and inventory anchors on
   `performance_id`.** That's what makes a tour (one event, many venues)
   representable, and what lets the same seat fall in different tiers for
   different performances, since the tier assignment is keyed by performance.

5. **city, country, postal_code, and address live on `venue`, never on
   `performance`.** We have `performance_id -> venue_id` and
   `venue_id -> city, country`. If we stored city on `performance`, the
   dependency `venue_id -> city` would sit inside the performance table with
   `venue_id` not a superkey and `city` not prime, which is a transitive
   dependency and breaks 3NF. So we keep them on the venue and reach them by a
   join. Same story for postal_code and address.

6. **`event` points at `genre` only, not `segment`.** Same transitive-dependency
   trap: `event_id -> genre_id -> segment_id`. Storing `segment_id` on the event
   would break 3NF and let in contradictory rows like genre Rock under segment
   Sports. Segment comes from `genre -> segment`, and genre names are unique
   within a segment, using the Ticketmaster taxonomy labels.

7. **One `artist` table for people and teams, with billing at the event level.**
   A headliner and a home team are the same structural thing (a named performer
   an event features), so one table with `artist_type` covers both. Billing
   lives on `event_artist`, not per performance.
   *Assumption:* the lineup and billing are constant across an event's
   performances. The spec defines billing at the event level and never asks for
   per-performance openers.

8. **The section-to-tier assignment is keyed by (performance, section).** This
   is the direct answer to the spec's hint about where the assignment belongs.
   Keying by performance is what lets one physical section be P1 one night and
   P2 the next. Anchoring on the section or the venue would make that impossible.

9. **A composite FK ties an assignment to a tier of the same performance.**
   `(performance_id, tier_id)` in the assignment references
   `price_tier(performance_id, tier_id)`, so you can't assign a section to some
   other show's tier. The database enforces it, not app code, which is why
   `price_tier` carries the extra `UNIQUE(performance_id, tier_id)`.

10. **`performance_section_tier` is deliberately 3NF, not BCNF.** The dependency
    `tier_id -> performance_id` holds here with a non-superkey left side (one
    tier covers many sections, so `tier_id` repeats), which breaks BCNF. It's
    still 3NF because `performance_id` is prime. We keep 3NF on purpose: the
    BCNF decomposition (dropping `performance_id`) isn't dependency-preserving,
    so it loses the "exactly one tier per section per performance" key and
    breaks the composite-FK enforcement above. A clean case of BCNF costing a
    dependency that 3NF keeps.

11. **Tier `price` can change; face value is frozen on the ticket.** "You can't
    change a tier's price once a ticket is sold in it" is enforced in the app.
    It's safe because each ticket stores its own `face_value` at sale time, so
    old sales never shift when a price changes.

12. **We derive inventory instead of materializing it.** No pre-built
    AVAILABLE/SOLD/BLOCKED row per (performance, seat). A seat is available when
    it has no `seat_hold` row for that performance; only unavailable reserved
    seats show up there. Materializing would mean performances times seats rows
    (tens of thousands), nearly all "available," and it would duplicate state
    the ticket and seat_hold tables already imply (a seat could read SOLD with
    no ticket behind it). The tradeoff is that "available seats" is computed, but
    Q6 and booking would compute it anyway. Worth spelling out in the report.

13. **No double-sell is a hard database constraint.** The primary key
    `(performance_id, seat_id)` on `seat_hold` makes a second concurrent insert
    for the same seat fail at the database, with no time-of-check/time-of-use
    race like an app-side "is it free?" test has. Cancelling or unblocking
    deletes the `seat_hold` row (freeing the seat) while the `ticket` row stays
    with status CANCELLED, so history survives and a plain PK is enough.
    Worth spelling out in the report.

14. **GA overselling is handled in the booking transaction, the one exception.**
    GA tickets have no seat, so they aren't in `seat_hold`. The booking
    transaction counts active GA tickets for the section and performance against
    `ga_capacity`. A running-count limit can't be a key constraint, so this is
    the single availability rule that lives in app code. It stores no redundant
    data, so it doesn't affect any table's normal form.

15. **Sold-versus-blocked exclusivity is now a hard constraint too.** Since SOLD
    and BLOCKED share the one `seat_hold` primary key, a seat can only be in one
    state. Inserting SOLD fails if a BLOCKED row exists ("can't sell a blocked
    seat") and vice versa ("can't block a sold seat"). Both rules used to be
    app-enforced and are now database-enforced.

15b. **`ticket` stays in BCNF, which is the normalization fix.** The ticket
    carries no `performance_id` (reachable via `order -> performance`; storing it
    would be the transitive dependency `order_id -> performance_id`, below 3NF)
    and no `section_id` for reserved tickets (reachable via `seat -> section`).
    GA tickets carry `ga_section_id` because they have no seat to derive from,
    and `face_value` is a legitimate sale-time snapshot. So `ticket` is the
    lasting record that survives cancellation, and `seat_hold` is the transient
    live-inventory table, cleanly separating history from current availability.
    Worth spelling out in the report.

16. **One `users` table with a `role`, customer or organizer.** The attributes
    are about 90% shared, so subtype tables would add joins everywhere for little
    gain. Both `event.organizer_id` and `order.customer_id` point here.
    *Assumption:* a user is a customer or an organizer, not both (the spec says
    "either"). If we ever want both, swap `role` for two boolean flags.

17. **Cards belong to the customer, but the order stores a payment snapshot.**
    Putting `card_id` on the order as an FK would break 3NF, since
    `card_id -> customer_id` would hold in the order with `card_id` not a
    superkey and `customer_id` not prime (transitive
    `order_id -> card_id -> customer_id`). Instead the order snapshots the card
    details used, like `face_value` does, which keeps the order in BCNF, matches
    "recording the payment information with the order is sufficient," and stays
    correct if a saved card is later edited or deleted. `credit_card` still
    exists so signup can collect a card. Worth spelling out in the report.

18. **Age 18+ is checked in the app, not by a CHECK.** MySQL check constraints
    can't call `CURRENT_DATE`/`NOW()`, so the legal-age rule runs at account
    creation (a BEFORE INSERT trigger is the database-side alternative).

19. **Users with history are soft-deleted.** This settles the tension between
    "fully support delete user" and "keep complete order and ownership history."
    A user with activity is flagged `is_deleted` so their history stays intact;
    a user with none can be removed outright.

20. **One order is one performance and one or more tickets.** `performance_id`
    sits on the order legitimately (it depends directly on `order_id`, so the
    order is BCNF). A ticket reaches its performance through its order, so the
    ticket needs no `performance_id` of its own, and no composite-FK trick.

21. **Cancellation is recorded as columns, not a separate table.** The
    ticket-to-cancellation link is strictly one-to-one and optional (a ticket
    cancels at most once) with no repeating data, so a separate table wouldn't
    raise the normal form (the ticket is already BCNF with the columns). A table
    would only add joins to the many queries that read cancellation state (R6,
    R7, availability), a two-place consistency surface that can drift, and a
    cross-table check. It would only be worth it if cancellation grew into a rich
    audit record (reasons, approver, refund transaction), which it doesn't here.

22. **`cancel_type` separates customer cancellations from performance ones.**
    R6 needs both: count CUSTOMER cancellations per customer, and CANCELLED
    performances per organizer. We store it rather than derive it, because a
    customer-cancelled ticket on a later-cancelled performance would otherwise be
    ambiguous.

23. **Refunds are implied, not modelled.** The spec expects no real payment
    transaction, so a cancellation means a full refund of the ticket's
    `face_value`, and a CANCELLED ticket is the refund record. No `refund` table.
    Whether R1's "gross revenue" counts refunded tickets is a reporting choice,
    and `status`/`cancel_type` let us compute it either way. *Assumption.*

24. **The 7-day window and authorization live in the cancel operation.**
    `performance_datetime - now >= 7 days` can't be a check (it needs the current
    date), so the app enforces it. "Only the order's customer" and "only the
    event's organizer" are checked against `order.customer_id` and
    `event.organizer_id`. A customer cancel frees the seat (deletes the
    `seat_hold` row); an organizer cancel bulk-cancels every active ticket and
    frees their seats.

25. **Ownership is an append-only log, and the current owner is derived.**
    `ticket_ownership` gets one row at purchase and one per resale; the current
    owner is the row with the latest `acquired_at`. We don't store a
    `current_owner_id` on the ticket: it would be BCNF, but it's a cross-table
    copy that can drift from the log. A `current_ownership` view can wrap the
    max-subquery if it gets repetitive. This keeps the full history the spec asks
    for.

26. **One active listing per ticket, and the cap is checked in the app.** The
    generated-column unique index blocks double-listing. The rule
    `list_price <= face_value * event.resale_cap_pct / 100` spans tables (face
    value on the ticket, the cap on the event), so a check can't express it and
    the app enforces it. "Priced exactly at the cap" (R8) is just a computed
    comparison, so nothing extra is stored and the listing stays BCNF.

27. **Buying a resale is not an order.** A sale flips the listing to SOLD with a
    buyer and close time and appends a RESALE ownership row; no `order` row is
    created. Orders are the primary market only; resale is peer-to-peer with no
    modelled payment, and `seat_hold` is untouched since only the owner changes.
    This keeps R5 ("number of orders") about the primary market. *Assumption.*

28. **The right to cancel follows the current owner.** The spec says "only the
    customer that placed an order" may cancel, but after a resale that customer
    no longer holds the ticket. We let the current owner cancel, since they hold
    it and receive the refund, reading the spec's wording as the common un-resold
    case. *Assumption.*

29. **A review is keyed by (customer, performance), and event/venue are derived.**
    "At most once per performance" is `UNIQUE(customer_id, performance_id)`. One
    review carries both the event and the venue rating, since a performance has
    one of each. We store `performance_id` and reach the event and venue by join,
    so nothing is duplicated. R9 joins `review -> performance -> event`.

30. **Review eligibility is enforced in the app.** The reviewer must be the
    current owner of a non-cancelled ticket for the performance (which correctly
    handles resale: the attendee is whoever held it through the show), and the
    performance must already have happened. That spans tables and needs the
    current date, so it lives in the review operation. The 1-to-5 ratings are
    real check constraints.

31. **"Recently attended" means within the past year.** We use a rolling
    365-day window, matching the "past year" windows in R4, R5, and R6 so
    "recent" means one consistent thing everywhere. Easy to tighten to 30 or 90
    days if we want. *Assumption.*


## Assumptions, collected

For the report's assumptions section:

- Billing order is defined at the event level and is constant across its
  performances (decision 7).
- A user is a customer or an organizer, not both (decision 16).
- Refunds aren't modelled; a cancellation implies a full face-value refund
  (decision 23).
- Buying a resale listing is peer-to-peer, not an order (decision 27).
- The right to cancel follows the current owner of a ticket (decision 28).
- "Recently attended," for reviews, means within the past year (decision 31).


## Open items

Small things to settle when we build the queries and reports. None of them
change the schema.

- **Q1 distance:** haversine over `venue.latitude/longitude`, with a default
  radius (say 50 km) the user can override.
- **Q2 postal adjacency:** decide what "adjacent postal codes" means (for
  example sharing the first three characters, the FSA, for Canada). We store the
  raw `postal_code`; adjacency is a query-side rule.
- **Q7 consecutive seats:** `seat_number` is an INT so "consecutive" is well
  defined within a row.
- **R1 gross revenue:** decide whether refunded (cancelled) tickets count;
  `status` and `cancel_type` support either reading (decision 23).
- **Data:** seed `segment` and `genre` with the real Ticketmaster taxonomy labels.


## Coverage check (closes task 1B)

Every query (Q1-Q7), every report (R1-R9), and every operation is served by the
20 tables, with no missing tables or columns. A few interpretations to pin
before we code the reports:

- **R1 basis:** count and revenue by sale date (`order.order_datetime`), primary
  market only (face value to the organizer); resales add no organizer revenue.
  Refunded tickets per decision 23.
- **R2 "events per country/city":** an event counts in a place if it has at
  least one performance there, so a tour can count in several places.
  Performances are counted directly by their venue.
- **R7 sellable capacity:** reserved seats in the performance's assigned sections
  that aren't blocked, plus the GA capacity of assigned GA sections. Sold is SOLD
  `seat_hold` rows plus active GA tickets, and sell-through is sold over sellable.
- **R4/R5/R6 "past year":** a rolling 365-day window from now, matching
  decision 31.
