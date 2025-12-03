# Contributing to Adous

Thank you for your interest in contributing to **Adous**! We welcome contributions of all kinds – bug reports, feature requests, documentation improvements, and code contributions.

## 📋 Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
  - [Reporting Bugs](#reporting-bugs)
  - [Suggesting Features](#suggesting-features)
  - [Contributing Code](#contributing-code)
- [Development Setup](#development-setup)
- [Pull Request Process](#pull-request-process)
- [Style Guidelines](#style-guidelines)
- [Community](#community)

## Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md). Please be respectful and constructive in all interactions.

## How Can I Contribute?

### Reporting Bugs

Found a bug? Help us fix it!

1. **Search existing issues** to avoid duplicates
2. **Use the bug report template** when creating a new issue
3. **Provide detailed information**:
   - Steps to reproduce
   - Expected vs actual behavior
   - Environment details (OS, Java version, SQL Server version)
   - Relevant logs (set `logging.level.app.majid.adous=DEBUG`)

[Report a Bug →](../../issues/new?template=bug_report.md)

### Suggesting Features

Have an idea for improvement?

1. **Check existing feature requests** to see if it's already proposed
2. **Use the feature request template** when creating a new issue
3. **Describe the use case** and why it would be valuable
4. **Consider implementation** if you have ideas

[Suggest a Feature →](../../issues/new?template=feature_request.md)

### Contributing Code

Ready to contribute code? Awesome!

1. **Start with an issue**: Comment on an existing issue or create a new one to discuss your approach
2. **Fork the repository** and create a feature branch
3. **Write your code** following our style guidelines
4. **Add tests** for new functionality
5. **Update documentation** if needed
6. **Submit a pull request** using the PR template

## Development Setup

### Prerequisites

- **Java 25** (Temurin or equivalent JDK)
- **Gradle** (wrapper included)
- **Docker** (for running SQL Server in tests)
- **Git** (obviously! 😄)

### Local Setup

1. **Clone your fork**:
   ```bash
   git clone https://github.com/YOUR-USERNAME/Adous.git
   cd Adous
   ```

2. **Build the project**:
   ```bash
   ./gradlew clean build
   ```

3. **Run tests**:
   ```bash
   ./gradlew test
   ```

4. **Run integration tests** (requires Docker):
   ```bash
   ./gradlew test --tests "*IT"
   ```

5. **Run the application locally**:
   ```bash
   ./gradlew bootRun
   ```

   The API will be available at `http://localhost:8080`  
   Swagger UI: `http://localhost:8080/swagger-ui.html`

### Project Structure

```
src/
├── main/java/app/majid/adous/
│   ├── AdousApplication.java          # Spring Boot entry point
│   ├── db/                             # Database services
│   │   ├── DatabaseService.java        # Interface
│   │   ├── MSSQLDatabaseService.java   # SQL Server implementation
│   │   └── ...
│   ├── git/                            # Git operations
│   │   ├── GitService.java
│   │   └── ...
│   └── synchronizer/                   # Sync logic
│       ├── DatabaseSynchronizer.java
│       └── ...
└── test/java/app/majid/adous/         # Tests
    ├── db/
    ├── git/
    ├── it/                             # Integration tests
    └── synchronizer/
```

### Running with Docker Compose

Test the full setup locally:

```bash
cd examples/mssql
docker compose up -d
```

This starts SQL Server and Adous service together.

## Pull Request Process

### Before Submitting

- [ ] Code builds successfully: `./gradlew clean build`
- [ ] All tests pass: `./gradlew test`
- [ ] New functionality includes tests
- [ ] Documentation is updated
- [ ] Code follows style guidelines
- [ ] Commit messages are clear and descriptive

### PR Guidelines

1. **Create a feature branch** from `main`:
   ```bash
   git checkout -b feature/my-awesome-feature
   ```

2. **Make focused commits**:
   - One logical change per commit
   - Clear commit messages (see [style guidelines](#commit-messages))

3. **Keep your branch up to date**:
   ```bash
   git fetch origin
   git rebase origin/main
   ```

4. **Push to your fork**:
   ```bash
   git push origin feature/my-awesome-feature
   ```

5. **Open a pull request**:
   - Use the PR template
   - Reference related issues: `Fixes #123`
   - Provide context and testing instructions
   - Request review from maintainers

### PR Review Process

- Maintainers will review your PR within **1 week**
- Address feedback by pushing new commits
- Once approved, a maintainer will merge your PR
- Your contribution will be included in the next release!

## Style Guidelines

### Java Code Style

- **Follow standard Java conventions**
- **Use meaningful variable names**: `databaseName` not `dbN`
- **Keep methods focused**: Single responsibility principle
- **Add JavaDoc for public APIs**
- **Use `@Override` annotations**
- **Prefer composition over inheritance**

Example:
```java
/**
 * Synchronizes database schema with Git repository.
 *
 * @param databaseName the name of the database to sync
 * @return SyncResult containing changes applied
 * @throws SyncException if synchronization fails
 */
public SyncResult syncToGit(String databaseName) {
    // Implementation
}
```

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Types**:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, no logic change)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Build process or auxiliary tool changes

**Examples**:
```
feat(sync): add support for synonyms synchronization

Implement bidirectional sync for SQL Server synonyms including
dependency resolution and proper ordering during apply.

Closes #42
```

```
fix(git): handle special characters in object names

Escape special characters in file paths when writing DDL files
to avoid Git errors on Windows systems.

Fixes #58
```

### Testing

- **Write unit tests** for new functionality
- **Use TestContainers** for integration tests requiring SQL Server
- **Aim for 80%+ code coverage**
- **Test edge cases** and error scenarios

Example test:
```java
@Test
void shouldSyncStoredProcedureToGit() {
    // Given
    String procName = "usp_GetUsers";
    String procDdl = "CREATE PROCEDURE usp_GetUsers AS SELECT * FROM Users";
    
    // When
    SyncResult result = synchronizer.syncToGit("myDatabase");
    
    // Then
    assertThat(result.getChangedObjects()).contains(procName);
    assertThat(gitRepo.getFileContent("stored_procedures/usp_GetUsers.sql"))
        .contains(procDdl);
}
```

### Documentation

- **Update README** if adding user-facing features
- **Update architecture docs** if changing internal design
- **Add inline comments** for complex logic
- **Keep examples up to date**

## Community

### Where to Get Help

- **GitHub Discussions**: For general questions and community support
- **GitHub Issues**: For bugs and feature requests
- **Pull Requests**: For code contributions

### Recognition

All contributors will be:
- Listed in release notes
- Acknowledged in the README (coming soon!)
- Thanked publicly on social media

### First-Time Contributors

New to open source? No problem!

Look for issues labeled:
- `good first issue`: Easy tasks for beginners
- `help wanted`: We'd love community contributions
- `documentation`: Improve docs (great way to start!)

Don't hesitate to ask questions in issues or discussions. We're here to help!

## Development Tips

### Enable Debug Logging

Add to `application.yml`:
```yaml
logging:
  level:
    app.majid.adous: DEBUG
```

### Test Against Real SQL Server

Use Docker:
```bash
docker run -e 'ACCEPT_EULA=Y' -e 'SA_PASSWORD=YourStrong@Passw0rd' \
  -p 1433:1433 -d mcr.microsoft.com/mssql/server:2022-latest
```

### IDE Setup

**IntelliJ IDEA**:
- Import as Gradle project
- Enable annotation processing
- Set Java SDK to 25

**VS Code**:
- Install "Extension Pack for Java"
- Install "Spring Boot Extension Pack"

### Useful Gradle Commands

```bash
# Clean build
./gradlew clean build

# Run tests
./gradlew test

# Run specific test
./gradlew test --tests "MSSQLDatabaseServiceTest"

# Build Docker image
./gradlew bootBuildImage

# Check for dependency updates
./gradlew dependencyUpdates
```

## Questions?

If you have questions about contributing, feel free to:
- Open a [GitHub Discussion](../../discussions)
- Comment on a relevant issue
- Reach out to maintainers

Thank you for contributing to Adous! 🎉

