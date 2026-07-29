#!/usr/bin/env python3
"""
MyTix sample-data generator.

Writes data/*.csv and sql/load.sql for the MyTix schema. Running once then committing
output; the graders will run the committed load.sql, never this script.

Two ideas drive the whole thing:

  1. Deterministic: a fixed seed means every run produces identical rows, so any
     weird report result is reproducible and planted scenarios stay put.
     Practical for debugging, testing, and consistency.

  2. Plant what's guaranteed and randomize the rest. The handout says we'd
     lose marks for any untestable features, so each required scenario
     (sold-out shows, scalpers, a row of consecutive open seats, etc.) is
     placed on purpose, then the rest can be filled in randomly.

Dates are stored relative to load time (see Rel) and emitted as NOW() + INTERVAL,
so "upcoming", "past", "within 7 days" and "within a year" stay correct no matter
when the graders load the data.

Usage:
    python3 tools/generate_data.py
"""

import csv
import os
import random
from datetime import datetime, timedelta

# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------

SEED = 43
random.seed(SEED)

# A fixed anchor used only when writing absolute dates into the CSVs. load.sql
# uses NOW()-relative expressions instead (so it doesn't depend on this).
GEN_NOW = datetime(2026, 8, 1, 12, 0, 0)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA_DIR = os.path.join(ROOT, "data")
LOAD_SQL = os.path.join(ROOT, "sql", "load.sql")

# Size knobs (handout minimums with a little margin). Tune as needed.
NUM_CUSTOMERS = 110
NUM_ORGANIZERS = 6
NUM_ORDERS = 320
MIN_TICKETS = 850


# ---------------------------------------------------------------------------
# Table definitions (column order matches sql/schema.sql exactly)
# ---------------------------------------------------------------------------

COLUMNS = {
    "users": ["user_id", "full_name", "address", "email", "date_of_birth", "role", "is_deleted"],
    "credit_card": ["card_id", "customer_id", "card_number", "cardholder_name", "expiry_month", "expiry_year"],
    "venue": ["venue_id", "name", "latitude", "longitude", "address", "postal_code", "city", "country"],
    "section": ["section_id", "venue_id", "section_name", "section_type", "ga_capacity"],
    "seat_row": ["row_id", "section_id", "row_name"],
    "seat": ["seat_id", "row_id", "seat_number"],
    "segment": ["segment_id", "segment_name"],
    "genre": ["genre_id", "segment_id", "genre_name"],
    "artist": ["artist_id", "artist_name", "artist_type"],
    "event": ["event_id", "organizer_id", "title", "genre_id", "resale_cap_pct"],
    "event_artist": ["event_id", "artist_id", "billing_order"],
    "performance": ["performance_id", "event_id", "venue_id", "performance_datetime", "status", "cancelled_at"],
    "price_tier": ["tier_id", "performance_id", "tier_code", "price"],
    "performance_section_tier": ["performance_id", "section_id", "tier_id"],
    "orders": ["order_id", "customer_id", "performance_id", "order_datetime", "pay_card_number", "pay_cardholder", "pay_expiry"],
    "ticket": ["ticket_id", "order_id", "seat_id", "ga_section_id", "face_value", "status", "cancelled_at", "cancel_type"],
    "seat_hold": ["performance_id", "seat_id", "hold_type", "ticket_id"],
    "ticket_ownership": ["ownership_id", "ticket_id", "owner_id", "acquired_at", "acquired_via"],
    "listing": ["listing_id", "ticket_id", "seller_id", "list_price", "status", "created_at", "closed_at", "buyer_id"],
    "review": ["review_id", "customer_id", "performance_id", "event_rating", "venue_rating", "comment_text", "created_at"],
}

# FK-safe load order (also the emit order).
TABLE_ORDER = list(COLUMNS.keys())

# Tables with a surrogate auto-increment id in their first column.
LINK_TABLES = {"event_artist", "performance_section_tier", "seat_hold"}
SURROGATE_TABLES = set(COLUMNS) - LINK_TABLES

DATA = {t: [] for t in COLUMNS}
_next_id = {t: 0 for t in COLUMNS}


def add(table, **vals):
    """Append one row and return its id. Surrogate ids are assigned here, so we
    only pass the real fields. Reference the returned id as an FK elsewhere."""
    cols = COLUMNS[table]
    if table in SURROGATE_TABLES:
        pk = cols[0]
        if vals.get(pk) is None:
            _next_id[table] += 1
            vals[pk] = _next_id[table]
    missing = set(vals) - set(cols)
    if missing:
        raise KeyError(f"{table}: unknown column(s) {missing}")
    DATA[table].append([vals.get(c) for c in cols])
    return vals.get(cols[0])


class Rel:
    """A datetime relative to load time. Rel(-30) is 30 days ago, Rel(10) is ten
    days out. Emitted in load.sql as NOW() + INTERVAL, so past vs. upcoming holds
    whenever the data is loaded."""

    def __init__(self, days=0, hours=0):
        self.days = days
        self.hours = hours


# ---------------------------------------------------------------------------
# Curated constants (realism + the clustering / taxonomy requirements)
# ---------------------------------------------------------------------------

# 8 venues, 4 cities, 2 countries. The first three are clustered in downtown
# Toronto (adjacent FSAs M5V / M5J / M5E) to satisfy the "close venues" rule.
# "kind" hints how build_venues should lay out sections; at least two venues
# must have both reserved and GA sections guaranteed.
VENUES = [
    {"name": "Scotiabank Arena", "lat": 43.6435, "lng": -79.3791, "address": "40 Bay St", "postal": "M5J 2X2", "city": "Toronto", "country": "Canada", "kind": "arena"},
    {"name": "Rogers Centre", "lat": 43.6414, "lng": -79.3894, "address": "1 Blue Jays Way", "postal": "M5V 1J1", "city": "Toronto", "country": "Canada", "kind": "stadium"},
    {"name": "Meridian Hall", "lat": 43.6469, "lng": -79.3762, "address": "1 Front St E", "postal": "M5E 1B2", "city": "Toronto", "country": "Canada", "kind": "theatre"},
    {"name": "Massey Hall", "lat": 43.6544, "lng": -79.3789, "address": "178 Victoria St", "postal": "M5B 1T7", "city": "Toronto", "country": "Canada", "kind": "theatre"},
    {"name": "Bell Centre", "lat": 45.4961, "lng": -73.5693, "address": "1909 Av des Canadiens", "postal": "H4B 5G0", "city": "Montreal", "country": "Canada", "kind": "arena"},
    {"name": "Madison Square Garden", "lat": 40.7505, "lng": -73.9934, "address": "4 Pennsylvania Plaza", "postal": "10001", "city": "New York", "country": "USA", "kind": "arena"},
    {"name": "Radio City Music Hall", "lat": 40.7599, "lng": -73.9799, "address": "1260 6th Ave", "postal": "10020", "city": "New York", "country": "USA", "kind": "club"},
    {"name": "TD Garden", "lat": 42.3662, "lng": -71.0621, "address": "100 Legends Way", "postal": "02114", "city": "Boston", "country": "USA", "kind": "arena"},
]

# Ticketmaster-style taxonomy: 3 segments, 8 genres.
TAXONOMY = {
    "Music": ["Rock", "Pop", "Hip-Hop", "Country"],
    "Arts & Theatre": ["Musical", "Play"],
    "Sports": ["Basketball", "Hockey"],
}

# Real, recognizable artists so the data reads plausibly. Each carries a genre hint
# so build_events can pair an act with a genre-matching event. The hint is only
# for generation; the artist table itself just stores name and type (INDIVIDUAL
# for a solo act or band, TEAM for a sports team).
ARTISTS = [
    # Music
    ("Tame Impala", "INDIVIDUAL", "Rock"),
    ("Foo Fighters", "INDIVIDUAL", "Rock"),
    ("Arctic Monkeys", "INDIVIDUAL", "Rock"),
    ("Justin Bieber", "INDIVIDUAL", "Pop"),
    ("The Weeknd", "INDIVIDUAL", "Pop"),
    ("Dua Lipa", "INDIVIDUAL", "Pop"),
    ("50 Cent", "INDIVIDUAL", "Hip-Hop"),
    ("Drake", "INDIVIDUAL", "Hip-Hop"),
    ("Kendrick Lamar", "INDIVIDUAL", "Hip-Hop"),
    ("Morgan Wallen", "INDIVIDUAL", "Country"),
    ("Luke Combs", "INDIVIDUAL", "Country"),
    ("Zach Bryan", "INDIVIDUAL", "Country"),
    # Arts & Theatre
    ("Hamilton", "INDIVIDUAL", "Musical"),
    ("The Lion King", "INDIVIDUAL", "Musical"),
    ("A Christmas Carol", "INDIVIDUAL", "Play"),
    # Sports
    ("Toronto Raptors", "TEAM", "Basketball"),
    ("Boston Celtics", "TEAM", "Basketball"),
    ("New York Knicks", "TEAM", "Basketball"),
    ("Montreal Canadiens", "TEAM", "Hockey"),
    ("Boston Bruins", "TEAM", "Hockey"),
    ("New York Rangers", "TEAM", "Hockey"),
]

# Short sentences to stitch into multi-sentence review comments. Keeping them noun-
# rich so R9's noun-phrase extraction has enough to work with.
REVIEW_SENTENCES = [
    "The sound quality was incredible and the lighting design stole the show.",
    "Our seats had a clear view of the stage and the whole crowd was electric.",
    "The opening act warmed up the room before an unforgettable encore.",
    "Parking near the venue was a nightmare but the concert made up for it.",
    "The stage production and video screens were worth the ticket price alone.",
    "Great atmosphere, friendly staff, and surprisingly short beer lines.",
    "The acoustics in the balcony were muddy but the energy was still amazing.",
    "A tight setlist, a killer light show, and a crowd that never sat down.",
]

BILLING = ["HEADLINER", "SPECIAL_GUEST", "OPENING_ACT"]

# Section layouts per venue kind: (name, type, rows, seats_per_row). GA sections
# ignore rows and use the last value as capacity. Arenas and clubs include a GA
# section, so several venues end up with both reserved and general admission.
SECTION_LAYOUTS = {
    "arena": [
        ("Floor", "RESERVED", 10, 20),
        ("Lower Bowl", "RESERVED", 18, 22),
        ("Upper Bowl", "RESERVED", 24, 24),
        ("General Admission", "GA", None, 500),
    ],
    "stadium": [
        ("Field Level", "RESERVED", 14, 28),
        ("100 Level", "RESERVED", 26, 30),
        ("500 Level", "RESERVED", 34, 32),
    ],
    "theatre": [
        ("Orchestra", "RESERVED", 14, 24),
        ("Mezzanine", "RESERVED", 10, 22),
        ("Balcony", "RESERVED", 8, 20),
    ],
    "club": [
        ("Floor", "GA", None, 400),
        ("Balcony", "RESERVED", 6, 18),
    ],
}

CONCERT_SUFFIXES = ["World Tour", "Live", "In Concert", "The Arena Tour", "Homecoming Show"]

# Small pools for the made-up people (customers and organizers).
FIRST_NAMES = ["Olivia", "Liam", "Emma", "Noah", "Ava", "William", "Sophia",
               "James", "Isabella", "Lucas", "Mia", "Benjamin", "Charlotte",
               "Ethan", "Amelia", "Daniel", "Harper", "Matthew", "Evelyn",
               "Jack", "Aria", "Leo", "Zoe", "Omar", "Priya", "Diego"]
LAST_NAMES = ["Smith", "Johnson", "Tremblay", "Nguyen", "Patel", "Brown",
              "Martin", "Lee", "Garcia", "Roy", "Wilson", "Chen", "Singh",
              "Cote", "Taylor", "Khan", "Wong", "Bouchard", "Reyes", "Kim",
              "Silva", "Ali", "Murphy", "Ivanov", "Rossi", "Haddad"]
STREETS = ["Main St", "King St", "Queen St", "Bay St", "Sherbrooke St", "Peel St",
           "Broadway", "5th Ave", "Beacon St", "Boylston St", "College St", "Dundas St"]


# ---------------------------------------------------------------------------
# Shared lookups, filled in as the build steps run
# ---------------------------------------------------------------------------

GENRE_ID = {}          # genre_name -> genre_id
ARTIST_NAME = {}       # artist_id -> name
ARTISTS_BY_GENRE = {}  # genre_name -> [artist_id, ...]
ORGANIZERS = []        # organizer user_ids
CUSTOMERS = []         # customer user_ids
VENUES_BUILT = []      # per-venue layout: sections, rows, seat ids
EVENTS_BUILT = []      # per-event info: id, genre, kind, artist ids


def _row_names(n):
    """A, B, ... Z, AA, AB, ... for n rows."""
    names = []
    i = 0
    while len(names) < n:
        if i < 26:
            names.append(chr(ord("A") + i))
        else:
            names.append(chr(ord("A") + i // 26 - 1) + chr(ord("A") + i % 26))
        i += 1
    return names


def _event_kind(genre):
    if genre in ("Basketball", "Hockey"):
        return "sports"
    if genre in ("Musical", "Play"):
        return "theatre"
    return "concert"


# ---------------------------------------------------------------------------
# Build steps (run in FK order). Later stubs' docstrings list what they must
# guarantee.
# ---------------------------------------------------------------------------

def build_taxonomy():
    """segment + genre. Worked example of the add() pattern: create a parent,
    capture its id, use it as the child's FK."""
    for segment_name, genres in TAXONOMY.items():
        seg_id = add("segment", segment_name=segment_name)
        for genre_name in genres:
            GENRE_ID[genre_name] = add("genre", segment_id=seg_id, genre_name=genre_name)


def build_venues():
    """venue + section + seat_row + seat, laid out per venue kind."""
    for v in VENUES:
        vid = add("venue", name=v["name"], latitude=v["lat"], longitude=v["lng"],
                  address=v["address"], postal_code=v["postal"], city=v["city"],
                  country=v["country"])
        vinfo = {"venue_id": vid, "city": v["city"], "country": v["country"],
                 "kind": v["kind"], "sections": []}
        for sec_name, sec_type, n_rows, size in SECTION_LAYOUTS[v["kind"]]:
            if sec_type == "GA":
                sid = add("section", venue_id=vid, section_name=sec_name,
                          section_type="GA", ga_capacity=size)
                vinfo["sections"].append(
                    {"section_id": sid, "type": "GA", "ga_capacity": size, "rows": []})
            else:
                sid = add("section", venue_id=vid, section_name=sec_name,
                          section_type="RESERVED", ga_capacity=None)
                rows = []
                for rname in _row_names(n_rows):
                    rid = add("seat_row", section_id=sid, row_name=rname)
                    seats = [add("seat", row_id=rid, seat_number=n)
                             for n in range(1, size + 1)]
                    rows.append({"row_id": rid, "row_name": rname, "seats": seats})
                vinfo["sections"].append(
                    {"section_id": sid, "type": "RESERVED", "ga_capacity": None, "rows": rows})
        VENUES_BUILT.append(vinfo)


def build_users():
    """users (customers + organizers) plus a fake card per customer. Emails are unique
    and everyone is 18+ (see the DOB range)."""
    used_emails = set()

    def make_user(role):
        first, last = random.choice(FIRST_NAMES), random.choice(LAST_NAMES)
        seq = len(used_emails) + 1
        email = f"{first}.{last}{seq}@example.com".lower()
        used_emails.add(email)
        # 19..70 at the gen anchor, so still 18+ whenever the data is loaded
        dob = (GEN_NOW - timedelta(days=random.randint(19 * 365, 70 * 365))).strftime("%Y-%m-%d")
        uid = add("users", full_name=f"{first} {last}",
                  address=f"{random.randint(1, 999)} {random.choice(STREETS)}",
                  email=email, date_of_birth=dob, role=role, is_deleted=False)
        return uid, f"{first} {last}"

    for _ in range(NUM_ORGANIZERS):
        uid, _name = make_user("ORGANIZER")
        ORGANIZERS.append(uid)

    for _ in range(NUM_CUSTOMERS):
        uid, name = make_user("CUSTOMER")
        CUSTOMERS.append(uid)
        add("credit_card", customer_id=uid,
            card_number="".join(str(random.randint(0, 9)) for _ in range(16)),
            cardholder_name=name,
            expiry_month=random.randint(1, 12),
            expiry_year=random.randint(2027, 2032))


def build_artists():
    """artist (people and teams). Also records name and genre lookups for events."""
    for name, atype, genre in ARTISTS:
        aid = add("artist", artist_name=name, artist_type=atype)
        ARTIST_NAME[aid] = name
        ARTISTS_BY_GENRE.setdefault(genre, []).append(aid)


def build_events():
    """event + event_artist. Three events per genre (24 total) spread across
    organizers; sports events feature two teams, some concerts add an opener."""
    org_i = 0
    for genre in list(GENRE_ID):
        for _ in range(3):
            organizer = ORGANIZERS[org_i % len(ORGANIZERS)]
            org_i += 1
            cap = 120.00 if random.random() < 0.8 else random.choice([110.00, 125.00, 150.00])
            acts = ARTISTS_BY_GENRE.get(genre, [])
            kind = _event_kind(genre)
            if kind == "sports" and len(acts) >= 2:
                home, away = random.sample(acts, 2)
                eid = add("event", organizer_id=organizer,
                          title=f"{ARTIST_NAME[home]} vs {ARTIST_NAME[away]}",
                          genre_id=GENRE_ID[genre], resale_cap_pct=cap)
                add("event_artist", event_id=eid, artist_id=home, billing_order="HEADLINER")
                add("event_artist", event_id=eid, artist_id=away, billing_order="SPECIAL_GUEST")
                featured = [home, away]
            else:
                headliner = random.choice(acts)
                title = ARTIST_NAME[headliner] if kind == "theatre" \
                    else f"{ARTIST_NAME[headliner]}: {random.choice(CONCERT_SUFFIXES)}"
                eid = add("event", organizer_id=organizer, title=title,
                          genre_id=GENRE_ID[genre], resale_cap_pct=cap)
                add("event_artist", event_id=eid, artist_id=headliner, billing_order="HEADLINER")
                featured = [headliner]
                others = [a for a in acts if a != headliner]
                if kind == "concert" and others and random.random() < 0.5:
                    opener = random.choice(others)
                    add("event_artist", event_id=eid, artist_id=opener, billing_order="OPENING_ACT")
                    featured.append(opener)
            EVENTS_BUILT.append({"event_id": eid, "genre": genre, "kind": kind,
                                 "artist_ids": featured})


def build_performances():
    """performance + price_tier + performance_section_tier.

    Must guarantee:
      - >= 60 performances, a mix of past and upcoming (use Rel for datetimes)
      - one touring event (same event at several venues)
      - one theatre run (one event, many performances at one venue)
      - >= 1 venue hosting two performances with different section->tier maps and
        different prices
      - every performance has >= 2 price tiers, and every section assigned a tier
    """
    # TODO
    raise NotImplementedError


def build_orders_tickets():
    """orders + ticket + seat_hold(SOLD) + ticket_ownership(PURCHASE).

    Keep an in-memory set of taken (performance, seat) so you never double-sell.
    face_value = the tier price of the seat's section for that performance.

    Must guarantee:
      - >= NUM_ORDERS orders and >= MIN_TICKETS tickets, spread past + future
      - several customers with 2+ orders in the past year, in more than one city
      - among PAST performances: some sold out, some under 25% sold
      - >= 1 upcoming performance more than 7 days out that still has: a row with
        4+ consecutive open seats, a row where only non-consecutive seats remain,
        and leftover GA capacity
      - >= 1 upcoming performance fewer than 7 days out with some sold tickets
      - at least one upcoming tier with zero sales AND one with sales (for the
        price-change demo and its refusal)
    """
    # TODO
    raise NotImplementedError


def plant_blocks():
    """seat_hold(BLOCKED). Block some available reserved seats in a few
    performances so Q6 and the blocking ops have data. Never block a sold seat."""
    # TODO
    raise NotImplementedError


def plant_cancellations():
    """Customer and organizer cancellations.

    Must guarantee:
      - several customer-cancelled tickets (status CANCELLED, cancel_type CUSTOMER),
        freeing their seat_hold rows
      - >= 2 past-year performances cancelled by their organizer (performance
        CANCELLED, its tickets cancel_type PERFORMANCE, seat_holds freed)
    """
    # TODO
    raise NotImplementedError


def build_resale():
    """listing + ticket_ownership(RESALE).

    Must guarantee:
      - listings in every status: ACTIVE, SOLD, WITHDRAWN
      - at least one listing priced exactly at the cap
      - at least one ticket that changed owners twice (a 3-row ownership chain)
      - >= 2 "scalpers": customers who, in the past year, bought >= 10 tickets and
        listed more than half of them
      - a SOLD listing appends a RESALE ownership row and moves the ticket to the
        buyer; seat_hold is untouched
    """
    # TODO
    raise NotImplementedError


def build_reviews():
    """review.

    Must guarantee:
      - reviews for >= 10 different events, several each
      - reviewer is the current owner of a non-cancelled ticket for a PAST
        performance; at most one review per (customer, performance)
      - multi-sentence comments (stitch a few REVIEW_SENTENCES together)
      - ratings 1..5
    """
    # TODO
    raise NotImplementedError


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def _csv_val(v):
    if v is None:
        return ""
    if isinstance(v, Rel):
        return (GEN_NOW + timedelta(days=v.days, hours=v.hours)).strftime("%Y-%m-%d %H:%M:%S")
    if isinstance(v, bool):
        return "1" if v else "0"
    return str(v)


def _sql_val(v):
    if v is None:
        return "NULL"
    if isinstance(v, Rel):
        expr = "NOW()"
        if v.days:
            expr += f" + INTERVAL {v.days} DAY"
        if v.hours:
            expr += f" + INTERVAL {v.hours} HOUR"
        return f"({expr})"
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, (int, float)):
        return str(v)
    s = str(v).replace("\\", "\\\\").replace("'", "''")
    return f"'{s}'"


def emit_csvs():
    os.makedirs(DATA_DIR, exist_ok=True)
    for table in TABLE_ORDER:
        path = os.path.join(DATA_DIR, f"{table}.csv")
        with open(path, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(COLUMNS[table])
            for row in DATA[table]:
                w.writerow([_csv_val(v) for v in row])


LOAD_HEADER = """-- MyTix load.sql  (generated by tools/generate_data.py; do not edit by hand)
-- Bulk-loads the sample data into the mytix schema. Run it after schema.sql.
--
-- Plain INSERTs so this runs anywhere with a bare `mysql < sql/load.sql`, no
-- flags needed. If load time ever becomes a problem, switch to the LOAD DATA
-- LOCAL INFILE variant and run the client with --local-infile=1 (server
-- local_infile=ON). Datetimes are NOW()-relative so past/upcoming stay correct.

USE mytix;
SET FOREIGN_KEY_CHECKS = 1;
"""


def emit_load_sql(batch=200):
    with open(LOAD_SQL, "w") as f:
        f.write(LOAD_HEADER)
        for table in TABLE_ORDER:
            rows = DATA[table]
            if not rows:
                continue
            cols = ", ".join(COLUMNS[table])
            f.write(f"\n-- {table} ({len(rows)} rows)\n")
            for i in range(0, len(rows), batch):
                chunk = rows[i:i + batch]
                f.write(f"INSERT INTO {table} ({cols}) VALUES\n")
                lines = ["  (" + ", ".join(_sql_val(v) for v in row) + ")" for row in chunk]
                f.write(",\n".join(lines) + ";\n")


def main():
    build_taxonomy()
    build_venues()
    build_users()
    build_artists()
    build_events()
    build_performances()
    build_orders_tickets()
    plant_blocks()
    plant_cancellations()
    build_resale()
    build_reviews()

    emit_csvs()
    emit_load_sql()

    total = sum(len(v) for v in DATA.values())
    print(f"Generated {total} rows across {len(TABLE_ORDER)} tables.")
    print(f"  CSVs  -> {DATA_DIR}")
    print(f"  load  -> {LOAD_SQL}")


if __name__ == "__main__":
    main()