# GitHub Repository Configuration

This document contains manual configuration steps to complete in the GitHub web interface to improve discoverability.

## Repository Topics

Add the following topics to your repository:

**How to add topics:**
1. Go to your repository on GitHub
2. Click the ⚙️ gear icon next to "About" section
3. Add topics in the "Topics" field

**Recommended topics:**

```
database-as-code
mssql
sql-server
spring-boot
java
gradle
schema-migration
devops
ci-cd
openapi
testcontainers
git
version-control
database-synchronization
ddl
schema-versioning
```

**Alternative tags (choose what fits):**
- `infrastructure-as-code` - If positioning alongside IaC tools
- `gitops` - If emphasizing GitOps workflow
- `database-devops` - For DevOps focus
- `schema-management` - Generic schema tooling
- `liquibase-alternative` - If comparing to similar tools
- `flyway-alternative` - If comparing to similar tools

## Repository Description

Update the repository description in GitHub settings:

**Recommended description:**

```
Synchronize SQL Server schemas with Git (DB as Code) – bidirectional, dependency-aware schema sync with intelligent ordering and safe table evolution
```

**Alternative (shorter):**

```
Database as Code for SQL Server: version-control schemas with Git through bidirectional synchronization
```

## Repository Settings

### General Settings

1. **Website:** Add your documentation URL (if deployed separately)
   - Example: `https://your-org.github.io/Adous`
   - Or link to main docs: `https://github.com/your-org/Adous/blob/main/docs/`

2. **Features:**
   - ✅ Enable Issues
   - ✅ Enable Discussions (recommended for Q&A)
   - ✅ Enable Projects (optional, for roadmap)
   - ✅ Enable Wiki (optional, for extended docs)

3. **Pull Requests:**
   - ✅ Allow squash merging (recommended)
   - ✅ Automatically delete head branches
   - ✅ Allow merge commits (optional)
   - ❌ Disable rebase merging (unless preferred)

4. **Social Preview Image:**
   - Upload a custom social preview image (1280x640px recommended)
   - Shows when sharing repository link
   - Can include logo, tagline, key features

### Branch Protection

Protect the `main` branch:

1. Go to Settings → Branches → Add rule
2. Branch name pattern: `main`
3. Enable:
   - ✅ Require a pull request before merging
   - ✅ Require status checks to pass before merging
     - Add: `build` (from build-test.yaml)
   - ✅ Require conversation resolution before merging
   - ✅ Do not allow bypassing the above settings

### Packages (GitHub Container Registry)

Make Docker images public:

1. Go to your repository → Packages
2. Click on `adous` package
3. Package settings → Change visibility → Public

Update package description:
```
Adous - Database as Code for SQL Server. Synchronize schemas with Git.
```

Link package to repository if not auto-linked.

### Secrets

Ensure required secrets are set (Settings → Secrets and variables → Actions):

- `GITHUB_TOKEN` - Auto-provided by GitHub Actions ✅
- Add any custom secrets your workflows need

### Environments

Optional: Create environments for deployment targeting:

1. Settings → Environments → New environment
2. Create environments:
   - `development`
   - `staging`
   - `production`
3. Configure protection rules per environment

## README Badges

Update badge URLs in `README.md` once repository is public:

Replace `OWNER` placeholder in badges with your GitHub username/org:

```markdown
[![Build & Test](https://github.com/YOUR-USERNAME/Adous/actions/workflows/build-test.yaml/badge.svg)](https://github.com/YOUR-USERNAME/Adous/actions/workflows/build-test.yaml)
[![Docker Image](https://ghcr-badge.deta.dev/YOUR-USERNAME/adous/latest_tag?label=Docker&color=blue)](https://github.com/YOUR-USERNAME/Adous/pkgs/container/adous)
```

## GitHub Community Standards

Check your community profile:

1. Go to Insights → Community
2. Verify checkmarks for:
   - ✅ Description
   - ✅ README
   - ✅ License
   - ✅ Code of conduct
   - ✅ Contributing guidelines
   - ✅ Issue templates
   - ✅ Pull request template

All should be green after completing these steps!

## Search Engine Optimization

To help search engines and GitHub search find your repo:

1. **Keywords in README:** Already done ✅
   - "Database as Code"
   - "SQL Server"
   - "Schema migration"
   - "Version control"

2. **Descriptive commits:** Use clear commit messages

3. **Documentation depth:** Architecture and usage docs added ✅

4. **Examples:** Real-world examples provided ✅

5. **Active maintenance:** Regular commits and responses to issues

## Discoverability Checklist

- [ ] Add repository topics (15+ recommended)
- [ ] Update repository description
- [ ] Enable Issues and Discussions
- [ ] Configure branch protection for `main`
- [ ] Make Docker images public in GHCR
- [ ] Update README badge URLs with actual org/username
- [ ] Upload social preview image (optional)
- [ ] Verify all community standards are met
- [ ] Create first release tag: `git tag v0.1.0 && git push origin v0.1.0`
- [ ] Share repository on relevant forums/communities

## Post-Launch Promotion

After repository is public and polished:

1. **Dev.to / Medium:** Write a blog post about DB as Code approach
2. **Reddit:** Share on r/programming, r/java, r/database, r/devops
3. **Hacker News:** Submit if article/launch is interesting
4. **Twitter/X:** Tweet with hashtags #DatabaseAsCode #SQLServer #DevOps
5. **LinkedIn:** Share with your network
6. **SQL Server community:** SQL Server Central, SQL Server subreddit
7. **GitHub topics:** Star repos with similar topics, engage with community
8. **Awesome lists:** Submit to awesome-sqlserver, awesome-devops lists
9. **Product Hunt:** Launch if significant traction
10. **Conference talks:** Submit to DevOps/Database conferences

## Monitoring Discoverability

Track your repository's reach:

1. **Insights → Traffic:** Views, clones, referrers
2. **Insights → Community:** Stars, forks, contributors
3. **GitHub Search:** Search for "database as code sql server" and check ranking
4. **Google Search:** Search for key phrases after a few weeks
5. **Star History:** Use star-history.com to track growth

---

Last updated: December 2025

