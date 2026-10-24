# 08 String-built SQL

Code: `java/.../Ex08StringBuiltSql.java`
Test: `Ex08StringBuiltSqlTest` (4 tests)

## Prompt that produces it
"Add a search on customer name and a sortable list" (when no repository abstraction is in sight, assistants fall back to
`JdbcTemplate` and concatenation, or to `@Query` with SpEL-concatenated fragments).

## Flawed code
```java
String sql = "SELECT id, status FROM orders WHERE customer_name LIKE '%" + name + "%' ORDER BY " + sort;
jdbc.query(sql, mapper);
```

## Why it is risky here
Classic SQL injection: `name = x' OR '1'='1` returns every customer's orders (BOLA at scale); `sort = created_at; DROP
TABLE orders` smuggles a statement or, with PostgreSQL, enables `pg_sleep` timing probes. Bind parameters cannot protect
identifiers (column names), which is exactly where `sort` lands, so identifiers need a whitelist, not escaping.

## Corrected code
```java
private static final Map<String,String> SORT = Map.of("createdAt","created_at","status","status","totalAmount","total_amount");
String col = SORT.get(sortField);                       // unknown -> 400 VALIDATION_FAILED
if (col == null) throw new IllegalArgumentException("sort field not allowed");
jdbc.query("SELECT id, status FROM orders WHERE customer_name LIKE ? ESCAPE '\\' ORDER BY " + col + dir,
           mapper, "%" + escapeLike(name) + "%");
```
Preferred: Spring Data derived queries/`Specification`/named parameters; never concatenate. LIKE wildcards in user input
(`%`, `_`) are escaped so they match literally.

## Regression test
Flawed: asserts the generated SQL contains the live `OR '1'='1` and `; DROP TABLE orders`. Fixed: SQL text contains no
user text and has `LIKE ?`; the payload appears only in the bound parameter list; non-whitelisted sort throws; `%` and `_`
are escaped. (The test inspects SQL text; no database is involved.)

CI guard (recommended, still open: no ArchUnit dependency or grep gate exists in the repo): a rule failing the build on `"SELECT` + `+`
concatenation in `src/main`.

## Review prompt to catch it
"Show me every place a request value reaches a SQL string. Which are values (bind) and which are identifiers (whitelist)?"
