# Changelog

All notable changes to Adous are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [0.2.0] - 2026-10-13

### Upgrading
- **Tags with `GIT_PREFIX_PATH`:** sync tags are now `{prefix}/{db}` instead of `{db}`. A database that only has the old `{db}` tag keeps using it until its next sync writes the prefixed tag. Without a prefix, tags are unchanged.
- **Table constraints now follow Git:** repo→db now adds, changes and drops foreign keys, unique and check constraints, column defaults and indexes to match the Git definition (0.1.0 ignored most of these). Run a dry run first and check `changes` for unexpected drops, especially for tables extracted by 0.1.0, which did not record foreign key `ON DELETE`/`ON UPDATE` actions or index options (clustered, columnstore, `INCLUDE`, `DESC`). Running db→repo first brings those definitions up to date.

### Features
- Table changes cover constraints, defaults and indexes: foreign keys (including `ON DELETE`/`ON UPDATE` actions), unique and check constraints, column defaults and indexes are added, changed and removed. Unchanged indexes are left alone instead of being rebuilt.
- Index kinds are kept: clustered, clustered and nonclustered columnstore, `INCLUDE` columns, descending keys, and primary keys on a nonclustered index. Changes to these are detected.
- Constraint and default names are kept as they are in the database.
- Swagger UI has an **Authorize** button for the API key, so "Try it out" works. The API version shown comes from the build.

### Bug Fixes
- Objects are applied in dependency order: full-text catalogs, types, sequences, tables (by foreign key), synonyms, functions, views, procedures, triggers. A new table using a new type or sequence, or a trigger on a new view, no longer fails when both arrive in one sync. Drops run in reverse order.
- With no `GIT_PREFIX_PATH`, db→repo failed whenever it had to write an override (invalid path `diff//db`).
- Deployments sharing a repository with different prefixes no longer share tags for databases with the same name.
- Altering a column that has a check constraint, default or index no longer fails; they are dropped and re-created around the change.
- Changing a primary key no longer leaves other tables' foreign keys to it dropped.
- XML, spatial and hash indexes are skipped with a warning instead of being written as invalid DDL.

### Internal
- Documentation rewritten to match the code (architecture, usage, supported features, Claude Code context).
- Integration tests for apply order, prefix namespacing, index kinds and table constraint changes.

## [0.1.0] - 2026-10-12

First release.

### Features
- Bidirectional sync between SQL Server databases and a Git repository: initialize a repo from a database, db→repo, and repo→db to several databases in parallel.
- Canonical `base/` definitions with per-database `diff/` overrides; Git tags track which commit each database is synced to.
- Supported objects: tables, views, functions, procedures, triggers, synonyms, sequences, user-defined table and scalar types, full-text catalogs.
- Dependency-ordered apply, dry-run mode, `.syncignore` patterns, and SQL equivalence checking that ignores formatting differences.
- Tables evolve with `ALTER TABLE` to keep data. Dropping a table or column requires `allowDataLoss: true`; otherwise the database is skipped with `SKIPPED_DATA_LOSS`.
- Procedures, functions, views and triggers are applied with `CREATE OR ALTER`, so permissions granted on them are kept.
- Existing sequences are changed with `ALTER SEQUENCE`, keeping their current value.
- Structured `changes` in API responses (files changed for db→repo, objects applied for repo→db).
- REST API with OpenAPI/Swagger UI, API key authentication, Prometheus metrics and health checks.
- Multi-arch Docker image (`linux/amd64`, `linux/arm64`) published to GHCR.

### Bug Fixes
- Rejected Git pushes are reported as failures instead of success; the local commit is rolled back and moved tags are re-fetched.
- One failing database no longer fails a whole repo→db batch; it is reported as `FAILED`.
- Concurrent sync requests are serialized so they can't corrupt the local Git state.
- Database names are matched case-insensitively.
- Sequences using the default cache size are read as `CACHE`, not `NO CACHE`.
- Earlier fixes: `ALTER TABLE` generation, non-existent ancestor tags in diffs, case-sensitive paths, default schema in SQL comparison, pushing tags to the remote.

### Security
- `API_KEY` is required; the service no longer falls back to a built-in key.
- API keys are compared in constant time.
- `/actuator/metrics` and `/actuator/prometheus` require the API key; only health, info and API docs are public.
- Spring Security debug logging is off by default; Adous logs at `INFO` (`ADOUS_LOG_LEVEL` to change).

### Internal
- Testcontainers integration tests against SQL Server 2022.
- CI: build and test on every PR. The Docker image is built (not pushed) on PRs that change it, and published on `main` and release tags.
- GitHub Actions moved to Node 24 versions.
- Claude Code project context, skills and agents.

[0.2.0]: https://github.com/majidkabir/Adous/releases/tag/v0.2.0
[0.1.0]: https://github.com/majidkabir/Adous/releases/tag/v0.1.0
