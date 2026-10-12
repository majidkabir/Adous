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
  "message": "Repository initialized successfully",
  "changes": [
    { "path": "base/TABLE/dbo/users.sql", "type": "TABLE", "schema": "dbo", "name": "users", "action": "WRITE" },
    { "path": "base/PROCEDURE/dbo/usp_get_users.sql", "type": "PROCEDURE", "schema": "dbo", "name": "usp_get_users", "action": "WRITE" }
  ]
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
  "message": "Sync completed successfully",
  "changes": [
    { "path": "diff/prod/mydatabase/PROCEDURE/dbo/usp_get_orders.sql", "type": "PROCEDURE", "schema": "dbo", "name": "usp_get_orders", "action": "WRITE" },
    { "path": "diff/prod/mydatabase/VIEW/dbo/v_report.sql", "type": "VIEW", "schema": "dbo", "name": "v_report", "action": "DELETE" },
    { "path": "diff/prod/mydatabase/FUNCTION/dbo/fn_old.sql", "type": "FUNCTION", "schema": "dbo", "name": "fn_old", "action": "MARK_ABSENT" }
  ]
}
```

`changes[].action`:
- `WRITE` — file written with the object's definition
- `DELETE` — database-specific override removed (the database matches `base/` again)
- `MARK_ABSENT` — empty override written: the object does not exist in this database

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
- Records differences as overrides for this database under `diff/`; `base/` is never changed by this endpoint
- Commits to the default branch (`Repo synced with DB: <db>`) and pushes
- Creates the database's tag on its first sync (onboarding)
- Objects already matching the repository's `HEAD` are left alone, so changes committed by others aren't reverted

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
- `commitish` - Git reference (branch name, tag, or SHA). Anything other than the head of the default branch runs as a **dry run** unless `force` is true.
- `dbs` - Array of database names to sync
- `dryRun` - Preview without applying (default: false)
- `force` - Skip the onboarding and out-of-sync checks, and allow a `commitish` other than the head (default: false)
- `allowDataLoss` - Apply changes that drop tables or columns (default: false). When false, a database whose changes include `DROP TABLE` or `DROP COLUMN` is skipped with status `SKIPPED_DATA_LOSS` and the blocked statements in `message`. Dry runs report this too. `force` does not imply `allowDataLoss`.

**Response:**
One result per database:

```json
[
  {
    "dbName": "database1",
    "status": "SYNCED",
    "changes": [
      { "type": "TABLE", "schema": "dbo", "name": "orders", "action": "CREATE_OR_UPDATE" },
      { "type": "PROCEDURE", "schema": "dbo", "name": "usp_old", "action": "DROP" }
    ]
  },
  {
    "dbName": "database2",
    "status": "SKIPPED_DATA_LOSS",
    "changes": [],
    "message": "Sync to 'database2' would destroy data; set allowDataLoss=true to apply: ALTER TABLE [dbo].[orders] DROP COLUMN [legacy];"
  }
]
```

`status` is one of `SYNCED`, `SUCCESS_DRY_RUN`, `SKIPPED_NOT_ONBOARDED`, `SKIPPED_OUT_OF_SYNC`, `SKIPPED_DATA_LOSS`, `FAILED`. `changes` lists the objects applied (or, in a dry run, that would be applied). `message` gives the reason for skipped and failed databases and is omitted on success.

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

# Apply a specific tag to multiple databases.
# Anything other than the head of main needs force: true, which also skips
# the out-of-sync check - run a dry run first.
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{
    "commitish": "v1.2.3",
    "dbs": ["testDb", "stagingDb"],
    "dryRun": false,
    "force": true
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
- Requires each database to be onboarded and to match the repository at its tag (unless `force: true`)
- Applies only what changed since the database's tag
- Refuses to drop tables or columns unless `allowDataLoss: true`
- Applies changes in dependency order, in one transaction per database
- Moves the database's tag to `commitish` after success
- One database failing doesn't stop the others

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
X-API-Key: your-api-key
```

Returns Prometheus-formatted metrics. Requires the API key (only `/actuator/health` and `/actuator/info` are public).

---

## Environment Variables

All configuration can be overridden with environment variables.

### Authentication

| Variable | Description | Default |
|----------|-------------|---------|
| `AUTH_TYPE` | Authentication type (`APIKEY`) | `APIKEY` |
| `API_KEY` | API key for APIKEY authentication. **Required** — startup fails if unset | - |
| `ADOUS_LOG_LEVEL` | Log level for Adous classes (`DEBUG` for troubleshooting) | `INFO` |

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
| `GIT_PREFIX_PATH` | Folder under `diff/` for this deployment's overrides (e.g. `prod`); use a different one per Adous instance sharing a repository | `` |
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

# 2. Sync dev → repo (recorded as dev overrides under diff/)
curl -X POST "http://localhost:8080/api/synchronizer/db-to-repo/devDb?dryRun=false" \
  -H "X-API-Key: your-api-key"

# 3. Promote the change: copy the override file over the base/ file in Git
#    (e.g. in a pull request), so it applies to every database

# 4. Sync repo → test
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

**Goal:** Deploy a tagged version of the schema repository to production.

A `commitish` other than the head of `main` runs as a dry run unless `force: true`, and `force` also skips the out-of-sync check. Preview first:

```bash
# 1. Tag a version in the schema repository
git tag v1.0.0
git push origin v1.0.0

# 2. Preview (dry run)
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{ "commitish": "v1.0.0", "dbs": ["productionDb"], "dryRun": true }'

# 3. Apply
curl -X POST http://localhost:8080/api/synchronizer/repo-to-db \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-api-key" \
  -d '{ "commitish": "v1.0.0", "dbs": ["productionDb"], "dryRun": false, "force": true }'
```

If `v1.0.0` is the head of `main`, `force` isn't needed.

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
# 1. Create a .syncignore file and point Adous at it
cat > /etc/adous/.syncignore <<EOF
**/TABLE/*/temp_*.sql
**/PROCEDURE/*/temp_*.sql
EOF
export GIT_SYNC_IGNORE_FILE=/etc/adous/.syncignore

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

DB → Repo (`/db-to-repo/myDatabase?dryRun=true`):

```json
{
  "dbName": "myDatabase",
  "dryRun": true,
  "message": "Sync completed successfully",
  "changes": [
    { "path": "diff/prod/mydatabase/PROCEDURE/dbo/usp_get_orders.sql", "type": "PROCEDURE", "schema": "dbo", "name": "usp_get_orders", "action": "WRITE" }
  ]
}
```

Repo → DB returns `"status": "SUCCESS_DRY_RUN"` per database with the `changes` that would be applied.

---

## Error Handling

### HTTP Errors

Errors return this shape:

```json
{
  "status": 400,
  "error": "Invalid Argument",
  "message": "Database unknownDb does not exist",
  "path": "uri=/api/synchronizer/db-to-repo/unknownDb"
}
```

| Status | `error` | Typical cause |
|---|---|---|
| 400 | `Invalid Argument` | Database name not configured |
| 400 | `Invalid State` | `init-repo` on a non-empty repository, or a database with no objects |
| 400 | `Validation Failed` | Invalid `repo-to-db` body; details in `fieldErrors` (e.g. `{"dbs": "At least one database is required"}`) |
| 401 | — | Missing or wrong `X-API-Key`: `{"error": "Missing API key. Please provide X-API-Key header."}` or `{"error": "Invalid API key."}` |
| 500 | `Git API Error` | Fetch or push failed, e.g. `Push rejected by remote: refs/heads/main: REJECTED_NONFASTFORWARD` (another writer pushed first; retry) or bad `GIT_TOKEN` |
| 500 | `IO Error` / `Internal Server Error` | Unexpected failure; check the logs |

### Per-Database Results (repo-to-db)

Problems with individual databases are **not** HTTP errors. The request returns 200 with one result per database:

| `status` | Meaning | What to do |
|---|---|---|
| `SKIPPED_NOT_ONBOARDED` | The database has no tag yet | Run `init-repo` or `db-to-repo` for it |
| `SKIPPED_OUT_OF_SYNC` | The database changed outside Git since its last sync; `message` lists the objects | Run `db-to-repo` for it, or use `force` to overwrite those changes |
| `SKIPPED_DATA_LOSS` | Changes would drop tables or columns; `message` lists the statements | Review, then repeat with `allowDataLoss: true` |
| `FAILED` | A statement or the tag push failed; nothing was changed for this database | See `message` (e.g. the SQL Server error) |

---

## Best Practices

### 1. Always Use Dry Run First

```bash
# Preview changes
curl ... -d '{"dryRun": true, ...}'

# If OK, apply
curl ... -d '{"dryRun": false, ...}'
```

### 2. Tag Versions of the Schema Repository

```bash
git tag v1.0.0
git push origin v1.0.0
```

Reference tags in sync operations for reproducibility. A tag that isn't the head of `main` needs `force: true` (see [Scenario 3](#scenario-3-deploy-specific-version-to-production)).

### 3. Use .syncignore

Exclude temporary, generated, or environment-specific objects.

### 4. Monitor Sync Status

Each database's tag (its name in lower case) marks the commit it is synced to:

```bash
git fetch --tags --force
git log -1 --format='%h %ad %s' mydatabase
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

### 8. Review Schema Changes in Pull Requests

Adous reads and writes only the default branch. Branches are still useful for changes made in Git:

```bash
git checkout -b feature-new-schema
# edit base/ files
# open a pull request, review, merge to main
# then repo-to-db from main
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

Prometheus metrics cover HTTP requests (including the sync endpoints' duration and status), JVM and connection pools:

```bash
curl -H "X-API-Key: your-api-key" http://localhost:8080/actuator/prometheus | grep http_server_requests
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

### Issue: Database skipped with `SKIPPED_OUT_OF_SYNC`

**Cause:** The database was changed outside Git since its last sync; it no longer matches the repository at its tag. `message` lists the objects.

**Solutions:**
1. Run `db-to-repo` for that database to record the changes, then run `repo-to-db` again
2. Or use `force: true` to overwrite the direct changes (carefully!)

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
- Increase JVM memory: `-Xmx2g`
- Note that requests run one at a time; a long repo-to-db delays the next request

---

### Issue: SQL syntax error during DDL execution

**Cause:** DDL script incompatible with target SQL Server version or settings.

**Solution:**
1. Verify source and target SQL Server versions match
2. Check database compatibility level
3. Review extracted DDL for manual corrections if needed

---

### Issue: Swagger UI "Try it out" returns 401

**Cause:** Swagger UI itself is public, but the API needs the `X-API-Key` header, and Swagger UI has no Authorize button for it yet.

**Solution:** Use Swagger UI (`http://localhost:8080/swagger-ui.html`) to read the API, and call it with `curl` or another client that sends the header.

---

### Issue: Docker container not starting

**Cause:** Database connection failure or missing environment variables.

**Solution:**
1. Check container logs: `docker logs adous`
2. Verify database connectivity from container
3. Ensure all required env vars set

---

## Advanced Usage

### Commit Messages

Adous commits as `GIT_COMMIT_USERNAME` / `GIT_COMMIT_EMAIL`, with these messages:
- `Repo initialized with DB: <db>` (init-repo)
- `Repo synced with DB: <db>` (db-to-repo)

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

Give each instance its own `GIT_PREFIX_PATH`, and keep database names unique across instances: database tags aren't prefixed, so the same name in two regions would share a tag.

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
- [README](../README.md) - Project overview and features

