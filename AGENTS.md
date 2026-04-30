# Repository Instructions for Codex

This repository also has Claude Code instructions in:

- `CLAUDE.md`
- `.claude/rules/`

When working in this repository, read and follow those files as project-specific guidance.

Important:
- Prefer small, focused changes.
- Do not run destructive database commands unless explicitly asked.
- For performance work, preserve before/after metrics.
- For backend changes, pay attention to transactions, idempotency, PGMQ/Kafka boundaries, and connection-pool pressure.
- Run relevant tests before committing when feasible.
