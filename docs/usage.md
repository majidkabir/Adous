# Adous Usage Guide

Quick reference for REST API endpoints, environment variables, common scenarios, and troubleshooting.

## Table of Contents

- [REST API Endpoints](#rest-api-endpoints)
- [Environment Variables](#environment-variables)
- [Authentication](#authentication)
- [Common Scenarios](#common-scenarios)
- [Dry Run Mode](#dry-run-mode)
- [Error Handling](#error-handling)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

## REST API Endpoints

### OpenAPI Documentation

Interactive API documentation available at:
```
http://localhost:8080/swagger-ui.html
```

### Initialize Repository

**Initialize an empty Git repository with all objects from a database.**

```http
POST /api/synchronizer/init-repo/{dbName}
```

**Path Parameters:**
- `dbName` - Database name (as configured in `application.yml`)

**Response:**
```json
{
  "dbName": "myDatabase",
  "dryRun": false,
  "result": "[list of all objects added]",
  "message": "Repository initialized successfully"
}
```

**Example:**
```bash
curl -X POST http://localhost:8080/api/synchronizer/init-repo/myDatabase \
  -H "X-API-Key: your-api-key"
```

**Notes:**
- Only works on empty repositories
- Creates initial baseline commit
- Use this once when setting up a new repository

---

### Sync Database to Repository

**Detect changes in a database and commit them to Git.**

```http
POST /api/synchronizer/db-to-repo/{dbName}?dryRun={true|false}
```

**Path Parameters:**
- `dbName` - Database name

**Query Parameters:**
- `dryRun` - Preview changes without committing (default: false)

**Response:**
```json
{
  "dbName": "myDatabase",
  "dryRun": false,
  "result": "Added: dbo/TABLE/NewTable.sql, Modified: dbo/PROCEDURE/UpdateProc.sql",
  "message": "Sync completed successfully"
}
```

**Example:**
```bash
# Apply changes
curl -X POST "http://localhost:8080/api/synchronizer/db-to-repo/myDatabase?dryRun=false" \
  -H "X-API-Key: your-api-key"

# Preview only
curl -X POST "http://localhost:8080/api/synchronizer/db-to-repo/myDatabase?dryRun=true" \
  -H "X-API-Key: your-api-key"
```

**Notes:**
- Detects added, modified, and deleted objects
- Creates a commit with descriptive message
- Tags the commit with database name
- Pushes to remote if configured

---

### Sync Repository to Database(s)

**Apply changes from a Git commit to one or more databases.**

```http
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
- `commitish` - Git reference (branch name, tag, or SHA)
- `dbs` - Array of database names to sync
- `dryRun` - Preview without applying (default: false)
- `force` - Force sync even if database out-of-sync (default: false)

**Response:**
```json
{
  "commitish": "main",
  "dbs": ["database1"],
  "dryRun": false,
  "force": false,
  "result": "Applied: dbo/TABLE/NewTable.sql, dbo/PROCEDURE/UpdateProc.sql",
  "message": "Sync completed successfully"
}
```

**Example:**
```bash
# Apply to one database
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "commitish": "main",
    "dbs": ["myDatabase"],
    "dryRun": false,
    "force": false
  }'

# Sync specific tag to multiple databases
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "commitish": "v1.2.3",
    "dbs": ["testDb", "stagingDb"],
    "dryRun": false,
    "force": false
  }'

# Preview changes (dry run)
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "commitish": "feature-branch",
    "dbs": ["devDb"],
    "dryRun": true,
    "force": false
  }'
```

**Notes:**
- Checks if database is in sync (unless `force: true`)
- Applies changes in dependency order
- Tags commit with database name after success
- Atomic per database (partial failures reported)

---

### Health Check

```http
GET /actuator/health
```

**Response:**
```json
{
  "status": "UP"
}
```

---

### Metrics (Prometheus)

```http
GET /actuator/prometheus
```

Returns Prometheus-formatted metrics.

---

## Environment Variables

All configuration can be overridden with environment variables.

### Authentication

| Variable | Description | Default |
|----------|-------------|---------|
| `AUTH_TYPE` | Authentication type (`APIKEY`) | `APIKEY` |
| `API_KEY` | API key for APIKEY authentication | - |

### Database

Database connections are configured in `application.yml`. Use environment-specific profiles or override individual properties:

```bash
export SPRING_DATASOURCES_DBS_MYDB_URL="jdbc:sqlserver://..."
export SPRING_DATASOURCES_DBS_MYDB_USERNAME="sa"
export SPRING_DATASOURCES_DBS_MYDB_PASSWORD="secret"
```

### Git Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `GIT_REMOTE_URI` | Git repository URL | - |
| `GIT_TOKEN` | GitHub/GitLab personal access token | - |
| `GIT_PREFIX_PATH` | Optional prefix for paths (e.g., `databases`) | `` |
| `GIT_COMMIT_USERNAME` | Git commit author name | `Adous System` |
| `GIT_COMMIT_EMAIL` | Git commit author email | `adous@mail.com` |
| `GIT_DEFAULT_BRANCH` | Default branch name | `main` |
| `GIT_SYNC_IGNORE_FILE` | Path to custom `.syncignore` file | `` |

### Server

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP port | `8080` |

### Example: Production Environment

```bash
export AUTH_TYPE=APIKEY
export API_KEY=prod-secure-key-xyz
export GIT_REMOTE_URI=https://github.com/myorg/db-schemas.git
export GIT_TOKEN=ghp_xxxxxxxxxxxx
export GIT_COMMIT_USERNAME="Adous Production"
export GIT_COMMIT_EMAIL="adous@prod.example.com"
export GIT_DEFAULT_BRANCH=main
export SERVER_PORT=8080
```

---

## Authentication

### API Key Authentication

Set the API key in `application.yml` or via environment variable:

```yaml
spring:
  application:
    authentication:
      type: APIKEY
      api-key: your-secure-api-key
```

Or:

```bash
export API_KEY=your-secure-api-key
```

Include the key in the `X-API-Key` header:

```bash
curl -X POST http://localhost:8080/api/synchronizer/db-to-repo/myDb \
  -H "X-API-Key: your-secure-api-key"
```

---

## Common Scenarios

### Scenario 1: Initial Setup

**Goal:** Version-control an existing database.

```bash
# 1. Configure database in application.yml
# 2. Start Adous
./gradlew bootRun

# 3. Initialize repository
curl -X POST http://localhost:8080/api/synchronizer/init-repo/myDatabase \
  -H "X-API-Key: your-api-key"

# Repository now contains all database objects
```

---

### Scenario 2: Apply Development Changes to Test

**Goal:** Sync changes from dev database to test database.

```bash
# 1. Developer makes changes in dev database

# 2. Sync dev → repo
curl -X POST "http://localhost:8080/api/synchronizer/db-to-repo/devDb?dryRun=false" \
  -H "X-API-Key: your-api-key"

# 3. Sync repo → test
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "commitish": "main",
    "dbs": ["testDb"],
    "dryRun": false,
    "force": false
  }'
```

---

### Scenario 3: Deploy Specific Version to Production

**Goal:** Deploy a tagged release to production.

```bash
# 1. Tag a release in Git
git tag v1.0.0
git push origin v1.0.0

# 2. Sync tagged version to production
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "commitish": "v1.0.0",
    "dbs": ["productionDb"],
    "dryRun": false,
    "force": false
  }'
```

---

### Scenario 4: Preview Changes Before Applying

**Goal:** See what would change without modifying the database.

```bash
# Dry run: preview repo → db changes
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "commitish": "main",
    "dbs": ["myDb"],
    "dryRun": true,
    "force": false
  }'

# Response shows what would be added/modified/deleted
```

---

### Scenario 5: Exclude Temporary Objects

**Goal:** Prevent temp tables/procedures from being versioned.

```bash
# 1. Create .syncignore in src/main/resources/
cat > src/main/resources/.syncignore <<EOF
**/TABLE/*/temp_*.sql
**/PROCEDURE/*/temp_*.sql
EOF

# 2. Restart Adous

# 3. Sync (temp objects will be ignored)
curl -X POST "http://localhost:8080/api/synchronizer/db-to-repo/myDb?dryRun=false" \
  -H "X-API-Key: your-api-key"
```

---

## Dry Run Mode

Dry run mode allows you to preview changes without applying them.

### When to Use

- Before syncing to production
- Testing new sync ignore patterns
- Validating repository state
- Understanding what changed

### How It Works

- **DB → Repo:** Shows which files would be added/modified/deleted
- **Repo → DB:** Shows which DDL scripts would be executed
- No commits, no database changes, no tags

### Example Output

```json
{
  "dryRun": true,
  "result": "Would add: dbo/TABLE/NewTable.sql, Would modify: dbo/PROCEDURE/UpdateProc.sql",
  "message": "Dry run completed (no changes applied)"
}
```

---

## Error Handling

### Common Error Responses

#### Database Not Configured

```json
{
  "error": "Database 'unknownDb' not found in configuration",
  "status": 404
}
```

**Solution:** Add database to `application.yml`.

---

#### Database Out of Sync

```json
{
  "error": "Database 'myDb' is out of sync. Last synced: abc1234, Repository HEAD: def5678. Use force=true to override.",
  "status": 409
}
```

**Solution:** Either sync the database first, or use `force: true` if you understand the implications.

---

#### Authentication Failed

```json
{
  "error": "Unauthorized",
  "status": 401
}
```

**Solution:** Include valid `X-API-Key` header.

---

#### DDL Execution Error

```json
{
  "error": "Failed to execute DDL: Invalid object name 'MissingTable'",
  "status": 500
}
```

**Solution:** Check dependency order, verify referenced objects exist.

---

#### Git Push Failed

```json
{
  "error": "Failed to push to remote: Authentication failed",
  "status": 500
}
```

**Solution:** Verify `GIT_TOKEN` is valid and has push permissions.

---

## Best Practices

### 1. Always Use Dry Run First

```bash
# Preview changes
curl ... -d '{"dryRun": true, ...}'

# If OK, apply
curl ... -d '{"dryRun": false, ...}'
```

### 2. Tag Releases

```bash
git tag v1.0.0
git push origin v1.0.0
```

Reference tags in sync operations for reproducibility.

### 3. Use .syncignore

Exclude temporary, generated, or environment-specific objects.

### 4. Monitor Sync Status

Check Git tags to see which commit each database is synced to:

```bash
git tag --list "*myDatabase*"
```

### 5. Regular Syncs

Sync development database to repo daily to avoid large drifts.

### 6. Review Commits

Inspect Git commits before applying to production:

```bash
git log --oneline --decorate
git show <commit-sha>
```

### 7. Backup Before Major Syncs

SQL Server backup:

```sql
BACKUP DATABASE MyDb TO DISK = '/backup/MyDb.bak';
```

### 8. Use Branches for Features

Experimental changes:

```bash
git checkout -b feature-new-schema
# Sync dev db to feature branch
# Test thoroughly
git checkout main
git merge feature-new-schema
```

### 9. Separate Environments

Use different database configurations per environment:

```yaml
# application-dev.yml
spring:
  datasources:
    dbs:
      myDb:
        url: jdbc:sqlserver://dev-server:1433;...

# application-prod.yml
spring:
  datasources:
    dbs:
      myDb:
        url: jdbc:sqlserver://prod-server:1433;...
```

### 10. Monitor Metrics

Check Prometheus metrics for sync performance and errors:

```bash
curl http://localhost:8080/actuator/prometheus | grep adous
```

---

## Troubleshooting

### Issue: "Repository not empty" error

**Cause:** Trying to init-repo on existing repository.

**Solution:** Use `db-to-repo` instead of `init-repo` for existing repos.

---

### Issue: Objects not syncing

**Possible Causes:**
1. Matched by `.syncignore` pattern
2. Object is system-generated (`is_ms_shipped = 1`)
3. Object type not supported

**Solution:**
- Check `.syncignore` patterns
- Verify object ownership
- Review application logs for warnings

---

### Issue: Foreign key constraint violation during sync

**Cause:** Objects applied out of order.

**Solution:** Adous should handle this automatically. If error persists:
1. Check for circular FK references (rare, but possible with disabled constraints)
2. Manually order application or temporarily disable constraints

---

### Issue: "Database out of sync" warning

**Cause:** Database tag doesn't match repository HEAD.

**Solutions:**
1. Sync database first to bring it up to date
2. Use `force: true` if you want to override (carefully!)

---

### Issue: Git authentication failed

**Cause:** Invalid or expired GitHub token.

**Solution:**
1. Generate new personal access token with `repo` scope
2. Update `GIT_TOKEN` environment variable or `application.yml`
3. Restart Adous

---

### Issue: Slow sync performance

**Possible Causes:**
1. Large number of objects
2. Slow network to remote Git
3. Complex view dependencies

**Solutions:**
- Use `.syncignore` to exclude unnecessary objects
- Use local Git repo (no remote push) for faster iteration
- Increase JVM memory: `-Xmx2g`

---

### Issue: SQL syntax error during DDL execution

**Cause:** DDL script incompatible with target SQL Server version or settings.

**Solution:**
1. Verify source and target SQL Server versions match
2. Check database compatibility level
3. Review extracted DDL for manual corrections if needed

---

### Issue: Cannot see Swagger UI

**Cause:** Actuator endpoints not exposed or wrong URL.

**Solution:**
- Verify `management.endpoints.web.exposure.include` includes `info` and `health`
- Access: `http://localhost:8080/swagger-ui.html` (not `/swagger-ui/`)

---

### Issue: Docker container not starting

**Cause:** Database connection failure or missing environment variables.

**Solution:**
1. Check container logs: `docker logs adous`
2. Verify database connectivity from container
3. Ensure all required env vars set

---

## Advanced Usage

### Custom Commit Messages

Commit messages are auto-generated. To customize:

1. Extend `DatabaseRepositorySynchronizerService`
2. Override commit message generation method
3. Rebuild and deploy

### Scheduled Syncs

Use a cron job or Kubernetes CronJob to run periodic syncs:

```bash
# Crontab: sync every hour
0 * * * * curl -X POST http://localhost:8080/api/synchronizer/db-to-repo/myDb -H "X-API-Key: your-api-key"
```

### Multi-Region Deployment

Deploy Adous in each region with local database configs:

- Region A: Adous instance → Regional databases → Shared Git repo
- Region B: Adous instance → Regional databases → Shared Git repo

Use Git branches or prefixes to isolate regions if needed.

### Integration with CI/CD

**Example: GitHub Actions**

```yaml
name: Sync Database
on:
  schedule:
    - cron: '0 2 * * *'  # Daily at 2 AM
jobs:
  sync:
    runs-on: ubuntu-latest
    steps:
      - name: Sync dev database
        run: |
          curl -X POST http://adous.internal:8080/api/synchronizer/db-to-repo/devDb \
            -H "X-API-Key: ${{ secrets.ADOUS_API_KEY }}"
```

---

## See Also

- [Architecture Documentation](architecture.md) - How Adous works internally
- [Examples](../examples/workflow.md) - Step-by-step tutorial
- [README](../README.md) - Project overview and features

