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
PERFORMANCES_BUILT = []  # per-perf info: id, venue, days offset, tier prices
CUSTOMER_CARD = {}       # customer user_id -> (card_number, name, "MM/YYYY") snapshot
TICKETS_BUILT = []       # per-ticket info for resale / cancellation / reviews
LOCKED_PERFS = set()     # performance_ids whose availability is planted (leave alone)
SCALPERS = []            # customer_ids planted to buy 10+ tickets (for the resale report)


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
        number = "".join(str(random.randint(0, 9)) for _ in range(16))
        month, year = random.randint(1, 12), random.randint(2027, 2032)
        add("credit_card", customer_id=uid, card_number=number, cardholder_name=name,
            expiry_month=month, expiry_year=year)
        CUSTOMER_CARD[uid] = (number, name, f"{month:02d}/{year}")


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
    """performance + price_tier + performance_section_tier. Plants a tour, a
    theatre run, and a same-venue pair with different section->tier maps, then
    fills the rest so we clear 60+ across past and upcoming dates."""

    def tier_prices(n_tiers, base):
        if n_tiers == 2:
            return [base, round(base * 0.6, 2)]
        return [base, round(base * 0.66, 2), round(base * 0.42, 2)]

    def base_price(kind):
        return {
            "arena": random.choice([150, 180, 200]),
            "stadium": random.choice([120, 150, 175]),
            "theatre": random.choice([90, 110, 130]),
            "club": random.choice([60, 75, 90]),
        }[kind]

    def venue_for(event):
        if event["kind"] == "sports":
            pool = [v for v in VENUES_BUILT if v["kind"] == "arena"]
        elif event["kind"] == "theatre":
            pool = [v for v in VENUES_BUILT if v["kind"] == "theatre"]
        else:
            pool = [v for v in VENUES_BUILT if v["kind"] in ("arena", "stadium", "club")]
        return random.choice(pool)

    def make_perf(event_id, vinfo, days, base, n_tiers, assign="best_first"):
        pid = add("performance", event_id=event_id, venue_id=vinfo["venue_id"],
                  performance_datetime=Rel(days=days), status="SCHEDULED", cancelled_at=None)
        tiers = []
        for i, price in enumerate(tier_prices(n_tiers, base), start=1):
            tid = add("price_tier", performance_id=pid, tier_code=f"P{i}", price=price)
            tiers.append((tid, f"P{i}", price))
        sections = vinfo["sections"]
        price_by_section = {}
        for i, sec in enumerate(sections):
            pos = len(sections) - 1 - i if assign == "reversed" else i
            ti = min(pos * n_tiers // max(len(sections), 1), n_tiers - 1)
            tid, _code, price = tiers[ti]
            add("performance_section_tier", performance_id=pid,
                section_id=sec["section_id"], tier_id=tid)
            price_by_section[sec["section_id"]] = price
        PERFORMANCES_BUILT.append({
            "performance_id": pid, "event_id": event_id, "venue": vinfo,
            "days": days, "is_past": days < 0,
            "price_by_section": price_by_section, "tiers": tiers,
        })
        return pid

    concerts = [e for e in EVENTS_BUILT if e["kind"] == "concert"]
    theatres = [e for e in EVENTS_BUILT if e["kind"] == "theatre"]
    theatre_venues = [v for v in VENUES_BUILT if v["kind"] == "theatre"]
    arenas = [v for v in VENUES_BUILT if v["kind"] == "arena"]
    stadiums = [v for v in VENUES_BUILT if v["kind"] == "stadium"]

    # 1) a tour: one concert at several venues, all upcoming
    tour = concerts[0]
    for i, vinfo in enumerate(random.sample(arenas + stadiums, 4)):
        make_perf(tour["event_id"], vinfo, days=10 + i * 6, base=base_price(vinfo["kind"]), n_tiers=3)

    # 2) a theatre run: one event, many nights at one theatre, past and upcoming
    run = theatres[0]
    run_venue = theatre_venues[0]
    for i in range(12):
        make_perf(run["event_id"], run_venue, days=-70 + i * 12, base=110, n_tiers=3)

    # 3) same venue, two performances with different section->tier maps and prices
    pair_event = concerts[1]
    pair_venue = arenas[0]
    make_perf(pair_event["event_id"], pair_venue, days=18, base=200, n_tiers=3, assign="best_first")
    make_perf(pair_event["event_id"], pair_venue, days=26, base=130, n_tiers=3, assign="reversed")

    # 4) one upcoming performance fewer than 7 days out (near-term scenario)
    make_perf(concerts[2]["event_id"], venue_for(concerts[2]), days=3, base=base_price("arena"), n_tiers=2)

    # 5) fill the rest to 60+, mixing past and upcoming (skipping the -7..7 window)
    while len(PERFORMANCES_BUILT) < 64:
        e = random.choice(EVENTS_BUILT)
        vinfo = venue_for(e)
        days = random.choice([random.randint(-330, -8), random.randint(8, 300)])
        make_perf(e["event_id"], vinfo, days=days, base=base_price(vinfo["kind"]),
                  n_tiers=random.choice([2, 3]))


def build_orders_tickets():
    """orders + ticket + seat_hold(SOLD) + ticket_ownership(PURCHASE). Spreads
    light sales across many shows, then plants the sold-out and under-25% past
    shows (across cities), an upcoming show that keeps a consecutive open row, a
    non-consecutive row and GA capacity, and a near-term show with a few sales."""

    taken = set() # (performance_id, seat_id) already sold
    ga_sold = {} # (performance_id, section_id) -> GA count sold
    count = {"orders": 0, "tickets": 0}

    def order_days(perf):
        # placed in the past, before the show
        return perf["days"] - random.randint(1, 30) if perf["is_past"] else -random.randint(1, 90)

    def place_order(customer_id, perf, specs, when=None):
        if not specs:
            return
        number, name, exp = CUSTOMER_CARD[customer_id]
        od = order_days(perf) if when is None else when
        oid = add("orders", customer_id=customer_id, performance_id=perf["performance_id"],
                  order_datetime=Rel(days=od), pay_card_number=number,
                  pay_cardholder=name, pay_expiry=exp)
        count["orders"] += 1
        for spec in specs:
            section_id = spec[1]
            seat_id = spec[2] if spec[0] == "R" else None
            fv = perf["price_by_section"][section_id]
            tid = add("ticket", order_id=oid, seat_id=seat_id,
                      ga_section_id=(None if seat_id else section_id),
                      face_value=fv, status="ACTIVE", cancelled_at=None, cancel_type=None)
            if seat_id is not None:
                add("seat_hold", performance_id=perf["performance_id"], seat_id=seat_id,
                    hold_type="SOLD", ticket_id=tid)
                taken.add((perf["performance_id"], seat_id))
            else:
                key = (perf["performance_id"], section_id)
                ga_sold[key] = ga_sold.get(key, 0) + 1
            add("ticket_ownership", ticket_id=tid, owner_id=customer_id,
                acquired_at=Rel(days=od), acquired_via="PURCHASE")
            TICKETS_BUILT.append({
                "ticket_id": tid, "performance_id": perf["performance_id"],
                "event_id": perf["event_id"], "is_past": perf["is_past"], "days": perf["days"],
                "section_id": section_id, "seat_id": seat_id, "face_value": fv,
                "owner_id": customer_id, "city": perf["venue"]["city"],
            })
        count["tickets"] += len(specs)

    def reserved_open(perf, only_section=None):
        pid = perf["performance_id"]
        out = []
        for sec in perf["venue"]["sections"]:
            if sec["type"] != "RESERVED" or (only_section and sec["section_id"] != only_section):
                continue
            for row in sec["rows"]:
                out += [(sec["section_id"], sid) for sid in row["seats"] if (pid, sid) not in taken]
        return out

    def ga_secs(perf):
        return [s for s in perf["venue"]["sections"] if s["type"] == "GA"]

    def sell_specs(perf, specs):
        random.shuffle(specs)
        i = 0
        while i < len(specs):
            n = min(random.randint(1, 4), len(specs) - i)
            place_order(random.choice(buyers), perf, specs[i:i + n])
            i += n

    def sell_fraction(perf, frac):
        seats = reserved_open(perf)
        random.shuffle(seats)
        specs = [("R", sid, seat) for sid, seat in seats[:int(len(seats) * frac)]]
        for sec in ga_secs(perf):
            specs += [("GA", sec["section_id"])] * int(sec["ga_capacity"] * frac)
        sell_specs(perf, specs)

    def sellable(perf):
        r = sum(len(row["seats"]) for sec in perf["venue"]["sections"]
                if sec["type"] == "RESERVED" for row in sec["rows"])
        g = sum(sec["ga_capacity"] for sec in perf["venue"]["sections"] if sec["type"] == "GA")
        return r + g

    past = [p for p in PERFORMANCES_BUILT if p["is_past"]]
    upcoming = [p for p in PERFORMANCES_BUILT if not p["is_past"]]

    # the two scalpers only ever buy their planted tickets, so keep them out of the
    # random passes; everyone else is a normal buyer
    SCALPERS[:] = random.sample(CUSTOMERS, 2)
    buyers = [c for c in CUSTOMERS if c not in set(SCALPERS)]

    # pick the scenario shows first, spread across cities, so the light pass avoids them
    past_by_city = {}
    for p in past:
        past_by_city.setdefault(p["venue"]["city"], []).append(p)
    cities = list(past_by_city)

    soldout, low, used = [], [], set()
    for city in cities[:2]:
        perf = min(past_by_city[city], key=sellable)
        soldout.append(perf); used.add(perf["performance_id"])
    for city in cities:
        if len(low) >= 3:
            break
        cands = [p for p in past_by_city[city] if p["performance_id"] not in used]
        if cands:
            perf = min(cands, key=sellable)
            low.append(perf); used.add(perf["performance_id"])

    demo = next((p for p in upcoming if p["days"] > 7
                 and any(s["type"] == "GA" for s in p["venue"]["sections"])
                 and any(s["type"] == "RESERVED" for s in p["venue"]["sections"])), None)
    near = next((p for p in upcoming if 0 < p["days"] < 7), None)
    scenario = {p["performance_id"] for p in soldout + low}
    scenario |= {p["performance_id"] for p in (demo, near) if p}

    # light pass: a few small orders across the other shows, for report breadth
    for perf in past + upcoming:
        if perf["performance_id"] in scenario:
            continue
        for _ in range(random.randint(0, 2)):
            seats = reserved_open(perf)
            if seats:
                place_order(random.choice(buyers), perf,
                            [("R", sid, seat) for sid, seat in seats[:random.randint(1, 3)]])

    # sold-out and under-25% past shows, leave the latter locked so nothing tops them up
    for perf in soldout:
        sell_fraction(perf, 1.0)
        LOCKED_PERFS.add(perf["performance_id"])
    for perf in low:
        sell_fraction(perf, 0.15)
        LOCKED_PERFS.add(perf["performance_id"])

    # upcoming availability demo: sell every other seat in one row, leaving that
    # row non-consecutive, every other row fully open, and the GA section fully untouched
    if demo:
        sec = next(s for s in demo["venue"]["sections"] if s["type"] == "RESERVED")
        row = sec["rows"][0]
        sell_specs(demo, [("R", sec["section_id"], sid)
                          for i, sid in enumerate(row["seats"]) if i % 2 == 0])
        LOCKED_PERFS.add(demo["performance_id"])

    # near-term (<7 days) upcoming show with a handful of sales
    if near:
        sell_specs(near, [("R", sid, seat) for sid, seat in reserved_open(near)[:6]])
        LOCKED_PERFS.add(near["performance_id"])

    # several repeat buyers: 2 orders each in two different cities, within the year
    by_city = {}
    for p in upcoming:
        if p["performance_id"] not in LOCKED_PERFS:
            by_city.setdefault(p["venue"]["city"], []).append(p)
    upc_cities = [c for c in by_city if by_city[c]]
    for cust in random.sample(buyers, 10):
        for city in random.sample(upc_cities, min(2, len(upc_cities))):
            perf = random.choice(by_city[city])
            seats = reserved_open(perf)
            if seats:
                place_order(cust, perf, [("R", sid, seat) for sid, seat in seats[:random.randint(1, 2)]],
                            when=-random.randint(1, 300))

    # the two planted scalpers buy 12+ upcoming tickets each (and nothing else)
    open_upcoming = [p for p in upcoming if p["performance_id"] not in LOCKED_PERFS]
    for cust in SCALPERS:
        bought = 0
        for perf in random.sample(open_upcoming, len(open_upcoming)):
            seats = reserved_open(perf)
            if seats:
                n = min(random.randint(2, 4), len(seats))
                place_order(cust, perf, [("R", sid, seat) for sid, seat in seats[:n]],
                            when=-random.randint(1, 200))
                bought += n
            if bought >= 12:
                break

    # safety top-up to clear the minimums, still avoiding the planted shows
    pool = [p for p in past + upcoming if p["performance_id"] not in LOCKED_PERFS]
    while pool and (count["orders"] < NUM_ORDERS or count["tickets"] < MIN_TICKETS):
        perf = random.choice(pool)
        seats = reserved_open(perf)
        if seats:
            place_order(random.choice(buyers), perf,
                        [("R", sid, seat) for sid, seat in seats[:random.randint(1, 4)]])
        else:
            pool = [p for p in pool if p["performance_id"] != perf["performance_id"]]


def plant_blocks():
    """seat_hold(BLOCKED). Blocks a handful of available reserved seats in a few
    non-scenario shows so Q6 and the block/unblock ops have data. Never blocks a
    sold seat (the shared primary key on seat_hold makes that impossible anyway)."""
    held = {(r[0], r[1]) for r in DATA["seat_hold"]}
    candidates = [p for p in PERFORMANCES_BUILT if p["performance_id"] not in LOCKED_PERFS]
    for perf in random.sample(candidates, min(5, len(candidates))):
        pid = perf["performance_id"]
        free = [sid for sec in perf["venue"]["sections"] if sec["type"] == "RESERVED"
                for row in sec["rows"] for sid in row["seats"] if (pid, sid) not in held]
        random.shuffle(free)
        for sid in free[:random.randint(6, 12)]:
            add("seat_hold", performance_id=pid, seat_id=sid, hold_type="BLOCKED", ticket_id=None)
            held.add((pid, sid))


def plant_cancellations():
    """Customer and organizer cancellations. Marks tickets CANCELLED and frees
    their seat_hold rows; cancels two past-year performances outright."""
    ti = COLUMNS["ticket"].index
    pi = COLUMNS["performance"].index
    ticket_row = {r[0]: r for r in DATA["ticket"]}
    perf_row = {r[0]: r for r in DATA["performance"]}

    def free_seat(pid, seat_id):
        for i, r in enumerate(DATA["seat_hold"]):
            if r[0] == pid and r[1] == seat_id:
                DATA["seat_hold"].pop(i)
                return

    def cancel_ticket(e, ctype, when_days):
        row = ticket_row[e["ticket_id"]]
        row[ti("status")] = "CANCELLED"
        row[ti("cancelled_at")] = Rel(days=when_days)
        row[ti("cancel_type")] = ctype
        if e["seat_id"] is not None:
            free_seat(e["performance_id"], e["seat_id"])
        e["active"] = False

    tickets_by_perf = {}
    for e in TICKETS_BUILT:
        tickets_by_perf.setdefault(e["performance_id"], []).append(e)

    protected = set(LOCKED_PERFS)

    # organizer cancels two past-year performances (and all their tickets)
    org_targets = [p for p in PERFORMANCES_BUILT if p["is_past"] and p["days"] >= -365
                   and p["performance_id"] not in protected and tickets_by_perf.get(p["performance_id"])]
    for perf in random.sample(org_targets, min(2, len(org_targets))):
        pid = perf["performance_id"]
        perf_row[pid][pi("status")] = "CANCELLED"
        perf_row[pid][pi("cancelled_at")] = Rel(days=perf["days"] - 2)
        for e in tickets_by_perf[pid]:
            if e.get("active", True):
                cancel_ticket(e, "PERFORMANCE", perf["days"] - 2)
        DATA["seat_hold"][:] = [r for r in DATA["seat_hold"] if r[0] != pid]  # free leftover holds
        protected.add(pid)

    # a spread of customers each cancel one ticket, 7+ days before their show
    cancellable = {}
    for e in TICKETS_BUILT:
        if e.get("active", True) and e["performance_id"] not in protected and (e["is_past"] or e["days"] > 7):
            cancellable.setdefault(e["owner_id"], []).append(e)
    for owner in random.sample(list(cancellable), min(15, len(cancellable))):
        e = cancellable[owner][0]
        when = e["days"] - 8 if e["is_past"] else -random.randint(1, 5)
        cancel_ticket(e, "CUSTOMER", when)


def build_resale():
    """listing + ticket_ownership(RESALE). Listings in every status, one exactly at
    the cap, a ticket resold twice, and the planted scalpers listing most of what
    they bought. Resale is on upcoming shows (you sell before the show)."""
    cap_pct = {r[0]: float(r[4]) for r in DATA["event"]}   # event_id -> resale_cap_pct

    def cap_of(e):
        return round(e["face_value"] * cap_pct[e["event_id"]] / 100, 2)

    def under_cap(e):
        return min(round(e["face_value"] * random.uniform(1.0, cap_pct[e["event_id"]] / 100), 2), cap_of(e))

    def other_than(owner):
        c = random.choice(CUSTOMERS)
        while c == owner:
            c = random.choice(CUSTOMERS)
        return c

    def list_ticket(e, status, price, created_days, buyer=None):
        closed = Rel(days=created_days + random.randint(1, 6)) if status != "ACTIVE" else None
        add("listing", ticket_id=e["ticket_id"], seller_id=e["owner_id"], list_price=price,
            status=status, created_at=Rel(days=created_days), closed_at=closed, buyer_id=buyer)
        if status == "SOLD":
            add("ticket_ownership", ticket_id=e["ticket_id"], owner_id=buyer,
                acquired_at=Rel(days=created_days + random.randint(1, 6), hours=random.randint(1, 12)),
                acquired_via="RESALE")
            e["owner_id"] = buyer

    upcoming_active = [e for e in TICKETS_BUILT if e.get("active", True) and not e["is_past"]]
    used = set()

    # scalpers list most of what they bought
    for cust in SCALPERS:
        ts = [e for e in upcoming_active if e["owner_id"] == cust]
        for e in ts[:int(len(ts) * 0.7) + 1]:
            list_ticket(e, random.choice(["ACTIVE", "WITHDRAWN"]), under_cap(e), -random.randint(1, 40))
            used.add(e["ticket_id"])

    rest = [e for e in upcoming_active if e["ticket_id"] not in used and e["owner_id"] not in SCALPERS]
    random.shuffle(rest)
    ptr = 0

    def take():
        nonlocal ptr
        if ptr >= len(rest):
            return None
        e = rest[ptr]
        ptr += 1
        return e

    for _ in range(5):
        e = take()
        if e:
            list_ticket(e, "WITHDRAWN", under_cap(e), -random.randint(20, 60))
    for _ in range(8):
        e = take()
        if e:
            list_ticket(e, "ACTIVE", under_cap(e), -random.randint(1, 20))
    e = take()
    if e:
        list_ticket(e, "ACTIVE", cap_of(e), -random.randint(1, 20))        # exactly at the cap
    for _ in range(6):
        e = take()
        if e:
            list_ticket(e, "SOLD", under_cap(e), -random.randint(15, 50), buyer=other_than(e["owner_id"]))

    # one ticket that changes owners twice (purchase + two resales)
    tw = take()
    if tw:
        list_ticket(tw, "SOLD", under_cap(tw), -45, buyer=other_than(tw["owner_id"]))
        list_ticket(tw, "SOLD", under_cap(tw), -22, buyer=other_than(tw["owner_id"]))


def build_reviews():
    """review. Attendees (current owner of a non-cancelled ticket for a past show)
    leave several reviews across 10+ events, at most one per (customer, performance),
    with multi-sentence comments and 1-5 ratings."""
    perf_days = {p["performance_id"]: p["days"] for p in PERFORMANCES_BUILT}
    by_event = {}
    for e in TICKETS_BUILT:
        if e["is_past"] and e.get("active", True) and perf_days[e["performance_id"]] >= -365:
            by_event.setdefault(e["event_id"], set()).add((e["performance_id"], e["owner_id"]))

    made = set()
    events_done = 0
    for pairs in by_event.values():
        if events_done >= 15:
            break
        pairs = list(pairs)
        random.shuffle(pairs)
        target = random.randint(3, 5)
        n = 0
        for pid, cust in pairs:
            if (cust, pid) in made:
                continue
            comment = " ".join(random.sample(REVIEW_SENTENCES, random.randint(2, 3)))
            created = min(perf_days[pid] + random.randint(1, 20), -1)
            add("review", customer_id=cust, performance_id=pid,
                event_rating=random.randint(1, 5), venue_rating=random.randint(1, 5),
                comment_text=comment, created_at=Rel(days=created))
            made.add((cust, pid))
            n += 1
            if n >= target:
                break
        if n:
            events_done += 1


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