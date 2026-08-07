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

## Running
```bash
chmod +x run.sh    # first time only
./run.sh
```
