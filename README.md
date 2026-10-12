# Adous

[![Build & Test](https://github.com/majidkabir/Adous/actions/workflows/build-test.yaml/badge.svg)](https://github.com/majidkabir/Adous/actions/workflows/build-test.yaml)
[![Docker Image Version](https://ghcr-badge.egpl.dev/majidkabir/adous/latest_tag?label=Docker&color=blue)](https://ghcr.io/majidkabir/adous)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

> **Database as Code for SQL Server** – Synchronize SQL Server schemas with Git through bidirectional, dependency-aware synchronization. Export canonical DDL to version control and apply changes deterministically with intelligent ordering and safe table evolution.

**Adous** is a Spring Boot service for managing and synchronizing multiple database instances with a Git repository. It enables version control for database objects (stored procedures, functions, views, triggers, tables, synonyms, sequences, user-defined types) and provides bidirectional synchronization between databases and Git.

## 📚 Documentation

- **[Getting Started](#getting-started)** - Configure and run Adous
- **[Architecture](docs/architecture.md)** - How Adous works internally
- **[Usage Guide](docs/usage.md)** - API reference and examples
- **[Contributing](docs/CONTRIBUTING.md)** - How to contribute

## Features

- 🔄 **Bidirectional Sync**: Sync database objects to Git and vice versa
- 🗄️ **Multi-Database Support**: Manage multiple SQL Server databases simultaneously
- 🌿 **Git Integration**: Every change is a Git commit; a tag per database records the commit it matches
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
  prefix-path: ""  # Optional namespace for this deployment's overrides and tags (GIT_PREFIX_PATH)
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

Exclude objects from synchronization with a `.syncignore` file. Point `GIT_SYNC_IGNORE_FILE` at it (works for the jar and Docker), or put it in `src/main/resources/` before building:

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

   `API_KEY` is required: the service refuses to start without it. Use a long random value, e.g. `openssl rand -hex 32`.

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
  -e API_KEY="$(openssl rand -hex 32)" \
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

All API calls need the `X-API-Key` header.

1. **Initialize the repository** (first time):
   ```bash
   curl -X POST http://localhost:8080/api/synchronizer/init-repo/myDatabase \
     -H "X-API-Key: $API_KEY"
   ```
   This commits every object to `base/` and tags the commit with the database name.

2. **Make changes to your database**, or edit `base/` files in Git.

3. **Record database changes in Git**:
   ```bash
   curl -X POST "http://localhost:8080/api/synchronizer/db-to-repo/myDatabase?dryRun=false" \
     -H "X-API-Key: $API_KEY"
   ```
   Changes are recorded as overrides for that database under `diff/`. To roll a change out to every database, update the `base/` file in Git.

4. **Apply the repository to databases**:
   ```bash
   curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
     -H "Content-Type: application/json" \
     -H "X-API-Key: $API_KEY" \
     -d '{"commitish": "main", "dbs": ["testDatabase", "stagingDatabase"], "dryRun": true}'
   ```
   Check the dry-run result, then repeat with `"dryRun": false`. Each database must have been onboarded (step 1, or one db-to-repo) and match its last synced commit.

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
curl -X POST http://localhost:8080/api/synchronizer/init-repo/myDatabase \
  -H "X-API-Key: $API_KEY"
```

**Response:**
```json
{
  "dbName": "myDatabase",
  "dryRun": false,
  "message": "Repository initialized successfully",
  "changes": [
    { "path": "base/TABLE/dbo/users.sql", "type": "TABLE", "schema": "dbo", "name": "users", "action": "WRITE" },
    { "path": "base/PROCEDURE/dbo/usp_get_users.sql", "type": "PROCEDURE", "schema": "dbo", "name": "usp_get_users", "action": "WRITE" }
  ]
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
curl -X POST "http://localhost:8080/api/synchronizer/db-to-repo/myDatabase?dryRun=false" \
  -H "X-API-Key: $API_KEY"
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
- `commitish`: Git commit, branch, or tag reference to sync from. Anything other than the head of the default branch runs as a **dry run** unless `force` is true.
- `dbs`: List of database names to sync
- `dryRun`: Preview changes without applying (default: false)
- `force`: Skip the onboarding and out-of-sync checks, and allow a commitish other than the head (default: false)
- `allowDataLoss`: Apply changes that drop tables or columns (default: false); otherwise the database is skipped with `SKIPPED_DATA_LOSS`

**Example:**
```bash
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
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
  {prefix-path}/          (only when set)
    {databaseName}/
      {objectType}/
        {schema}/
          {objectName}.sql
```

- **base/**: The canonical definition of each object
- **diff/**: Per-database overrides. An empty file means the object doesn't exist in that database.

### Synchronization Flow

1. **DB → Repo**: Compares the database with the repository and records differences as overrides under `diff/`, then commits and pushes.
2. **Repo → DB**: Applies the files that changed since the database's last synced commit, in one transaction per database, then moves the database's tag.

### Tagging

Each database has one Git tag: `{prefix-path}/{db}`, or just `{db}` without a prefix (lower case). The tag marks the commit the database matches, and moves on every successful repo-to-db. Adous uses it to detect databases that changed outside Git.

See [docs/architecture.md](docs/architecture.md) for the details.

## Monitoring and Health Checks

Adous includes built-in monitoring capabilities via Spring Boot Actuator:

### Available Endpoints

- **Health**: `http://localhost:8080/actuator/health` - Application health status (public)
- **Info**: `http://localhost:8080/actuator/info` - Application information (public)
- **Metrics**: `http://localhost:8080/actuator/metrics` - Application metrics (requires `X-API-Key`)
- **Prometheus**: `http://localhost:8080/actuator/prometheus` - Prometheus-compatible metrics (requires `X-API-Key`)

Recent Prometheus versions can send the key with `http_headers` in the scrape config:

```yaml
scrape_configs:
  - job_name: adous
    metrics_path: /actuator/prometheus
    http_headers:
      X-API-Key:
        secrets: ["<your API key>"]
    static_configs:
      - targets: ["adous:8080"]
```

### Metrics

The application exports metrics including:
- HTTP request/response metrics
- JVM memory and GC metrics
- Database connection pool metrics

### Docker Health Check

When running in Docker, the container includes a health check that polls the actuator health endpoint every 30 seconds.

## Versioning & Releases

Adous follows [Semantic Versioning](https://semver.org/) (SemVer):

- **MAJOR.MINOR.PATCH** (e.g., `1.2.3`)
  - **MAJOR**: Breaking changes
  - **MINOR**: New features (backward-compatible)
  - **PATCH**: Bug fixes (backward-compatible)

### Making a Release

1. Set a release version in `build.gradle` (e.g. `0.2.0`, not `-SNAPSHOT`) and add a section to `CHANGELOG.md`.
2. Merge to `main`. The **Tag release** workflow creates the `v0.2.0` tag, then starts the **Release** workflow (GitHub release) and the image workflow (`ghcr.io/.../adous:v0.2.0`).

The workflow does nothing for `-SNAPSHOT` versions or tags that already exist. It can also be started by hand from the Actions tab.

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

- `ghcr.io/majidkabir/adous:latest` - Latest build from main
- `ghcr.io/majidkabir/adous:0.1.0` - Version from build.gradle
- `ghcr.io/majidkabir/adous:abc123def456` - Commit SHA (short)
- `ghcr.io/majidkabir/adous:v0.1.0` - Release tag

**Use specific version tags in production** for reproducibility.

## Best Practices

1. **Always use dry-run first**: Preview changes before applying
2. **Regular syncs**: Keep databases and repository in sync
3. **Use force carefully**: It skips the out-of-sync check that protects changes made directly in a database
4. **Use allowDataLoss only on purpose**: Without it, dropping tables or columns is refused
5. **Review out-of-sync results**: Run db-to-repo for that database first
6. **Leverage .syncignore**: Exclude objects you don't want to sync

## Testing

Run tests with:

```bash
./gradlew test
```

Integration tests use Testcontainers for SQL Server.

## CI/CD

GitHub Actions workflows in `.github/workflows/`:

| Workflow | Runs on | Does |
|---|---|---|
| `build-test.yaml` | PRs, pushes to main | Builds and runs all tests, including Testcontainers integration tests |
| `build-push.yaml` | Pushes to main, release tags; PRs that change the image | Builds the multi-arch image; pushes it to GHCR except on PRs |
| `tag-release.yaml` | `build.gradle` changes on main | Tags `vX.Y.Z` for a new release version and starts the release |
| `release.yaml` | Release tags | Creates the GitHub release |

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

- **Issues**: [Report bugs](https://github.com/majidkabir/Adous/issues/new?template=bug_report.md) or [request features](https://github.com/majidkabir/Adous/issues/new?template=feature_request.md)
- **Discussions**: [Ask questions and share ideas](https://github.com/majidkabir/Adous/discussions)
- **Documentation**: Help improve docs in [docs/](docs/) directory

## License

See [LICENSE](LICENSE) file for details.
