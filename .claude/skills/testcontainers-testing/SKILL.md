---
name: testcontainers-testing
description: Integration testing patterns for Adous using Testcontainers with SQL Server
---

# Testcontainers Testing Patterns

## Container Setup
```java
@Container
static MSSQLServerContainer<?> sqlServer = new MSSQLServerContainer<>(
    DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
    .acceptLicense();
```

Always declare containers as `static` to share across test methods in a class.
Use `@DynamicPropertySource` to inject JDBC URL into Spring context.

## Test Categories

- **Unit tests**: No container needed. Mock GitService and MSSQLDatabaseService.
- **Integration tests**: Use Testcontainers. Test actual SQL execution and Git operations end-to-end.
- **Equivalence tests**: Test SqlEquivalenceCheckerService with table-driven test cases covering whitespace, comments, case variations.

## Table-Driven Tests (preferred pattern)
```java
@ParameterizedTest
@MethodSource("equivalenceTestCases")
void shouldDetectEquivalentSQL(String sql1, String sql2, boolean expected) { ... }
```

## DryRun Testing

Every sync operation test MUST have a dryRun=true variant that verifies:
1. No DB changes were made
2. The response correctly describes what WOULD have changed

## Naming

- Integration test classes: `*IT` suffix (e.g., `SyncOperationIT`)
- Unit test classes: `*Test` suffix
- Test methods: `should{Action}When{Condition}` pattern