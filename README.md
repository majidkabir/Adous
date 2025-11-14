# Adous

**Adous** is a Spring Boot service for managing and synchronizing multiple database instances with a Git repository. It enables version control for database objects (stored procedures, functions, views, triggers) and provides bidirectional synchronization between databases and Git.

## Features

- 🔄 **Bidirectional Sync**: Sync database objects to Git and vice versa
- 🗄️ **Multi-Database Support**: Manage multiple SQL Server databases simultaneously
- 🌿 **Git Integration**: Full Git integration with commit, tag, and branch support
- 🔍 **SQL Equivalence Checking**: Smart comparison of SQL statements
- 🚀 **Parallel Processing**: Efficient synchronization using virtual threads
- 📝 **OpenAPI Documentation**: Integrated Swagger UI for API exploration
- 🔒 **Security**: Token-based authentication
- 🎯 **Dry Run Mode**: Preview changes before applying them

## Architecture

### Core Components

- **SynchronizerController**: REST API endpoints for synchronization operations
- **DatabaseRepositorySynchronizerService**: Orchestrates sync operations between DB and Git
- **GitService**: High-level Git operations abstraction
- **MSSQLDatabaseService**: SQL Server-specific database operations
- **SqlEquivalenceCheckerService**: Compares SQL statements for equivalence

### Key Technologies

- **Spring Boot 3.5.6**: Modern Spring Boot framework
- **Java 25**: Latest Java features including virtual threads
- **JGit**: Git operations in Java
- **SQL Server**: Primary database support
- **SpringDoc OpenAPI**: API documentation

## Getting Started

### Prerequisites

- Java 25 or higher
- Gradle
- SQL Server database(s)
- Git repository (local or remote)

### Configuration

Configure your databases and Git repository in `application.yml`:

```yaml
spring:
  datasources:
    dbs:
      myDatabase:
        url: jdbc:sqlserver://localhost:1433;databaseName=MyDB
        username: sa
        password: yourPassword
        driver-class-name: com.microsoft.sqlserver.jdbc.SQLServerDriver

github:
  remote-uri: https://github.com/your-org/your-repo.git
  token: your-github-token
  base-root-path: base
  diff-root-path: diff
```

### Building

```bash
./gradlew build
```

### Running

```bash
./gradlew bootRun
```

The application will start on `http://localhost:8080`

### API Documentation

Access Swagger UI at: `http://localhost:8080/swagger-ui.html`

## API Endpoints

### Sync Database to Repository

Synchronizes database objects to the Git repository:

```bash
POST /api/synchronizer/db-to-repo/{dbName}?dryRun=false
```

### Sync Repository to Databases

Synchronizes Git repository commit to one or more databases:

```bash
POST /api/synchronizer/repo-to-db
Content-Type: application/json

{
  "commitish": "main",
  "dbs": ["database1", "database2"],
  "dryRun": false,
  "force": false
}
```

## How It Works

### Repository Structure

Database objects are stored in the following structure:

```
base/
  {objectType}/
    {schema}/
      {objectName}.sql

diff/
  {databaseName}/
    {objectType}/
      {schema}/
        {objectName}.sql
```

- **base/**: Contains the canonical version of database objects
- **diff/**: Contains database-specific overrides

### Synchronization Flow

1. **DB → Repo**: Detects changes in database, creates/updates files in Git, commits and pushes
2. **Repo → DB**: Reads Git commit, applies changes to database(s), tags commit with database name

### Tagging Strategy

Each database is tagged in Git when synchronized, allowing tracking of which commit each database is synced to.

## Best Practices

1. **Always use dry-run first**: Preview changes before applying
2. **Regular syncs**: Keep databases and repository in sync
3. **Use force carefully**: Only when you understand the implications
4. **Review out-of-sync warnings**: They prevent data loss
5. **Leverage .syncignore**: Exclude objects you don't want to sync

## Testing

Run tests with:

```bash
./gradlew test
```

Integration tests use Testcontainers for SQL Server.

## Contributing

1. Follow Java and Spring Boot best practices
2. Add appropriate logging
3. Write unit and integration tests
4. Update documentation

## License

See LICENSE file for details.
