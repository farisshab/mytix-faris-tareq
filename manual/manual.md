# MyTix - User Manual

CSCC43 Database Design Project

**Group members**
- Tareq Abousherif (1007977824)
- Faris Shabaytah (1011300986)


## 1. Overview

This manual explains how to build, run, and use MyTix from the command line, walks
through every operation, query, and report reachable from the interface, and closes
with the system's known limitations and possible improvements. For the conceptual
design, the ER diagram, the relational schema, and the normal-form justification,
see `report.pdf`.

MyTix is a single text-based interface shared by customers and organizers. Which
menus are available depends on the role of whoever is signed in: a customer sees
Customer Operations and not Organizer Operations, and vice versa. Account
management and search queries are available to either role. Reports and the
organizer toolkit are organizer-only, since they are operator tools (revenue
rankings, sell-through, pricing suggestions) that a customer has no need for; a
customer who selects them is told the option is unavailable.


## 2. Getting started

**Prerequisites:** JDK 17 or newer, and MySQL 8 running locally.

**One-time setup**, from the repository root:

```bash
cp config.properties.example config.properties
mysql -u root -p < sql/schema.sql
mysql -u root -p < sql/load.sql
```

`config.properties` only needs editing if your local MySQL login differs from the
default (`root`, no password); it is gitignored, so credentials stay local.
`schema.sql` and `load.sql` each select the `mytix` database themselves.

**Running the app:**

```bash
chmod +x run.sh    # first time only
./run.sh
```

`run.sh` compiles everything under `src/` and launches the interface. All commands
above assume the repository root as the working directory, since the app locates
`config.properties` by relative path, the same
way `sql/schema.sql` and `sql/load.sql` are referenced from the repository root
throughout this manual.


## 3. Signing in

The first screen is:

```
=== Welcome to MyTix ===
1) Sign in
2) Create an account
```

**Sign in** asks only for an email address; there is no password. If the email
matches an active (non-deleted) account, you are signed in as that user, with
whichever role (customer or organizer) the account was created with.

**Sample logins** (from the loaded data, for trying the app out):
`liam.roy1@example.com` is an organizer and `harper.khan7@example.com` is a
customer. Any email in `data/users.csv` works; these are just two convenient
ones.

**Create an account** asks for a role (customer or organizer), full name, address,
email, and date of birth (must be 18 or older). A customer account also collects a
credit card (card number, cardholder name, expiry month and year); this is fully
fabricated data, never validated against a real payment network, per the project
specification. If the email belongs to a previously deleted account, you are
offered the option to reactivate it with its original information rather than
create a duplicate.

Once signed in, you land on the Home Menu, which stays available until you choose
Exit or delete your own account (which signs you out and returns you to this
welcome screen).


## 4. Home Menu

```
===== MyTix =====
1) Account Management
2) Customer Operations   (customers only)
3) Organizer Operations  (organizers only)
4) Queries
5) Reports               (organizers only)
6) Organizer Toolkit     (organizers only)
0) Exit
```

Sections 5-10 below cover each of these in turn.


## 5. Account Management

Available to both roles.

- **View Account Information** - prints your name, address, email, date of birth,
  and role, read fresh from the database each time (not cached from sign-in).
- **View Card Information** - customers only; prints the card(s) on file. Prints a
  message instead if signed in as an organizer.
- **Update Email** - checks the new email isn't already registered to someone else
  before applying the change.
- **Update Address** - a single free-text prompt.
- **Update Credit Card** - customers only; replaces the card on file (or adds one,
  if none exists), with the same validation as account creation (valid month 1-12,
  not already expired).
- **Delete Account** - asks for Y/N confirmation. If you have any history anywhere
  in the system (orders, tickets, listings, reviews, an organized event), the
  account is deactivated rather than removed, so that history stays intact; a
  brand-new account with no history is removed outright. Deleting your account
  signs you out immediately.


## 6. Customer Operations

Visible only when signed in as a customer.

- **Book Tickets** - lists upcoming performances, then that performance's sections
  with their tier and price. Choosing a general-admission section asks for a
  quantity; choosing a reserved section shows the currently available seats grouped
  by row and asks for a row and seat number per ticket. Requires a card on file.
- **Cancel Tickets** - lists your own active tickets for performances at least
  seven days away, grouped by performance with seat/section detail so multiple
  seats for the same show are distinguishable. Cancelling refunds the ticket's full
  face value (implied, not a separate transaction) and frees the seat or GA
  capacity for resale to someone else. If the cancelled ticket had an active resale
  listing, that listing is withdrawn automatically.
- **List a Ticket for Resale** - lists your own currently-owned, unlisted, active
  tickets, shows each one's resale cap, and asks for an asking price (must not
  exceed the cap).
- **Withdraw a Listing** - lists your own active listings and asks which to pull.
- **Buy a Listing** - lists upcoming performances, then that performance's active
  listings from other customers (never your own). Requires a card on file.
  Ownership of the ticket transfers to you immediately; this does not create an
  order, since resale is a peer-to-peer transfer, not a primary-market purchase.
- **Submit a Review** - lists performances you attended (held a non-cancelled
  ticket for) within the past year that you haven't already reviewed, then asks
  for an event rating and a venue rating (1-5 each) and an optional comment.


## 7. Organizer Operations

Visible only when signed in as an organizer.

- **Create Event** - title, genre, and resale cap percentage (must be at least
  100).
- **Add Performance to Event** - one of your own events, a venue, and a date/time.
- **Define Price Tiers** - one of your own performances, then one or more tier
  codes and prices.
- **Assign Sections to Tiers** - maps each of the venue's sections to one of that
  performance's defined tiers.
- **Set Resale Cap** - updates an event's resale cap percentage.
- **Update Tier Price** - refused if any ticket has already sold in that tier for
  that performance, since past sales must never silently change value.
- **Block Seat** - marks an available reserved seat unsellable for one performance
  (obstructed view, equipment, etc.); refused if the seat is already sold.
- **Unblock Seat** - reverses a block.
- **Cancel a Performance** - asks for Y/N confirmation, then cancels the whole
  performance: every active ticket for it is refunded and cancelled, every held
  seat is freed, and any active resale listings on those tickets are withdrawn, all
  in one all-or-nothing transaction.


## 8. Queries

Available to either role. Every result list shows the underlying `_id` values
needed to act on a result elsewhere in the app (e.g. a performance ID surfaced here
can be typed directly into Book Tickets).

- **Q1 - Performances near a location** - latitude, longitude, an optional search
  radius in km (default 50), and a choice of ranking: nearest first, cheapest
  available ticket first, or most expensive first. A performance with no priced
  section yet always sorts last under either price ranking, never first.
- **Q2 - Search by postal code** - returns performances at venues in the same or
  an adjacent postal code, where "adjacent" means sharing the first two characters
  of the postal code (the general metro-area prefix).
- **Q3 - Search by exact address** - an exact, case-sensitive address match; finds
  the venue and lists its upcoming performances.
- **Q4 - By date range & availability** - a date range and a minimum number of
  available tickets.
- **Q5 - Advanced filters** - combinable filters (city, segment/genre, date range,
  price range, minimum availability, reserved vs. general admission).
- **Q6 - Seat map for a performance** - per section: tier, price, and
  available/sold/blocked counts.
- **Q7 - Best available seats** - a performance, a desired ticket count, and an
  optional budget; finds that many consecutive seats in one row at the lowest
  total price.


## 9. Reports

Available to organizers only. All nine are pure SQL except R9, per the project
specification's one stated exception.

- **R1 - Tickets sold & revenue by city** - a date range, then a choice of
  grouping by city or by venue within one chosen city. Counts active tickets only;
  a cancelled ticket was refunded, so its money isn't counted as revenue.
- **R2 - Event & performance counts** - a choice of four groupings: segment &
  genre, country, country & city, or country, city & venue.
- **R3 - Organizer revenue ranking** - a choice of overall, per country, or one
  city. Same active-tickets-only revenue rule as R1.
- **R4 - Possible scalpers by city** - flags customers who, in the past year,
  bought at least ten tickets and listed more than half of them for resale.
- **R5 - Customer order ranking** - a choice of an overall ranking in a
  user-supplied date range, or a per-city ranking over the past year restricted to
  customers with at least two orders that year.
- **R6 - Most cancellations** - a choice of customers with the most cancelled
  tickets, or organizers with the most cancelled performances, both within the
  past year.
- **R7 - Sell-through** - a choice of per performance, per tier of one
  performance, or sold-out/under-a-quarter performances by city for one month.
- **R8 - Resale report** - a choice of per-event resale statistics (all time), or
  the top ten events by resale volume in a chosen date range.
- **R9 - Event noun phrases** - for each event with reviews, the most popular noun
  phrases from its review comments. Rather than parsing grammar with a part-of-speech
  (POS) tagger, it removes common filler words and counts the frequent word runs that
  remain, which in review writing are mostly nouns and their modifiers. The output
  could seed a word cloud per event.


## 10. Organizer Toolkit

Available to organizers only.

- **Suggest pricing & tier structure for a new performance** - asks for an event
  ID (to read its genre) and a venue ID (for its capacity), then suggests a number
  of price tiers, a price for each, and a rough capacity split, based on comparable
  past performances (same genre, similar venue capacity, same city, recent). If too
  few close comparables exist, the search widens automatically (city, then
  country, then anywhere; capacity band, then recency), and the suggestion states
  which comparables it actually used so the result is never a black box.


## 11. System limitations

- R9 finds its noun phrases by removing common filler words and counting what's
  left, rather than actually parsing the grammar with a POS tagger, so now and then
  an adjective or verb slips in among the real noun phrases.
- Sign-in is email-only, with no password or other credential; anyone who knows or
  guesses an email address in the system can sign in as that user.
- Payment information is entirely fabricated and never validated against a real
  card network, per the project specification; there is no real payment gateway.
- Long result lists (e.g. Q1 with a wide radius, or a customer with many
  cancellable tickets) print in full with no pagination, which could become
  unwieldy on a much larger dataset than the sample data provided.
- The interface is a single-user text session; there is no concurrent multi-user
  view of the same running app (though the underlying database itself is
  concurrency-safe, per the seat-hold and listing uniqueness constraints described
  in `report.pdf`).
- General-admission capacity concurrency safety relies on locking the section's
  row for the duration of a booking transaction; a very high-volume simultaneous
  GA sale on the exact same section would break through that lock rather than
  process in parallel.


## 12. Possible improvements

- Upgrade R9 to a real part-of-speech tagger for cleaner noun-phrase extraction.
- Add real authentication (a password, at minimum) rather than email-only sign-in.
- Add pagination or a result-count cap to long-running query/report output.
- Extend the organizer toolkit's extra-credit revenue-change estimate (a
  constant-elasticity demand model, outlined in `docs/report_design.md`) from
  design into a working feature.
- A graphical or web front end in place of the text interface, for a more typical
  end-user experience.