# Adous Architecture

How Adous works internally: repository layout, the three sync flows, how objects are extracted, compared and applied, tagging, and concurrency.

## Table of Contents

- [Overview](#overview)
- [Core Components](#core-components)
- [Repository Layout](#repository-layout)
- [Sync Flows](#sync-flows)
- [Object Extraction](#object-extraction)
- [SQL Equivalence](#sql-equivalence)
- [Applying Changes](#applying-changes)
- [Tagging](#tagging)
- [Concurrency and Failure Handling](#concurrency-and-failure-handling)
- [Known Limitations](#known-limitations)

## Overview

Adous keeps SQL Server schemas in Git. It extracts object definitions from databases into `.sql` files, and applies the files in a Git commit back to one or more databases.

```
┌─────────────┐         ┌─────────────┐         ┌─────────────┐
│  Databases  │ ◄─────► │    Adous    │ ◄─────► │     Git     │
│ (SQL Server)│         │  (REST API) │         │  repository │
└─────────────┘         └─────────────┘         └─────────────┘
   extract / apply DDL     compare, order,         commit, tag,
                           guard, report           push, fetch
```

Adous keeps a bare clone of the remote repository and fetches before every operation. All writes go to the default branch (`GIT_DEFAULT_BRANCH`, `main` by default) and are pushed immediately.

## Core Components

| Component | Responsibility |
|---|---|
| `SynchronizerController` | REST endpoints (`init-repo`, `db-to-repo`, `repo-to-db`) |
| `DatabaseRepositorySynchronizerService` | Orchestrates the flows, out-of-sync and data-loss checks, per-database results |
| `GitService` (+ `GitCommitService`, `GitRemoteService`, `GitDiffService`, `GitRepository`) | In-memory commits, tags, fetch and push (JGit), diffs between commits |
| `MSSQLDatabaseService` | Extracts definitions from system catalogs, orders and applies changes |
| `TableAlterScriptGenerator` | Turns a table definition change into `ALTER TABLE` statements; flags data-loss statements |
| `SequenceAlterScriptGenerator` | Applies sequence changes with `ALTER SEQUENCE`, keeping the current value |
| `ModuleDefinitionRewriter` | Rewrites module definitions to `CREATE OR ALTER` (and back when reading) |
| `SqlEquivalenceCheckerService` | Compares definitions while ignoring formatting differences |
| `SynchronizerIgnoreService` | Applies `.syncignore` glob patterns to repository paths |

## Repository Layout

```
base/{TYPE}/{schema}/{name}.sql                    canonical definition
diff/[{prefix-path}/]{db}/{TYPE}/{schema}/{name}.sql database-specific override
```

- `{TYPE}` is the object type in upper case (`TABLE`, `VIEW`, `PROCEDURE`, ...). `{schema}`, `{name}` and `{db}` are lower case.
- An override replaces the base definition for that database only.
- An **empty** override file means the object does not exist in that database.
- `prefix-path` (`GIT_PREFIX_PATH`) is a namespace for one deployment of Adous: its overrides go under `diff/{prefix-path}/` and its tags are named `{prefix-path}/{db}`. Without a prefix, overrides go straight under `diff/`.

## Sync Flows

### init-repo

1. Requires an empty repository.
2. Extracts every object from the database (minus `.syncignore` matches) and writes it to `base/`.
3. Commits, pushes, and creates the database's tag on that commit.

### db-to-repo

1. Fetches, then compares the database with the repository. If the database has a tag, the comparison uses the tagged commit; otherwise it uses `HEAD`.
2. For each object, it compares the database definition with the `base/` and override files:

| Database definition | Result | Response `action` |
|---|---|---|
| equals `base/` | override file removed, if one exists | `DELETE` |
| object missing | empty override written | `MARK_ABSENT` |
| differs from `base/` and from the override | override written | `WRITE` |

3. Objects that already match `HEAD` (in `base/` or the override) are dropped from the list, so changes others committed meanwhile aren't reverted.
4. If anything changed (and it isn't a dry run), it commits, pushes, and creates the database's tag if it didn't have one yet.

> **db-to-repo never changes `base/`.** After `init-repo`, changes from a database are always recorded as overrides for that database. To make a change canonical for all databases, edit the `base/` file in Git (for example, promote the override in a pull request).

### repo-to-db

For each requested database, in parallel:

1. **Commit check:** if `commitish` is not `HEAD` of the default branch, the run becomes a **dry run** unless `force: true`.
2. **Safety checks** (skipped with `force: true`):
   - The database must have a tag; otherwise it's skipped with `SKIPPED_NOT_ONBOARDED`.
   - The database must match the repository at its tag; otherwise it's skipped with `SKIPPED_OUT_OF_SYNC`.
3. **Changes to apply:** Git diff between the database's tag and `commitish`, over `base/` and the database's overrides.
   - A changed `base/` file applies only if the database has no override for it.
   - A changed override always applies.
   - A removed override applies the `base/` definition.
   - A database without a tag (only possible with `force`) gets everything.
4. **Data-loss check:** if the changes would drop a table or column and `allowDataLoss` is false, the database is skipped with `SKIPPED_DATA_LOSS`. This check also runs in dry runs.
5. **Apply:** unless it's a dry run, the changes are applied in one transaction (see [Applying Changes](#applying-changes)). The tag then moves to `commitish` and is pushed. If either step fails, the transaction rolls back.

## Object Extraction

| Type | Source |
|---|---|
| Procedures, functions, views, DML triggers | `sys.objects` + `sys.sql_modules`, with the module's `ANSI_NULLS` / `QUOTED_IDENTIFIER` settings prepended as `SET` statements |
| Tables | `sys.tables`, `sys.columns`, `sys.key_constraints`, `sys.foreign_keys`, `sys.check_constraints`, `sys.indexes` |
| Sequences | `sys.sequences` (start value, not current value) |
| Synonyms | `sys.synonyms` |
| Table types, scalar types | `sys.table_types`, `sys.types` |
| Full-text catalogs | `sys.fulltext_catalogs` |

Objects with `is_ms_shipped = 1` are excluded. CLR modules (no `sys.sql_modules` row) and database-level DDL triggers are not extracted.

**Constraint names:** system-generated names are replaced so definitions are the same across databases:

| Constraint | Generated name |
|---|---|
| Primary key | `PK_{table}` |
| Unique | `UQ_{table}_{columns}` |
| Foreign key | `FK_{table}_{referenced table}` |
| Check | `CK_{table}_{hash of the expression}` |

Column defaults are written inline (`DEFAULT (...)`) without a constraint name. User-chosen names are kept.

**Indexes:** indexes are written as `CREATE [UNIQUE] INDEX ... ON ... (columns) [WHERE filter]`. Indexes on `text`, `ntext`, `image`, `xml` or `(n)varchar(max)` / `varbinary(max)` columns are skipped with a warning.

## SQL Equivalence

Definitions are compared after normalization, so cosmetic differences don't count as changes:
- comments removed; case, whitespace and semicolons normalized
- `CREATE OR ALTER` treated as `CREATE`
- `[brackets]` around simple identifiers and the default-schema prefix removed
- parentheses around literals, operator spacing, default values and data type spellings normalized
- table columns and index statements sorted

## Applying Changes

**Order:** missing schemas are created first. Then creates and updates run in this order:
1. Full-text catalogs
2. Scalar types
3. Table types
4. Sequences (before tables whose defaults use them)
5. Tables, sorted by foreign keys so referenced tables come first
6. Synonyms
7. Functions
8. Views, sorted by references between the views being applied
9. Procedures
10. Triggers (on tables, or `INSTEAD OF` on views)

Drops run last, in the reverse order. Dependent objects go first: for example, a schema-bound view before its table, and a referencing table before the table it references (foreign keys for dropped tables are read from the database).

**How each type is applied:**

| Type | Change | Deletion |
|---|---|---|
| Table | `ALTER TABLE` statements generated from the old and new definitions; new tables are created | `DROP TABLE` (needs `allowDataLoss`) |
| Procedure, function, view, trigger | `CREATE OR ALTER`, which keeps the object's permissions; a function whose kind changes (scalar, inline or multi-statement) is dropped and recreated | `DROP` |
| Sequence | `ALTER SEQUENCE`, keeping the current value; `START WITH` restarts only an unused sequence; a data type change recreates it | `DROP` |
| Synonym, types, full-text catalog | `DROP` + `CREATE` | `DROP` |

**Table changes:**

| Change | Statement | Data |
|---|---|---|
| Add column | `ALTER TABLE ADD` | kept |
| Change type / nullability | `ALTER TABLE ALTER COLUMN` (dependent indexes dropped and recreated) | kept, or the statement fails if values don't convert |
| Remove column | `ALTER TABLE DROP COLUMN` (dependent constraints dropped first) | **lost**, needs `allowDataLoss` |
| Rename column | seen as remove + add | **lost**, needs `allowDataLoss` |
| All data columns replaced | table dropped and recreated | **lost**, needs `allowDataLoss` |
| Primary key change | constraint dropped and re-added; foreign keys referencing it are dropped and **not** re-created | kept |
| New check constraint | `ALTER TABLE ADD` | kept |
| Indexes | all indexes in the definition are dropped (if present) and recreated | kept |
| Identity change | not applied; a warning is logged | — |

**Not applied to existing tables:**
- added or removed foreign keys and unique constraints
- removed check constraints
- removed indexes
- changed column defaults

Renames are not detected. Do them by hand, then run db-to-repo.

**GO batches:** scripts are split on lines that contain only `GO` (case-insensitive), and each batch is sent separately. `GO n` and `GO` inside comments or strings are not recognized.

## Tagging

Each database has one Git tag, `{prefix-path}/{db}` or just `{db}` without a prefix, in lower case (for example `prod/orders`). The tag marks the commit the database matches.

- Created by `init-repo`, or by the database's first db-to-repo.
- Moved to `commitish` after every successful repo-to-db, including when there was nothing to apply.
- Pushed to the remote with force (the tag moves).

To see which commit a database is on, run `git log -1 prod/orders`. Tags are not versioned per sync. Git history of the repository is the audit trail.

**Upgrading from unprefixed tags:** tags written by version 0.1.0 and earlier have no prefix. With a prefix set, a database whose prefixed tag doesn't exist yet keeps using its old `{db}` tag. Its next successful sync writes `{prefix-path}/{db}`. The old tag is left alone; delete it once every deployment has synced.

A tag can't share its name with another tag's namespace: a deployment without a prefix can't have a database named like another deployment's prefix.

## Concurrency and Failure Handling

- **One operation at a time:** sync requests are serialized. A second request waits for the first to finish.
- **Parallel databases:** within a repo-to-db request, databases are processed in parallel, one virtual thread each. Git writes (tag pushes) are serialized.
- **Per-database failures:** a database that fails is reported as `FAILED`, and the others continue.
- **Transactions:**
  - Each database's changes run in one transaction.
  - If a statement fails, or the tag can't be pushed, the transaction rolls back and the tag isn't moved.
  - Statements that can't run inside a transaction will fail.
- **Rejected pushes:**
  - A push rejected by the remote fails the request, and the local commit is discarded.
  - Run the request again; it fetches first.
- **Several Adous instances on one repository:** they don't corrupt it, because rejected pushes are detected. Each should use its own `prefix-path`, so their overrides and tags don't overlap.

## Known Limitations

- Indexes are written without `CLUSTERED` / `COLUMNSTORE` / XML / spatial keywords, so those index types are recreated as regular indexes.
- Table changes are partial: see [Table changes](#applying-changes). In particular, changing a primary key drops the foreign keys that reference it.
- Computed columns, full-text indexes, partitioning, indexed views, CLR objects and database-level objects (users, roles, permissions) are not managed. See [MSSQL_FEATURES_SUPPORT.md](MSSQL_FEATURES_SUPPORT.md).

## See Also

- [Usage Guide](usage.md) - API reference and examples
- [Supported SQL Server features](MSSQL_FEATURES_SUPPORT.md)
- [README](../README.md) - Project overview
