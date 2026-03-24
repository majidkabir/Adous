---
name: git-sync-safety
description: Safety rules for Git sync operations in Adous — prevents dangerous patterns
---

# Git Sync Safety Rules

## Always Check Before Sync

Before repo→db sync:
1. Verify target DB tag matches expected commitish (out-of-sync detection)
2. If out-of-sync and `force=false`, REJECT and explain divergence
3. If `dryRun=true`, compute diff but make ZERO changes to DB or Git

## Tagging Strategy

Each successful repo→db sync creates a tag: `{dbName}/{timestamp}` (e.g., `production-db/20240315-143022`)
- Never reuse or overwrite existing sync tags
- Tag is created AFTER successful apply, never before
- On partial failure, do NOT create the tag

## Commit Discipline

- db→repo commits must include: object type, schema, object name in message
- Never commit empty diffs (check before committing)
- Push failures must be surfaced to the caller — never silently swallowed

## Force Flag

`force=true` bypasses out-of-sync check. Only use when:
- Explicitly requested by user
- User understands they may overwrite changes made directly in DB

Always log a WARNING when force=true is used.