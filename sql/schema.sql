-- MyTix schema (MySQL 8.4)
-- Creates the database, every table, and every constraint in one run.
-- The full reasoning behind these choices lives in design_decisions.md.
-- Heads up: `order` and `row` are reserved words, so they're named
-- `orders` and `seat_row` here.
--
-- Sections: 1 users/cards, 2 venues, 3 taxonomy, 4 events, 5 pricing,
-- 6 orders & tickets, 7 resale, 8 reviews.

SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS mytix CHARACTER SET utf8mb4;
USE mytix;


-- 1. Users and cards

-- Customers and organizers share one table, split by role.
-- Ones with history get soft-deleted instead of removed, so their orders survive.
CREATE TABLE users (
  user_id INT AUTO_INCREMENT PRIMARY KEY,
  full_name VARCHAR(255) NOT NULL,
  address VARCHAR(500) NOT NULL,
  email VARCHAR(255) NOT NULL,
  date_of_birth DATE NOT NULL,  -- 18+, checked in the app (a CHECK can't read today's date)
  role ENUM('CUSTOMER','ORGANIZER') NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (email)
) ENGINE=InnoDB;

-- Cards saved at signup. Orders snapshot the card they used, so editing
-- or deleting one here never rewrites an old order.
CREATE TABLE credit_card (
  card_id INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT NOT NULL,
  card_number VARCHAR(25) NOT NULL,
  cardholder_name VARCHAR(255) NOT NULL,
  expiry_month TINYINT NOT NULL,
  expiry_year SMALLINT NOT NULL,
  FOREIGN KEY (customer_id) REFERENCES users(user_id),
  CHECK (expiry_month BETWEEN 1 AND 12)
) ENGINE=InnoDB;


-- 2. Venues, sections, rows, seats

-- Lat/long power the distance search (Q1). City and postal stay here on the
-- venue, never copied down onto performances.
CREATE TABLE venue (
  venue_id INT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  latitude DECIMAL(9,6) NOT NULL,
  longitude DECIMAL(9,6) NOT NULL,
  address VARCHAR(500) NOT NULL,
  postal_code VARCHAR(20) NOT NULL,
  city VARCHAR(120) NOT NULL,
  country VARCHAR(120) NOT NULL,
  CHECK (latitude BETWEEN -90 AND 90),
  CHECK (longitude BETWEEN -180 AND 180)
) ENGINE=InnoDB;

-- Reserved sections have rows and seats; GA sections just hold a capacity.
-- The check stops the two kinds from mixing.
CREATE TABLE section (
  section_id INT AUTO_INCREMENT PRIMARY KEY,
  venue_id INT NOT NULL,
  section_name VARCHAR(120) NOT NULL,
  section_type ENUM('RESERVED','GA') NOT NULL,
  ga_capacity INT NULL,
  UNIQUE (venue_id, section_name),
  FOREIGN KEY (venue_id) REFERENCES venue(venue_id),
  CHECK ( (section_type = 'GA' AND ga_capacity IS NOT NULL AND ga_capacity > 0)
       OR (section_type = 'RESERVED' AND ga_capacity IS NULL) )
) ENGINE=InnoDB;

-- Its own table mostly so "consecutive seats in a row" (Q7) is an easy group-by.
CREATE TABLE seat_row (
  row_id INT AUTO_INCREMENT PRIMARY KEY,
  section_id INT NOT NULL,
  row_name VARCHAR(60) NOT NULL,
  UNIQUE (section_id, row_name),
  FOREIGN KEY (section_id) REFERENCES section(section_id)
) ENGINE=InnoDB;

-- seat_number is an INT so "consecutive" actually means something.
CREATE TABLE seat (
  seat_id INT AUTO_INCREMENT PRIMARY KEY,
  row_id INT NOT NULL,
  seat_number INT NOT NULL,
  UNIQUE (row_id, seat_number),
  FOREIGN KEY (row_id) REFERENCES seat_row(row_id)
) ENGINE=InnoDB;


-- 3. Taxonomy and artists

-- Ticketmaster-style taxonomy (e.g. Music/Rock, Sports/Basketball).
-- An event points at a genre only; its segment tags along through the genre.
CREATE TABLE segment (
  segment_id INT AUTO_INCREMENT PRIMARY KEY,
  segment_name VARCHAR(120) NOT NULL,
  UNIQUE (segment_name)
) ENGINE=InnoDB;

CREATE TABLE genre (
  genre_id INT AUTO_INCREMENT PRIMARY KEY,
  segment_id INT NOT NULL,
  genre_name VARCHAR(120) NOT NULL,
  UNIQUE (segment_id, genre_name),
  FOREIGN KEY (segment_id) REFERENCES segment(segment_id)
) ENGINE=InnoDB;

-- People and teams both go here; a headliner and a home team are alike to us.
CREATE TABLE artist (
  artist_id INT AUTO_INCREMENT PRIMARY KEY,
  artist_name VARCHAR(255) NOT NULL,
  artist_type ENUM('INDIVIDUAL','TEAM') NOT NULL
) ENGINE=InnoDB;


-- 4. Events and performances

-- resale_cap_pct caps resale prices, 120% of face value by default.
CREATE TABLE event (
  event_id INT AUTO_INCREMENT PRIMARY KEY,
  organizer_id INT NOT NULL,
  title VARCHAR(255) NOT NULL,
  genre_id INT NOT NULL,
  resale_cap_pct DECIMAL(5,2) NOT NULL DEFAULT 120.00,
  FOREIGN KEY (organizer_id) REFERENCES users(user_id),
  FOREIGN KEY (genre_id) REFERENCES genre(genre_id),
  CHECK (resale_cap_pct >= 100)
) ENGINE=InnoDB;

-- The bill for an event. The same artist can headline one and open another,
-- so billing lives on the pairing.
CREATE TABLE event_artist (
  event_id INT NOT NULL,
  artist_id INT NOT NULL,
  billing_order ENUM('HEADLINER','SPECIAL_GUEST','OPENING_ACT') NOT NULL,
  PRIMARY KEY (event_id, artist_id),
  FOREIGN KEY (event_id) REFERENCES event(event_id),
  FOREIGN KEY (artist_id) REFERENCES artist(artist_id)
) ENGINE=InnoDB;

-- One event at one venue on one date. A tour is just many of these.
CREATE TABLE performance (
  performance_id INT AUTO_INCREMENT PRIMARY KEY,
  event_id INT NOT NULL,
  venue_id INT NOT NULL,
  performance_datetime DATETIME NOT NULL,
  status ENUM('SCHEDULED','CANCELLED') NOT NULL DEFAULT 'SCHEDULED',
  cancelled_at DATETIME NULL,
  FOREIGN KEY (event_id) REFERENCES event(event_id),
  FOREIGN KEY (venue_id) REFERENCES venue(venue_id),
  CHECK ( (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
       OR (status = 'SCHEDULED' AND cancelled_at IS NULL) )
) ENGINE=InnoDB;


-- 5. Pricing: tiers and section-to-tier

-- Each performance sets its own tiers and prices. The (performance_id, tier_id)
-- unique key is what the assignment below points back to.
CREATE TABLE price_tier (
  tier_id INT AUTO_INCREMENT PRIMARY KEY,
  performance_id INT NOT NULL,
  tier_code VARCHAR(10) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  UNIQUE (performance_id, tier_code),
  UNIQUE (performance_id, tier_id),
  FOREIGN KEY (performance_id) REFERENCES performance(performance_id),
  CHECK (price >= 0)
) ENGINE=InnoDB;

-- Which tier a section sits in for THIS performance, so the Floor can be P1
-- one night and P2 the next. The composite FK keeps the tier tied to the same
-- performance. Left in 3NF on purpose (the notes explain why).
CREATE TABLE performance_section_tier (
  performance_id INT NOT NULL,
  section_id INT NOT NULL,
  tier_id INT NOT NULL,
  PRIMARY KEY (performance_id, section_id),
  INDEX idx_pst_tier (performance_id, tier_id),
  FOREIGN KEY (section_id) REFERENCES section(section_id),
  FOREIGN KEY (performance_id, tier_id) REFERENCES price_tier(performance_id, tier_id)
) ENGINE=InnoDB;


-- 6. Orders, tickets, and live seat inventory

-- One order, one performance, one or more tickets. pay_* is the card snapshot.
CREATE TABLE orders (
  order_id INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT NOT NULL,
  performance_id INT NOT NULL,
  order_datetime DATETIME NOT NULL,
  pay_card_number VARCHAR(25) NOT NULL,
  pay_cardholder VARCHAR(255) NOT NULL,
  pay_expiry VARCHAR(7) NOT NULL,  -- 'MM/YYYY'
  FOREIGN KEY (customer_id) REFERENCES users(user_id),
  FOREIGN KEY (performance_id) REFERENCES performance(performance_id)
) ENGINE=InnoDB;

-- The lasting record of a sold seat, kept even after cancellation so history
-- survives. Reserved uses seat_id, GA uses ga_section_id, never both.
-- face_value is frozen at sale time, so later price changes don't touch it.
CREATE TABLE ticket (
  ticket_id INT AUTO_INCREMENT PRIMARY KEY,
  order_id INT NOT NULL,
  seat_id INT NULL,
  ga_section_id INT NULL,
  face_value DECIMAL(10,2) NOT NULL,
  status ENUM('ACTIVE','CANCELLED') NOT NULL DEFAULT 'ACTIVE',
  cancelled_at DATETIME NULL,
  cancel_type ENUM('CUSTOMER','PERFORMANCE') NULL,
  FOREIGN KEY (order_id) REFERENCES orders(order_id),
  FOREIGN KEY (seat_id) REFERENCES seat(seat_id),
  FOREIGN KEY (ga_section_id) REFERENCES section(section_id),
  CHECK ( (seat_id IS NULL) <> (ga_section_id IS NULL) ),  -- exactly one of them
  CHECK ( (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancel_type IS NOT NULL)
       OR (status = 'ACTIVE' AND cancelled_at IS NULL AND cancel_type IS NULL) )
) ENGINE=InnoDB;

-- Live "this reserved seat is taken" rows. The primary key is the real trick:
-- it makes double-selling impossible and stops a seat being sold and blocked
-- at once. Free a seat by deleting its row; the ticket record stays put.
CREATE TABLE seat_hold (
  performance_id INT NOT NULL,
  seat_id INT NOT NULL,
  hold_type ENUM('SOLD','BLOCKED') NOT NULL,
  ticket_id INT NULL,
  PRIMARY KEY (performance_id, seat_id),
  FOREIGN KEY (performance_id) REFERENCES performance(performance_id),
  FOREIGN KEY (seat_id) REFERENCES seat(seat_id),
  FOREIGN KEY (ticket_id) REFERENCES ticket(ticket_id),
  CHECK ( (hold_type = 'SOLD' AND ticket_id IS NOT NULL)
       OR (hold_type = 'BLOCKED' AND ticket_id IS NULL) )
) ENGINE=InnoDB;


-- 7. Ownership and resale

-- Every owner a ticket has had. The latest acquired_at is who holds it now;
-- the first row is the original purchase, each resale adds another.
CREATE TABLE ticket_ownership (
  ownership_id INT AUTO_INCREMENT PRIMARY KEY,
  ticket_id INT NOT NULL,
  owner_id INT NOT NULL,
  acquired_at DATETIME NOT NULL,
  acquired_via ENUM('PURCHASE','RESALE') NOT NULL,
  UNIQUE (ticket_id, acquired_at),
  FOREIGN KEY (ticket_id) REFERENCES ticket(ticket_id),
  FOREIGN KEY (owner_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

-- A ticket put up for resale. The generated key allows just one active listing
-- per ticket. Price-vs-cap spans tables, so the app enforces that part.
CREATE TABLE listing (
  listing_id INT AUTO_INCREMENT PRIMARY KEY,
  ticket_id INT NOT NULL,
  seller_id INT NOT NULL,
  list_price DECIMAL(10,2) NOT NULL,
  status ENUM('ACTIVE','SOLD','WITHDRAWN') NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME NOT NULL,
  closed_at DATETIME NULL,
  buyer_id INT NULL,
  active_listing_key INT GENERATED ALWAYS AS
      (CASE WHEN status = 'ACTIVE' THEN ticket_id ELSE NULL END) VIRTUAL,
  UNIQUE (active_listing_key),
  FOREIGN KEY (ticket_id) REFERENCES ticket(ticket_id),
  FOREIGN KEY (seller_id) REFERENCES users(user_id),
  FOREIGN KEY (buyer_id) REFERENCES users(user_id),
  CHECK (list_price >= 0),
  CHECK ( (status = 'ACTIVE' AND buyer_id IS NULL AND closed_at IS NULL)
       OR (status = 'SOLD' AND buyer_id IS NOT NULL AND closed_at IS NOT NULL)
       OR (status = 'WITHDRAWN' AND buyer_id IS NULL AND closed_at IS NOT NULL) )
) ENGINE=InnoDB;


-- 8. Reviews

-- One review per performance you actually attended. Rates the event and the
-- venue, plus the free text that feeds the noun-phrase report (R9).
CREATE TABLE review (
  review_id INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT NOT NULL,
  performance_id INT NOT NULL,
  event_rating TINYINT NOT NULL,
  venue_rating TINYINT NOT NULL,
  comment_text TEXT,
  created_at DATETIME NOT NULL,
  UNIQUE (customer_id, performance_id),
  FOREIGN KEY (customer_id) REFERENCES users(user_id),
  FOREIGN KEY (performance_id) REFERENCES performance(performance_id),
  CHECK (event_rating BETWEEN 1 AND 5),
  CHECK (venue_rating BETWEEN 1 AND 5)
) ENGINE=InnoDB;
