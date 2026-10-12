---
name: release-prepare
description: Orchestrates a release: bumps version in build.gradle and updates CHANGELOG in a release PR; the Tag release workflow tags and publishes it on merge.
---

Prepare a release for Adous. The release version is: $ARGUMENTS

If no version is given, ask for one (X.Y.Z) before doing anything.

Steps:
1. Read current version from `build.gradle`
2. Update the version to $ARGUMENTS in `build.gradle`
3. Check `CHANGELOG.md` exists — if not, create it
4. Add a new section to CHANGELOG.md for version $ARGUMENTS with today's date
5. List commits since the last git tag and categorize them as Features / Bug Fixes / Internal
6. Open a release PR with these changes; CI runs the full test suite on it
7. Tell the user that merging it is the release: the **Tag release** workflow creates `v$ARGUMENTS`, then starts the Release workflow (GitHub release) and the image workflow (`ghcr.io/majidkabir/adous:v$ARGUMENTS`). No manual tagging is needed
8. After merge, check that the Tag release, Release and Build & push image runs succeeded