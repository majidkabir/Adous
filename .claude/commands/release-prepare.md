---
name: release-prepare
description: Orchestrates a release: bumps version in build.gradle, updates CHANGELOG, verifies tests pass, and prepares the release tag.
---

Prepare a release for Adous. The release version is: $ARGUMENTS

Steps:
1. Read current version from `build.gradle`
2. Update the version to $ARGUMENTS in `build.gradle`
3. Check `CHANGELOG.md` exists — if not, create it
4. Add a new section to CHANGELOG.md for version $ARGUMENTS with today's date
5. List commits since the last git tag and categorize them as Features / Bug Fixes / Internal
6. Remind the user to run `./gradlew test` before tagging
7. Show the git commands needed to create and push the release tag:
```
   git tag -a v$ARGUMENTS -m "Release v$ARGUMENTS"
   git push origin v$ARGUMENTS
```
8. Remind that Docker image will be published to ghcr.io automatically via GitHub Actions