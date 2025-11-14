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

### Quick Start Workflow

1. **Initialize Repository** (first time setup):
   ```bash
   curl -X POST http://localhost:8080/api/synchronizer/init-repo/myDatabase
   ```
   This creates the initial commit with all database objects.

2. **Make changes to your database** (create/modify stored procedures, functions, views, triggers)

3. **Sync database to repository**:
   ```bash
   curl -X POST "http://localhost:8080/api/synchronizer/db-to-repo/myDatabase?dryRun=false"
   ```

4. **Sync repository to other databases**:
   ```bash
   curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
     -H "Content-Type: application/json" \
     -d '{"commitish": "main", "dbs": ["testDatabase", "stagingDatabase"], "dryRun": false, "force": false}'
   ```

## API Endpoints

### Initialize Repository with Database

Initializes an empty Git repository with all database objects from a specified database. This is typically the first operation when setting up Adous:

```bash
POST /api/synchronizer/init-repo/{dbName}
```

**Parameters:**
- `dbName` (path): Name of the database to initialize the repository from

**Example:**
```bash
curl -X POST http://localhost:8080/api/synchronizer/init-repo/myDatabase
```

**Response:**
```json
{
  "dbName": "myDatabase",
  "dryRun": false,
  "result": "Repository initialized with 25 objects",
  "message": "Repository initialized successfully"
}
```

**Note:** This operation can only be performed on an empty repository. Use this when setting up a new Git repository for the first time.

### Sync Database to Repository

Synchronizes database objects to the Git repository:

```bash
POST /api/synchronizer/db-to-repo/{dbName}?dryRun=false
```

**Parameters:**
- `dbName` (path): Name of the database to sync
- `dryRun` (query, optional): If true, previews changes without committing (default: false)

**Example:**
```bash
curl -X POST "http://localhost:8080/api/synchronizer/db-to-repo/myDatabase?dryRun=false"
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

**Request Body:**
- `commitish`: Git commit, branch, or tag reference to sync from
- `dbs`: List of database names to sync
- `dryRun`: Preview changes without applying (default: false)
- `force`: Force sync even if database is out of sync (default: false)

**Example:**
```bash
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -d '{
    "commitish": "main",
    "dbs": ["database1", "database2"],
    "dryRun": false,
    "force": false
  }'
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
