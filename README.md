# MyTix

CSCC43 database project.

**Group members**
- Tareq Abousherif (1007977824)
- Faris Shabaytah (1011300986)

**To run:** `run.sh` (details under Running below).

---

## Prerequisites

Install these before you start:

- **JDK 17 or newer** | Verify with `java -version`
- **MySQL 8.4** (we run 8.4.10) | Server must be running locally. Verify with `mysql --version`

---

## Setup

All commands below are run from the project root (the directory this README is in).

### 1. Set up your local DB config
Copy the template. Edit `config.properties` only if your MySQL login differs
from the defaults (user `root`, no password):
```bash
cp config.properties.example config.properties
```
`config.properties` is gitignored, so your credentials stay on your machine.
`run.sh` also creates it from the template automatically on first run.

### 2. Create the schema and load the sample data
`schema.sql` creates the `mytix` database and every table; `load.sql` fills it
with sample data. Both select the database themselves, so no need to pass one:
```bash
mysql -u root -p < sql/schema.sql
mysql -u root -p < sql/load.sql
```
`-p` makes the client prompt for a MySQL password; press Enter if your `root`
account has none (the default here). Run these on a fresh MySQL instance;
`sql/drop.sql` resets an existing `mytix` database first if you need it.

## Running
```bash
chmod +x run.sh    # first time only
./run.sh
```

**Sample logins** (email-only sign-in, no password): `liam.roy1@example.com` is
an organizer, `harper.khan7@example.com` is a customer. Any email in
`data/users.csv` works.

---

## Repository layout

Graded deliverables:
- `report.pdf`, `manual.pdf` - the project report and user manual
- `sql/schema.sql`, `sql/drop.sql`, `sql/load.sql` - create, drop, and bulk-load the database
- `data/` - the sample-data CSVs the project requires (raw data; `load.sql` itself is self-contained INSERTs)
- `src/` - the Java application source (includes `src/lib/` with the MySQL Connector/J jar)
- `run.sh` - compiles `src/` and launches the app

Supporting material (not needed for grading):
- `sql/queries.sql`, `sql/reports.sql` - readable SQL reference for the queries and reports
- `docs/` - design notes (ER diagram, design decisions, report/query/toolkit design)
- `report/`, `manual/` - the Markdown sources and build scripts for the two PDFs
- `tools/` - the sample-data generator
- `config.properties.example` - template for the local DB config
