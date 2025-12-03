# Adous

[![Build & Test](https://github.com/OWNER/Adous/actions/workflows/build-test.yaml/badge.svg)](https://github.com/OWNER/Adous/actions/workflows/build-test.yaml)
[![Docker Image](https://ghcr-badge.deta.dev/owner/adous/latest_tag?label=Docker&color=blue)](https://github.com/OWNER/Adous/pkgs/container/adous)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

> **Database as Code for SQL Server** – Synchronize SQL Server schemas with Git through bidirectional, dependency-aware synchronization. Export canonical DDL to version control and apply changes deterministically with intelligent ordering and safe table evolution.

**Adous** is a Spring Boot service for managing and synchronizing multiple database instances with a Git repository. It enables version control for database objects (stored procedures, functions, views, triggers, tables, synonyms, sequences, user-defined types) and provides bidirectional synchronization between databases and Git.

## 📚 Documentation

- **[Quick Start Guide](examples/workflow.md)** - Step-by-step tutorial
- **[Architecture](docs/architecture.md)** - How Adous works internally
- **[Usage Guide](docs/usage.md)** - API reference and examples
- **[Contributing](docs/CONTRIBUTING.md)** - How to contribute
- **[Examples](examples/)** - Sample configurations and schemas

## Features

- 🔄 **Bidirectional Sync**: Sync database objects to Git and vice versa
- 🗄️ **Multi-Database Support**: Manage multiple SQL Server databases simultaneously
- 🌿 **Git Integration**: Full Git integration with commit, tag, and branch support
- 🔍 **SQL Equivalence Checking**: Smart comparison of SQL statements
- 🚀 **Parallel Processing**: Efficient synchronization using virtual threads
- 📝 **OpenAPI Documentation**: Integrated Swagger UI for API exploration
- 🔒 **Security**: Configurable authentication (API Key)
- 🎯 **Dry Run Mode**: Preview changes before applying them
- 📊 **Monitoring**: Built-in metrics and health checks with Prometheus support
- 🚫 **Sync Ignore Patterns**: Exclude specific database objects using .syncignore file
- 🐳 **Docker Support**: Production-ready Docker image with multi-stage build

## Architecture

### Core Components

- **SynchronizerController**: REST API endpoints for synchronization operations
- **DatabaseRepositorySynchronizerService**: Orchestrates sync operations between DB and Git
- **GitService**: High-level Git operations abstraction
- **MSSQLDatabaseService**: SQL Server-specific database operations
- **SqlEquivalenceCheckerService**: Compares SQL statements for equivalence

### Key Technologies

- **Spring Boot 3.5.7**: Modern Spring Boot framework
- **Java 25**: Latest Java features including virtual threads
- **JGit 7.3.0**: Git operations in Java
- **SQL Server**: Primary database support
- **SpringDoc OpenAPI 2.7.0**: API documentation and Swagger UI
- **Micrometer**: Metrics and monitoring with Prometheus support
- **Testcontainers**: Integration testing with real database instances

## Getting Started

### Configuration

Configure your databases and Git repository in `application.yml`:

```yaml
spring:
  application:
    name: Adous
    authentication:
      type: APIKEY  # Authentication type: APIKEY
      api-key: your-api-key  # For APIKEY authentication
  datasources:
    dbs:
      myDatabase:
        url: jdbc:sqlserver://localhost:1433;databaseName=MyDB
        username: sa
        password: yourPassword
        driver-class-name: com.microsoft.sqlserver.jdbc.SQLServerDriver

# Git repository configuration
github:
  remote-uri: https://github.com/your-org/your-repo.git  # Can be set via GIT_REMOTE_URI env var
  token: your-github-token  # Can be set via GIT_TOKEN env var
  base-root-path: base
  diff-root-path: diff
  prefix-path: ""  # Optional prefix for Git paths (GIT_PREFIX_PATH)
  commit-username: "Adous System"  # Git commit author (GIT_COMMIT_USERNAME)
  commit-email: "adous@mail.com"  # Git commit email (GIT_COMMIT_EMAIL)
  default-branch: main  # Default branch name (GIT_DEFAULT_BRANCH)
  sync-ignore-file: ""  # Path to custom .syncignore file (GIT_SYNC_IGNORE_FILE)

# Actuator endpoints for monitoring
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

#### Sync Ignore Configuration

Create a `.syncignore` file in `src/main/resources/` to exclude specific database objects from synchronization:

```
# Ignore all stored procedures starting with 'temp_'
**/PROCEDURE/*/temp_*.sql

# Ignore specific view
**/VIEW/dbo/internal_view.sql

# Ignore all objects in a specific schema
**/FUNCTION/internal/*
```

### Authentication

1. **APIKEY**: API Key authentication
   ```bash
   export AUTH_TYPE=APIKEY
   export API_KEY=your-api-key
   ```
   Use in `X-API-Key` header: `X-API-Key: your-api-key`

### Building

```bash
./gradlew build
```

### Running

```bash
./gradlew bootRun
```

The application will start on `http://localhost:8080`

### Running with Docker

Build the Docker image:

```bash
docker build -t adous:latest .
```

Run the container:

```bash
docker run -d \
  -p 8080:8080 \
  -e GIT_REMOTE_URI=https://github.com/your-org/your-repo.git \
  -e GIT_TOKEN=your-github-token \
  -e GIT_PREFIX_PATH="" \
  -e GIT_COMMIT_USERNAME="Adous System" \
  -e GIT_COMMIT_EMAIL="adous@mail.com" \
  -e GIT_DEFAULT_BRANCH=main \
  --name adous \
  adous:latest
```

The Docker image includes:
- Multi-stage build for minimal image size
- Non-root user for security
- Health checks via actuator endpoint
- Optimized JVM settings for container environments

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
  "result": "[list of all objects added]",
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
  {prefix-path}/
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

## Monitoring and Health Checks

Adous includes built-in monitoring capabilities via Spring Boot Actuator:

### Available Endpoints

- **Health**: `http://localhost:8080/actuator/health` - Application health status
- **Info**: `http://localhost:8080/actuator/info` - Application information
- **Metrics**: `http://localhost:8080/actuator/metrics` - Application metrics
- **Prometheus**: `http://localhost:8080/actuator/prometheus` - Prometheus-compatible metrics

### Metrics

The application exports metrics including:
- HTTP request/response metrics
- JVM memory and GC metrics
- Database connection pool metrics
- Custom synchronization operation metrics

### Docker Health Check

When running in Docker, the container includes a health check that polls the actuator health endpoint every 30 seconds.

## Versioning & Releases

Adous follows [Semantic Versioning](https://semver.org/) (SemVer):

- **MAJOR.MINOR.PATCH** (e.g., `1.2.3`)
  - **MAJOR**: Breaking changes
  - **MINOR**: New features (backward-compatible)
  - **PATCH**: Bug fixes (backward-compatible)

### Release Tags

Git tags mark stable releases:

```bash
# List all releases
git tag --list "v*"

# Check out a specific version
git checkout v1.0.0
```

### Docker Images

Docker images are published to GitHub Container Registry with multiple tags:

- `ghcr.io/owner/adous:latest` - Latest build from main branch
- `ghcr.io/owner/adous:0.0.1-SNAPSHOT` - Specific version from build.gradle
- `ghcr.io/owner/adous:abc123def456` - Specific commit SHA (short)
- `ghcr.io/owner/adous:v1.0.0` - Specific release tag

**Use specific version tags in production** for reproducibility.

### Database Sync Tagging

Each database sync creates a Git tag:

```
{databaseName}/{timestamp}
```

Example: `production-db/20231205-143022`

This tracks which commit each database is synced to, enabling:
- Out-of-sync detection
- Rollback capability
- Audit trail

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

## CI/CD

The project includes GitHub Actions workflow for continuous integration:

### Build and Test Workflow

Located in `.github/workflows/build-test.yaml`, this workflow:
- Runs on pull requests and pushes to main branch
- Sets up Java 25 with Temurin distribution
- Builds the application with Gradle
- Executes all tests including integration tests

The workflow ensures code quality and test coverage on every change.

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](docs/CONTRIBUTING.md) for detailed guidelines.

**Quick Start for Contributors:**

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes following our coding standards
4. Add tests for new functionality
5. Run tests: `./gradlew test`
6. Commit with descriptive messages (Conventional Commits format)
7. Push and create a Pull Request

**Before contributing:**
- Read the [Code of Conduct](docs/CODE_OF_CONDUCT.md)
- Check existing issues and PRs
- Use issue templates for bugs and feature requests
- Follow the PR template checklist

## Community

- **Issues**: [Report bugs](https://github.com/OWNER/Adous/issues/new?template=bug_report.md) or [request features](https://github.com/OWNER/Adous/issues/new?template=feature_request.md)
- **Discussions**: Ask questions and share ideas
- **Documentation**: Help improve docs in [docs/](docs/) directory

## License

See [LICENSE](LICENSE) file for details.
