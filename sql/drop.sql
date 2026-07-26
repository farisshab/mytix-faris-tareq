-- MyTix drop.sql
-- Tears the whole schema back down. We switch off FK checks so the order
-- of the drops doesn't matter, then switch them back on.

USE mytix;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS review;
DROP TABLE IF EXISTS listing;
DROP TABLE IF EXISTS ticket_ownership;
DROP TABLE IF EXISTS seat_hold;
DROP TABLE IF EXISTS ticket;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS performance_section_tier;
DROP TABLE IF EXISTS price_tier;
DROP TABLE IF EXISTS performance;
DROP TABLE IF EXISTS event_artist;
DROP TABLE IF EXISTS event;
DROP TABLE IF EXISTS artist;
DROP TABLE IF EXISTS genre;
DROP TABLE IF EXISTS segment;
DROP TABLE IF EXISTS seat;
DROP TABLE IF EXISTS seat_row;
DROP TABLE IF EXISTS section;
DROP TABLE IF EXISTS credit_card;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS venue;

SET FOREIGN_KEY_CHECKS = 1;
