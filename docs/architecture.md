# Adous Architecture

This document explains how Adous works internally, including object discovery, DDL normalization, dependency resolution, and synchronization strategies.

## Table of Contents

- [Overview](#overview)
- [Core Components](#core-components)
- [Object Discovery](#object-discovery)
- [DDL Normalization](#ddl-normalization)
- [Dependency Resolution](#dependency-resolution)
- [Synchronization Strategies](#synchronization-strategies)
- [GO Batch Handling](#go-batch-handling)
- [Table Evolution Strategy](#table-evolution-strategy)
- [Index Filtering](#index-filtering)
- [Tagging Strategy](#tagging-strategy)
- [Concurrency Model](#concurrency-model)

## Overview

Adous is a bidirectional synchronization tool that maintains database schemas as code in Git. It extracts database objects into normalized DDL files and applies changes back to databases with dependency-aware ordering.

```
┌─────────────┐         ┌─────────────┐         ┌─────────────┐
│  Database   │ ◄─────► │    Adous    │ ◄─────► │     Git     │
│  (MSSQL)    │         │  Sync Engine│         │  Repository │
└─────────────┘         └─────────────┘         └─────────────┘
     │                         │                        │
     │  Extract Objects        │  Commit Changes        │
     │  Apply Changes          │  Track History         │
     │  Detect Drifts          │  Version Objects       │
     └─────────────────────────┴────────────────────────┘
```

## Core Components

### DatabaseRepositorySynchronizerService
Orchestrates synchronization between databases and Git repository.

**Responsibilities:**
- Coordinate DB→Repo and Repo→DB flows
- Manage Git operations (commit, tag, push)
- Handle dry-run mode
- Detect and report out-of-sync states

### MSSQLDatabaseService
SQL Server-specific implementation for object discovery and manipulation.

**Responsibilities:**
- Query system catalogs for object metadata
- Generate DDL for each object type
- Execute DDL with proper batch handling
- Validate object dependencies

### GitService
High-level Git operations abstraction using JGit.

**Responsibilities:**
- Clone/init repositories
- Commit and tag changes
- Push to remote
- Resolve commit references (tags, branches, SHAs)

### SqlEquivalenceCheckerService
Smart SQL statement comparison that ignores formatting differences.

**Responsibilities:**
- Normalize SQL for comparison (whitespace, comments, case)
- Detect actual schema changes vs. cosmetic differences
- Prevent unnecessary commits

### TableAlterScriptGenerator
Generates ALTER TABLE scripts to preserve data during schema evolution.

**Responsibilities:**
- Compare old and new table definitions
- Generate ADD COLUMN, DROP COLUMN, MODIFY COLUMN statements
- Handle constraints and indexes
- Preserve existing data

## Object Discovery

Adous discovers and manages the following SQL Server object types:

### Discovery Order (DB → Repo)

1. **Types** (User-Defined Types)
   - Queried from `sys.types` and `sys.assemblies`
   - Must be created before tables that use them

2. **Sequences**
   - Queried from `sys.sequences`
   - Independent of other objects

3. **Synonyms**
   - Queried from `sys.synonyms`
   - Pointers to other objects

4. **Tables**
   - Queried from `sys.tables`, `sys.columns`, `sys.indexes`, `sys.foreign_keys`
   - Includes columns, constraints, indexes
   - Foreign key dependencies tracked for ordering

5. **Functions** (Scalar, Table-Valued, Inline)
   - Queried from `sys.objects` with `type IN ('FN', 'IF', 'TF')`
   - May depend on tables and types

6. **Procedures** (Stored Procedures)
   - Queried from `sys.procedures`
   - May depend on tables, views, functions

7. **Views**
   - Queried from `sys.views` and `sys.sql_modules`
   - May depend on tables, other views, functions
   - Dependency graph parsed and topologically sorted

8. **Triggers** (Table Triggers)
   - Queried from `sys.triggers`
   - Attached to specific tables

9. **Full-Text Catalogs**
   - Queried from `sys.fulltext_catalogs`
   - Search indexing infrastructure

### Discovery Implementation

```sql
-- Example: Discovering stored procedures
SELECT 
    s.name AS SchemaName,
    p.name AS ProcedureName,
    m.definition AS Definition
FROM sys.procedures p
INNER JOIN sys.schemas s ON p.schema_id = s.schema_id
INNER JOIN sys.sql_modules m ON p.object_id = m.object_id
WHERE p.is_ms_shipped = 0
ORDER BY s.name, p.name;
```

## DDL Normalization

All extracted DDL is normalized to ensure consistency and determinism.

### SET Options

Every script includes standard SET options:

```sql
SET ANSI_NULLS ON
GO
SET QUOTED_IDENTIFIER ON
GO
```

These options ensure consistent behavior across environments.

### Constraint Naming

Constraints are normalized with deterministic names:

- **Primary Keys:** `PK_{TableName}`
- **Unique Constraints:** `UQ_{TableName}_{ColumnName}`
- **Check Constraints:** `CK_{TableName}_{ColumnName}`
- **Default Constraints:** `DF_{TableName}_{ColumnName}`
- **Foreign Keys:** `FK_{TableName}_{ReferencedTable}`

Auto-generated system names (e.g., `PK__Person__AA2FFB8D...`) are replaced with readable names.

### Whitespace and Formatting

- Consistent indentation (tabs or spaces configurable)
- Comments preserved
- Line endings normalized to LF
- Trailing whitespace removed

### GO Batch Separators

- GO statements are preserved exactly as they appear in SQL modules
- Each GO separates independent batches
- Critical for objects with schema-binding or context changes

## Dependency Resolution

Adous applies changes in dependency-aware order to prevent failures.

### Table Dependencies (Foreign Keys)

Tables are sorted using **topological sort** based on foreign key relationships:

1. Build dependency graph: `Table A → Table B` if A references B
2. Detect cycles (not typical in FK graphs)
3. Sort tables so referenced tables are created first

**Example:**
```
Person → Department (FK: DepartmentID)
Order → Person (FK: PersonID)
OrderItem → Order (FK: OrderID)

Creation order: Department, Person, Order, OrderItem
```

### View Dependencies

Views can depend on:
- Tables
- Other views
- Functions

Adous parses view definitions to extract dependencies:

```sql
-- Example view
CREATE VIEW EmployeeSummary AS
SELECT e.EmployeeID, d.DepartmentName
FROM Employee e
INNER JOIN Department d ON e.DepartmentID = d.DepartmentID;
```

Dependency extraction:
- Parse SQL AST or use regex heuristics
- Identify referenced objects (Employee, Department)
- Build dependency graph
- Topologically sort views

**View application order:**
1. Create base tables (Employee, Department)
2. Create functions (if any referenced)
3. Create views in dependency order

### Application Order (Repo → DB)

1. **Types** - User-defined types
2. **Sequences** - Independent generators
3. **Synonyms** - Object pointers
4. **Tables** - In FK dependency order
5. **Functions** - May be used by views/procedures
6. **Procedures** - May reference functions and tables
7. **Views** - In dependency order (topologically sorted)
8. **Triggers** - Attached to tables

## Synchronization Strategies

### DB → Repository

1. **Discovery:** Query system catalogs for all objects
2. **Extraction:** Generate normalized DDL for each object
3. **Comparison:** Use SqlEquivalenceChecker to detect changes
4. **Categorization:** Added, Modified, Deleted
5. **Write Files:** Create/update/delete SQL files in base/ or diff/
6. **Commit:** Create Git commit with descriptive message
7. **Tag:** Tag commit with database name for tracking
8. **Push:** Push to remote (if configured)

### Repository → DB

1. **Fetch Commit:** Resolve commitish (branch, tag, SHA)
2. **Read Files:** Load SQL files from base/ and diff/
3. **Merge Overrides:** Apply diff/ overrides on top of base/
4. **Dependency Sort:** Order objects by dependencies
5. **Dry Run (optional):** Preview changes without applying
6. **Execute:** Run DDL scripts with GO batch handling
7. **Verify:** Check for errors
8. **Tag:** Tag commit with database name

### Conflict Resolution

- **base/ vs. diff/:** diff/ always wins (database-specific override)
- **Concurrent changes:** Not supported; use Git branch protection
- **Schema drift:** Detected via tagging; force flag required to override

## GO Batch Handling

SQL Server requires certain DDL statements to be in separate batches.

### GO Separator Semantics

`GO` is not a T-SQL statement; it's a batch terminator recognized by client tools.

**Example:**
```sql
CREATE TABLE Person (ID INT);
GO
CREATE VIEW PersonView AS SELECT * FROM Person;
GO
```

Each batch is submitted separately to SQL Server.

### Adous GO Processing

1. **Parse DDL:** Split script by GO statements
2. **Execute Batches:** Submit each batch sequentially via JDBC
3. **Error Handling:** If a batch fails, stop and report error
4. **Preserve GO:** When extracting DDL, preserve GO statements as-is

### Edge Cases

- GO with count: `GO 5` (repeat batch 5 times) - not supported, treated as single GO
- GO in comments or strings: Ignored during parsing
- Missing GO between incompatible statements: SQL Server will error; Adous surfaces error

## Table Evolution Strategy

Adous uses **ALTER TABLE** when possible to preserve data.

### Table Change Detection

Compare old and new table definitions:

| Change Type     | Action                      | Data Preserved |
|-----------------|-----------------------------|----------------|
| Add Column      | ALTER TABLE ADD             | ✅ Yes          |
| Drop Column     | ALTER TABLE DROP            | ⚠️ Column lost |
| Modify Column   | ALTER TABLE ALTER COLUMN    | ✅ Yes*         |
| Add Constraint  | ALTER TABLE ADD CONSTRAINT  | ✅ Yes          |
| Drop Constraint | ALTER TABLE DROP CONSTRAINT | ✅ Yes          |
| Add Index       | CREATE INDEX                | ✅ Yes          |
| Drop Index      | DROP INDEX                  | ✅ Yes          |
| Rename Column   | sp_rename                   | ✅ Yes          |
| Table Rename    | sp_rename                   | ✅ Yes          |

*May fail if data incompatible with new type (e.g., VARCHAR to INT with non-numeric data).

### DROP and CREATE Fallback

If ALTER fails or change is too complex (e.g., changing column order):
1. Generate DROP TABLE (with warning)
2. Generate CREATE TABLE
3. Require explicit confirmation (force flag)

### Data Migration Scripts

For complex schema changes, Adous cannot auto-generate data migration. Users should:
1. Export data
2. Apply schema change
3. Re-import data with transformation

## Index Filtering

Not all indexes can be recreated from metadata.

### Valid Index Types

- **Clustered Index:** Supported
- **Non-Clustered Index:** Supported
- **Unique Index:** Supported
- **Filtered Index:** Supported (WHERE clause preserved)
- **Columnstore Index:** Supported

### Invalid Index Scenarios

Indexes are **excluded** if:

1. **Column type not indexable:**
   - `VARCHAR(MAX)`
   - `NVARCHAR(MAX)`
   - `VARBINARY(MAX)`
   - `TEXT`, `NTEXT`, `IMAGE` (deprecated types)
   - `XML`
   - `GEOGRAPHY`, `GEOMETRY`

2. **Computed column index:** Requires persisted computed column

3. **Index on view:** Handled separately with indexed view DDL

4. **Full-text index:** Managed via full-text catalog

### Index Extraction Logic

```sql
-- Pseudo-code
FOR EACH index IN sys.indexes:
    FOR EACH column IN index_columns:
        IF column.type IN (excluded_types):
            SKIP index
    IF index is valid:
        GENERATE CREATE INDEX statement
```

## Tagging Strategy

Git tags track which commit each database is synced to.

### Tag Format

```
{prefix-path}/{databaseName}
```

**Example:** `cdt-env/production-db`

### Tag Usage

- **Sync Detection:** Check if database tag matches repo HEAD
- **Out-of-Sync Warning:** If tags differ, warn user before applying
- **Force Sync:** Override tag check with `force: true`
- **History Tracking:** View sync history via Git tag log

### Tag Operations

**Create Tag (after sync):**
```java
git.tag()
   .setName(prefixPath + "/" + dbName)
   .setMessage("Synced " + dbName)
   .call();
```

## Concurrency Model

Adous uses **Java Virtual Threads** (Project Loom) for parallel processing.

### Parallel Operations

- **Multi-Database Sync:** Each database synced in parallel virtual thread
- **Object Discovery:** Queries for different object types run concurrently
- **File I/O:** Read/write operations parallelized

### Synchronization Guarantees

- **Git Operations:** Serialized per repository (JGit not thread-safe)
- **Database Transactions:** Each DDL batch in separate transaction
- **File System:** Concurrent writes to different files; atomic rename for same file

### Error Handling

- **Partial Failures:** One database failure doesn't stop others
- **Rollback:** DDL changes not transactional (SQL Server DDL auto-commits)
- **Reporting:** Collect all errors and return aggregate result

## Performance Considerations

### Optimization Strategies

1. **Lazy Discovery:** Only query needed object types
2. **Incremental Sync:** Only process changed objects
3. **Batch Execution:** Group small DDL statements
4. **Connection Pooling:** Reuse database connections
5. **Git Pack Files:** Efficient storage for many small files

### Scalability Limits

- **Large Schemas:** 10,000+ objects may take several minutes
- **Large Views:** Complex views with many dependencies slow parsing
- **Network Latency:** Remote Git push time depends on repo size
- **Concurrent Users:** Single Adous instance recommended per repo

## Future Enhancements

- **PostgreSQL Support:** Extend beyond SQL Server
- **Schema Validation:** Pre-flight checks before applying
- **Rollback Scripts:** Generate undo DDL
- **Change Approvals:** Integrate with PR workflows
- **Performance Metrics:** Track sync duration and object counts
- **Differential Backup:** Incremental Git storage optimization

## See Also

- [Usage Guide](usage.md) - API reference and examples
- [Examples](../examples/workflow.md) - Step-by-step tutorial
- [README](../README.md) - Project overview

