# Changelog

All notable changes to Adous are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

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

[0.1.0]: https://github.com/majidkabir/Adous/releases/tag/v0.1.0
