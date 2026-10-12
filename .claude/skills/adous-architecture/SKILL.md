---
name: adous-architecture
description: Adous project architecture, layering rules, and coding conventions for the DB-as-Code synchronizer
---

# Adous Architecture Guide

## Layer Responsibilities

- **Controller layer** (`SynchronizerController`): REST API only. No business logic. Delegates to service layer.
- **Service layer** (`DatabaseRepositorySynchronizerService`): Orchestrates sync operations. Coordinates between GitService and MSSQLDatabaseService.
- **Infrastructure layer** (`GitService`, `MSSQLDatabaseService`): External system access only. No business logic.
- **Utility** (`SqlEquivalenceCheckerService`): Stateless, pure functions. No I/O.

## Naming Conventions

- Services: `*Service` suffix
- Controllers: `*Controller` suffix  
- DTOs: `*Request`, `*Response` suffix
- Sync operations return structured result objects, never raw strings

## Concurrency

- repo→db processes databases in parallel, one virtual thread each
- Sync operations are serialized by a lock in `DatabaseRepositorySynchronizerService`; Git writes by a lock in `GitService`
- Never block virtual threads with synchronized blocks — use ReentrantLock or structured concurrency
- Testcontainers tests run single-threaded; don't parallelize test setup

## Dependency Direction

Controller → Service → (GitService | MSSQLDatabaseService)
No reverse dependencies. No circular references.

## Error Handling

- Wrap external system failures (Git, SQL Server) in domain-specific exceptions
- Never expose raw JDBC/JGit exceptions to the controller layer
- Always support dryRun mode: detect and report changes without applying them

## Repository Structure (Git side)
```
base/{objectType}/{schema}/{objectName}.sql   ← canonical version
diff/{prefix}/{dbName}/{objectType}/{schema}/{objectName}.sql  ← db-specific overrides
```

When adding new object type support, always handle both base/ and diff/ paths.