---
name: git-sync-safety
description: Safety rules for Git sync operations in Adous — prevents dangerous patterns
---

# Git Sync Safety Rules

## Always Check Before Sync

Before repo→db sync:
1. Verify the DB is onboarded (has a tag) and matches the repository at its tag (out-of-sync detection)
2. If out-of-sync and `force=false`, skip it with `SKIPPED_OUT_OF_SYNC` and list the divergent objects
3. If the changes drop tables or columns and `allowDataLoss=false`, skip it with `SKIPPED_DATA_LOSS`
4. If `dryRun=true`, compute the changes but make ZERO changes to DB or Git

## Tagging Strategy

Each database has exactly one lightweight tag, `{prefix-path}/{db}` (or `{db}` without a prefix), lower case (e.g. `prod/orders`), marking the commit the database matches. Build it only through `GitService.tagName` / `getTag`, which also handle the legacy unprefixed tag.
- Created by init-repo or the database's first db→repo
- Moved (force-updated and force-pushed) to the applied commitish after every successful repo→db, including when there was nothing to apply
- Moved only after the DDL succeeded, inside the same transaction: if the push fails, the DDL rolls back
- On failure, do NOT move the tag

## Commit Discipline

- db→repo writes only `diff/` overrides for that database, never `base/`
- Commit messages name the database (`Repo synced with DB: <db>`); per-object detail is in the response's `changes`
- Never commit empty diffs (check before committing)
- Push failures must be surfaced to the caller — never silently swallowed (check JGit `PushResult` statuses)

## Force Flag

`force=true` bypasses out-of-sync check. Only use when:
- Explicitly requested by user
- User understands they may overwrite changes made directly in DB

Always log a WARNING when force=true is used.