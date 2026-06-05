# Spec: Relocate Outbox Query from CalculationJobPortAdapter to OutboxEventPortAdapter

- Issue: #1076
- Date: 2026-06-05
- Status: Approved (brainstorming complete)

## 1. Background / Problem

`CalculationJobPortAdapter` (module-infra) owns 18 port methods. 16 of them manage `calculation_jobs` lifecycle through `jobRepository`. One outlier — `findCompletedJobsMissingOutboxEvents` — queries the `outbox_events` table. Conceptually that query is an outbox-domain concern: it scans the cross-product of completed jobs and existing outbox events to find gaps.

The adapter's `jdbc: NamedParameterJdbcTemplate` field is used by only 2 of 18 methods: `createOrFindActiveJob` (INSERT..ON CONFLICT..RETURNING) and the outlier outbox query. Moving the outlier out reduces the `jdbc` usage to one justified method.

### Goal

Move the method to the port that owns its underlying table, with no behavior change. Acceptance criteria from #1076:

- `findCompletedJobsMissingOutboxEvents` lives on `OutboxEventPort`, not `CalculationJobPort`.
- All callers updated.
- `CalculationJobPortAdapter` no longer queries `outbox_events`.
- `./gradlew compileKotlin compileJava --continue` passes.
- `./gradlew test` passes.

## 2. Decision

> Relocate the method to `OutboxEventPort` with inline JDBC in the adapter. Keep the method name, signature, and SQL identical. Do not touch the `jdbc` field in `CalculationJobPortAdapter` — `createOrFindActiveJob` still needs it.

### Component changes

**`module-core/.../core/port/out/CalculationJobPort.kt`**
- Remove `fun findCompletedJobsMissingOutboxEvents(limit: Int): List<UUID>` (line 27).
- Interface now has 17 methods.

**`module-core/.../core/port/out/OutboxEventPort.kt`**
- Add `fun findCompletedJobsMissingOutboxEvents(limit: Int): List<UUID>`.
- Interface now has 6 methods.

**`module-infra/.../adapter/outgoing/CalculationJobPortAdapter.kt`**
- Remove the method override (current lines 118–130).
- 17 overrides remain. The `jdbc` field stays for `createOrFindActiveJob`.

**`module-infra/.../adapter/outgoing/OutboxEventPortAdapter.kt`**
- Add constructor parameter `private val jdbc: NamedParameterJdbcTemplate`.
- Add the SQL block (verbatim from the old location) as a new override, annotated `@Transactional(value = "transactionManager", readOnly = true)` to match the existing read-only methods in this adapter.
- 6 overrides total.

**`module-infra/.../infrastructure/job/OutboxCompensatingScanner.kt`**
- Line 25: `jobPort.findCompletedJobsMissingOutboxEvents(50)` → `outboxPort.findCompletedJobsMissingOutboxEvents(50)`.
- The `jobPort` constructor parameter is no longer used — remove it. Final constructor: `(outboxPort, executor)`.

### Data flow (unchanged behavior)

```
OutboxCompensatingScanner.scan()
  → outboxPort.findCompletedJobsMissingOutboxEvents(50)
  → jdbc.queryForList(SELECT j.job_id FROM calculation_jobs j
                       WHERE j.status = 'COMPLETED'
                         AND j.completed_at < now() - INTERVAL '1 minute'
                         AND NOT EXISTS (SELECT 1 FROM outbox_events o
                                         WHERE o.job_id = j.job_id
                                           AND o.event_type = 'CALCULATION_COMPLETED')
                       LIMIT :limit,
                       {limit: 50}, UUID::class.java)
  → for each jobId: outboxPort.insertIfAbsent("CALCULATION_COMPLETED", jobId, payload)
```

### Error handling

- SQL failure bubbles to `LogicExecutor.executeVoid` (already wrapping the scanner body). No new exception types or wrapping introduced by this refactor.
- `jdbc.queryForList` returning empty list short-circuits the loop, same as today.

### Tests

**Existing test file (modify):**
- `module-infra/src/test/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapterTest.kt`
  - Already has `@Mock repo: OutboxEventRepository` and `@InjectMocks adapter: OutboxEventPortAdapter`.
  - The new method uses `jdbc`, not `repo`. Adding a `@Mock lateinit var jdbc: NamedParameterJdbcTemplate` is required for `@InjectMocks` to satisfy the new constructor.
  - Add 3 test cases:
    - `findCompletedJobsMissingOutboxEvents returns ids from jdbc`
    - `findCompletedJobsMissingOutboxEvents returns empty list when jdbc yields nothing`
    - `findCompletedJobsMissingOutboxEvents passes limit to jdbc query`

**No other test files exist** for `CalculationJobPortAdapter` or `OutboxCompensatingScanner` (verified by `find . -name "CalculationJobPortAdapterTest*" -o -name "OutboxCompensatingScannerTest*"` returning no results); nothing to delete or migrate. The absence is unchanged.

### Verification

- `./gradlew compileKotlin compileJava --continue` — must pass.
- `./gradlew test` — must pass.
- No runtime server check. The feature is gated by `app.outbox.compensating-scanner.enabled` (default off per `matchIfMissing = false`), and the change is pure relocation with identical SQL.

## 3. Trade-offs

### Sensitivity

* The SQL itself — identical wording moved verbatim, so zero sensitivity.
* Mockito wiring — adding `@Mock jdbc` to an existing test file. Standard pattern in this codebase.

### Trade-off

| Choice | Gained | Gave up |
| ------ | ------ | ------- |
| Keep `jdbc` in `CalculationJobPortAdapter` | Minimal diff, no scope creep | Adapter still has 1 raw-SQL method (justified by issue) |
| Keep method name unchanged | Zero caller code churn beyond port swap | Method name still reads as "CalculationJob..." even though it lives on OutboxEventPort |
| Inline SQL in adapter | SQL visible at the call site, no new repository method | Slight inconsistency with 4 of 5 existing adapter methods that delegate to repository |

### Risk

* Outbox scanner constructor change — `OutboxCompensatingScanner` constructor signature changes. No other production caller exists for this class. Test impact: none (no test file for the scanner).
* `@InjectMocks` will fail at test construction if the new `jdbc` mock is forgotten. Mitigated by adding it in the same PR.

### Non-Risk

* Cross-table SQL (calculation_jobs × outbox_events) lives on the outbox port. Conceptually defensible: the query answers "which jobs lack their outbox event", and outbox events are the second operand.
* Behavior parity — same SQL, same caller loop, same executor wrapping. No runtime risk of different results.

## 4. Result / Evidence

### Metrics

| Metric | Before | After | Notes |
| ------ | ----: | ----: | ----- |
| `CalculationJobPort` methods | 18 | 17 | One removed |
| `OutboxEventPort` methods | 5 | 6 | One added |
| `CalculationJobPortAdapter` overrides | 18 | 17 | One removed |
| `OutboxEventPortAdapter` overrides | 5 | 6 | One added |
| `jdbc` users in `CalculationJobPortAdapter` | 2 | 1 | `createOrFindActiveJob` only |
| `OutboxEventPortAdapter` constructor params | 1 (`repo`) | 2 (`repo`, `jdbc`) | `jdbc` added |

### Observed Result (expected)

* Compile clean.
* Unit tests pass.
* No change in runtime behavior when `app.outbox.compensating-scanner.enabled=true`.

## 5. Summary

> One method moves from one port to the port that owns the table it queries. Pure relocation, no behavior change, minimal blast radius.
