---
name: sql-ddl-conventions
description: T-SQL DDL conventions and dependency ordering rules for SQL Server object synchronization
---

# SQL DDL Conventions for Adous

## Supported Object Types (in dependency order)

1. USER_DEFINED_TYPE
2. SEQUENCE
3. TABLE
4. VIEW
5. FUNCTION (scalar before table-valued)
6. PROCEDURE
7. TRIGGER
8. SYNONYM

Always apply DDL in this order when syncing repo→db. Reverse the order for drops.

## SQL Equivalence Checking

When comparing SQL statements for equivalence:
- Normalize whitespace (collapse to single spaces)
- Normalize case for keywords (UPPER)
- Strip comments before comparison
- Ignore trailing semicolons
- Do NOT normalize schema names — dbo.MyTable ≠ MyTable

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