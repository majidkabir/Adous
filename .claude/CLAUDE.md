# Adous — Project Context for Claude

## What This Project Is

Adous is a Spring Boot service that synchronizes SQL Server database schemas with a Git repository, bidirectionally. It's a "Database as Code" tool — think of it like Liquibase but driven by actual DB state rather than migration scripts.

## Key Invariants

- Every sync operation MUST support dryRun mode
- DDL must be applied in dependency order — see `docs/architecture.md` (Applying Changes) for the current order
- Tagging in Git tracks which commit each database is synced to (one tag per database, `{prefix-path}/{db}` or `{db}`) — never break this contract
- The `base/` path holds canonical versions; `diff/` holds database-specific overrides. db→repo only ever writes `diff/`
- Never drop tables or columns without `allowDataLoss`

## Active Skills

- `adous-architecture` — layering and naming rules
- `sql-ddl-conventions` — T-SQL patterns and DDL ordering
- `testcontainers-testing` — integration test conventions
- `git-sync-safety` — sync safety rules

## Active Agents

- `@sync-flow-reviewer` — deep sync logic review
- `/release-prepare <version>` — release PR (version bump + changelog); merging it tags and publishes

## Common Tasks

- Add new DB object type: implement in `MSSQLDatabaseService`, add it to `getDependencyPriority`, document it in `docs/architecture.md` and `docs/MSSQL_FEATURES_SUPPORT.md`
- Add new API endpoint: controller → service → add dryRun support → add integration test
- Debug sync issues: check the database's tag first (`git log -1 <prefix>/<dbname>`, lower case)
- Keep `docs/` in sync with behaviour changes

