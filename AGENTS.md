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

Load test flow:
- Use `RESET_VIEWS=1 RESET_ACTIVE_JOBS=1 COUNT=10000 CONCURRENCY=50 SAMPLE_INTERVAL=30 POST_SAMPLE_COUNT=2 ./load-test/run-v5-db-throughput.sh` for the V5 cold-miss DB throughput test unless the user asks for different parameters.
- This flow starts `:module-app:bootRun` if `localhost:8080` is not already healthy, deletes `character_valuation_views` only when `RESET_VIEWS=1`, marks active `calculation_jobs` for the first `COUNT` CSV IGNs as `FAILED/LOAD_TEST_RESET` only when `RESET_ACTIVE_JOBS=1`, runs `load_test_v5.py`, and samples DB progress every `SAMPLE_INTERVAL` seconds.
- The DB progress sampler reports `character_valuation_views`, `pgmq.q_expectation_calc_high`, `pgmq.q_external_api_queue`, `pgmq.q_result_ready_queue`, and active `API_REQUESTED` job count. Use these together; `q_expectation_calc_high=0` alone does not mean the external/result pipeline drained.
- Treat `RESET_VIEWS=1` and `RESET_ACTIVE_JOBS=1` as destructive DB work. Only run them when explicitly requested by the user.
- For a true cold-miss throughput test, use both reset flags. Resetting only `character_valuation_views` leaves active jobs in `API_REQUESTED`/`RETRYING` etc.; `createJob()` may return those existing active jobs, `dispatchToExternalApi()` may not enqueue new work, and throughput can appear capped by the timeout scanner batch size.
- `RESET_ACTIVE_JOBS=1` loads the first `COUNT` IGNs from `module-app/src/main/resources/data/userIgn_List.csv` into a temporary table and updates only those matching active jobs. Keep that scoped reset behavior intact; do not replace it with a full-table job reset.
- Preserve the script output and boot log path in reports. The boot log is written under `module-app/logs/load-test-bootrun-*.log`.
- For exploratory worker-pool throughput tests, collect only 6 DB progress samples at 30-second intervals unless the user asks otherwise. After the 6th sample, stop the load-test Python process and bootRun server, then analyze slow tasks from the app/boot log and report the throughput samples, including each sample's `delta_views` and `views_per_sec`, errors, and main slow-task categories.
- If view count does not increase while requests are being processed, check the boot log for `ResultReadyProjectionWorker`, `ResultProjection:ProjectBatch`, `ReadModel:BestEffortBatchWrite`, `UnexpectedRollbackException`, and JDBC type errors before assuming the external API is the bottleneck.
- Stop any load-test server or Python process after interrupted runs: check `pgrep -af 'load_test_v5|python3 load_test|gradlew :module-app:bootRun|ExpectationApplication'` and terminate only those load-test processes.
