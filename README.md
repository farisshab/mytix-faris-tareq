## Prerequisites

Install these before you start:

- **JDK 17 or newer** | Verify with `java -version`
- **MySQL 8.4** (we run 8.4.10) | Server must be running locally. Verify with `mysql --version`
- **Git** | To clone the repository

---

## Setup

### 1. Clone the repository
```bash
git clone https://github.com/farisshab/mytix-faris-tareq
cd mytix-faris-tareq
```

### 2. Set up your local DB config
Copy the template. Edit `config.properties` only if your MySQL login differs
from the defaults (user `root`, no password):
```bash
cp config.properties.example config.properties
```
`config.properties` is gitignored, so your credentials stay on your machine.
`run.sh` also creates it from the template automatically on first run.

### 3. Create the schema and load the sample data
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
