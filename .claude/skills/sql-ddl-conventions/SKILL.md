---
name: sql-ddl-conventions
description: T-SQL DDL conventions and dependency ordering rules for SQL Server object synchronization
---

# SQL DDL Conventions for Adous

## Apply Order

The order lives in one place: `MSSQLDatabaseService.orderByDependency` / `getDependencyPriority`, documented in `docs/architecture.md` (Applying Changes). When you change it, update that section; don't copy the list elsewhere.

Current order: tables (FK-sorted) → full-text catalogs → scalar and table types → sequences → synonyms → functions and procedures → triggers → views (sorted by view references).
Known problem: types should come before tables, and views before triggers that use them.

## SQL Equivalence Checking

When comparing SQL statements for equivalence:
- Normalize whitespace (collapse to single spaces)
- Normalize case for keywords (UPPER)
- Strip comments before comparison
- Ignore trailing semicolons
- The default schema prefix (`spring.application.database.default-schema`, `dbo`) is removed, so `dbo.MyTable` = `MyTable`; other schemas are kept

## Safe Table Evolution

Tables require special care:
- Column additions: safe, apply directly
- Column renames: NEVER auto-rename — flag as manual intervention required
- Column and table drops: blocked unless the request sets `allowDataLoss=true` (separate from `force`); otherwise the DB is skipped with `SKIPPED_DATA_LOSS`
- Type changes: always check implicit conversion compatibility first

## .syncignore Pattern Matching

Patterns use glob syntax: `**/PROCEDURE/*/temp_*.sql`
- `**` matches any path segment
- `*` matches within a single segment
- Always test patterns against both base/ and diff/ paths

## T-SQL Style

- Always use schema prefix: `dbo.ObjectName`, never unqualified `ObjectName`
- Use `CREATE OR ALTER` for procedures and functions (SQL Server 2016+)
- For triggers: always specify `AFTER INSERT, UPDATE, DELETE` explicitly