# Adous — Project Context for Claude

## What This Project Is

Adous is a Spring Boot service that synchronizes SQL Server database schemas with a Git repository, bidirectionally. It's a "Database as Code" tool — think of it like Liquibase but driven by actual DB state rather than migration scripts.

## Key Invariants

- Every sync operation MUST support dryRun mode
- DDL must be applied in dependency order (types → tables → views → functions → procedures → triggers)
- Tagging in Git tracks which commit each database is synced to — never break this contract
- The `base/` path holds canonical versions; `diff/` holds database-specific overrides

## Active Skills

- `adous-architecture` — layering and naming rules
- `sql-ddl-conventions` — T-SQL patterns and DDL ordering
- `testcontainers-testing` — integration test conventions
- `git-sync-safety` — sync safety rules

## Active Agents

- `@sync-flow-reviewer` — deep sync logic review
- `@sql-equivalence-analyst` — T-SQL comparison analysis  
- `/release-prepare <version>` — release workflow

## Common Tasks

- Add new DB object type: implement in `MSSQLDatabaseService`, add to dependency order in DDL skill
- Add new API endpoint: controller → service → add dryRun support → add integration test
- Debug sync issues: check Git tags first (`git tag --list "*dbName*"`)

