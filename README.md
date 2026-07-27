## Prerequisites

Install these before you start:

| **JDK 17 or newer** | Verify with `java -version` |
| **MySQL 8.0+** | Server must be running locally. Verify with `mysql --version` |
| **Git** | To clone the repository |

---

## Setup

### 1. Clone the repository
```bash
git clone <repository-url>
cd mytix
```

### 2. Create the database locally and a dedicated user

Log in as an administrative MySQL user:

```bash
mysql -u root -p
```

Then run, in order:

```sql
CREATE DATABASE mytix;
EXIT;
```

Then load the schema into it:
```bash
mysql -u root -p mytix < sql/schema.sql
mysql -u root -p mytix < load.sql (yet to be implemented)
```

`schema.sql` creates the tables, `load.sql` populates them with sample rows, but this has yet to be implemented.

## Running
```bash
chmod +x run.sh    # first time only
./run.sh
```