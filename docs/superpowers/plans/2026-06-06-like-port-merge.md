# Like Port Hypothetical Seam Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete 5 dead Like outbound port files in `module-core`. Keep `LikeBufferStrategy` (only alive port). Update 1 legacy test that referenced the removed `LikeEventPublisher`/`LikeEventSubscriber`.

**Architecture:** Pure deletion. No new ports, no new adapters. Refactor restores the 6→1 reality (1 alive + 5 dead seams → just 1 alive). The previously sketched "6→2 merge" was based on assumed Redis adapters that never existed; this plan supersedes that.

**Tech Stack:** Kotlin, Spring Boot, JUnit5

---

## File Structure

### Files deleted (5 port files + 1 empty directory)

| File | Reason |
|------|--------|
| `module-core/src/main/kotlin/maple/expectation/core/port/out/like/LikeAtomicFetchStrategy.kt` | Dead seam, 0 impls/0 consumers |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationBufferStrategy.kt` | Dead seam, 0 impls/0 consumers |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationSyncPort.kt` | Dead seam, 0 impls/0 consumers |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeSyncPort.kt` | Dead seam, deprecated by #664 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeEventPublisher.kt` | Dead seam, also contains `LikeEventSubscriber` nested iface |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/like/` (directory) | Becomes empty after deletion |

### Files modified (3)

| File | Change |
|------|--------|
| `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeBufferStrategy.kt` | Fix KDoc — remove "5-Agent Council Agreement" block + stale `maple.expectation.service.v2.cache.LikeBufferStorage` reference + redundant `@see` |
| `module-app/src/test-legacy/java/maple/expectation/service/v2/like/realtime/LikeRealtimeSyncIntegrationTest.java` | Remove `LikeEventPublisher` and `LikeEventSubscriber` fields + 2 assertion test methods |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/like/` directory | Remove empty directory after file deletion |

### Files NOT touched (verified alive)

* `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/like/InMemoryLikeBufferStorage.kt` (impl of kept port)
* `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/BufferedLikeAspect.kt`
* `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/LikeSyncExecutor.kt`
* `module-infra/src/main/java/maple/expectation/infrastructure/like/DatabaseLikeProcessor.java`
* `module-app/src/test/kotlin/maple/expectation/testinfra/pgmq/PgmqClientIntegrationTest.kt`
* `module-app/src/test/kotlin/maple/expectation/testinfra/pgmq/PgmqTransactionAtomicityTest.kt`
* `module-infra/src/test/kotlin/maple/expectation/infrastructure/cache/like/InMemoryLikeBufferStorageTest.kt`

---

## Task 1: Update KDoc in `LikeBufferStrategy.kt`

**Files:**
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeBufferStrategy.kt:17-26`

- [ ] **Step 1: Read current KDoc lines 1-26 of the file**

Run: `Read module-core/src/main/kotlin/maple/expectation/core/port/out/LikeBufferStrategy.kt` (offset 1, limit 26)
Expected: KDoc block (lines 3-26) referencing `maple.expectation.service.v2.cache.LikeBufferStorage` (line 22) and `@see` (line 25), with "5-Agent Council Agreement" block at lines 10-17

- [ ] **Step 2: Replace the entire KDoc (lines 3-26) with a corrected version**

In `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeBufferStrategy.kt`, replace the entire KDoc block (lines 3-26) so the file becomes:

```kotlin
package maple.expectation.core.port.out

/**
 * Like Buffer Strategy Interface (#271 V5 Stateless Architecture)
 *
 * <p>Defines the strategy for buffering like increments. In-Memory (Caffeine) implementation.
 *
 * <h3>Implementations</h3>
 *
 * <ul>
 *   <li>maple.expectation.infrastructure.cache.like.InMemoryLikeBufferStorage - In-Memory Caffeine
 * </ul>
 */
interface LikeBufferStrategy {
```

(Removes: the "5-Agent Council Agreement" block at lines 10-17, the stale `maple.expectation.service.v2.cache.LikeBufferStorage` reference at line 22, and the redundant `@see` line 25. The interface body and rest of the file below line 27 are unchanged.)

- [ ] **Step 3: Verify file still compiles standalone**

Run: `./gradlew :module-core:compileKotlin --continue 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL` (or no Kotlin errors)

- [ ] **Step 4: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/LikeBufferStrategy.kt
git commit -m "docs(core): fix LikeBufferStrategy KDoc — remove nonexistent RedisLikeBufferStorage reference"
```

---

## Task 2: Delete 5 dead port files + empty directory

**Files:**
- Delete: `module-core/src/main/kotlin/maple/expectation/core/port/out/like/LikeAtomicFetchStrategy.kt`
- Delete: `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationBufferStrategy.kt`
- Delete: `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationSyncPort.kt`
- Delete: `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeSyncPort.kt`
- Delete: `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeEventPublisher.kt`
- Delete (directory): `module-core/src/main/kotlin/maple/expectation/core/port/out/like/`

- [ ] **Step 1: Delete the 5 files using git rm**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/like-port-merge-pr1
git rm module-core/src/main/kotlin/maple/expectation/core/port/out/like/LikeAtomicFetchStrategy.kt
git rm module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationBufferStrategy.kt
git rm module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationSyncPort.kt
git rm module-core/src/main/kotlin/maple/expectation/core/port/out/LikeSyncPort.kt
git rm module-core/src/main/kotlin/maple/expectation/core/port/out/LikeEventPublisher.kt
```

- [ ] **Step 2: Remove now-empty `like/` directory**

```bash
rmdir module-core/src/main/kotlin/maple/expectation/core/port/out/like/
```

If `rmdir` fails (directory not empty), investigate with `ls -la module-core/src/main/kotlin/maple/expectation/core/port/out/like/` and stop — something else lives there.

- [ ] **Step 3: Verify directory is gone**

Run: `ls module-core/src/main/kotlin/maple/expectation/core/port/out/`
Expected: list does NOT contain `like/`

- [ ] **Step 4: Stage directory removal if needed**

```bash
git add -u module-core/src/main/kotlin/maple/expectation/core/port/out/like/
```

(If `like/` was untracked, it disappears automatically; if it had been tracked, `git add -u` records the removal.)

- [ ] **Step 5: Commit**

```bash
git commit -m "refactor(core): delete 5 dead Like port hypothetical seams (#897, ADR-391)"
```

---

## Task 3: Update legacy test — remove `LikeEventPublisher`/`LikeEventSubscriber` fields

**Files:**
- Modify: `module-app/src/test-legacy/java/maple/expectation/service/v2/like/realtime/LikeRealtimeSyncIntegrationTest.java:53-57, 105-115`

- [ ] **Step 1: Remove the two `@Autowired(required = false)` fields (lines 53-57)**

In `module-app/src/test-legacy/java/maple/expectation/service/v2/like/realtime/LikeRealtimeSyncIntegrationTest.java`, delete the following 5 lines:

```java
  @Autowired(required = false)
  private LikeEventPublisher likeEventPublisher;

  @Autowired(required = false)
  private LikeEventSubscriber likeEventSubscriber;

```

(The blank line at end preserves spacing with the next `@BeforeEach` annotation.)

- [ ] **Step 2: Remove the imports for the deleted types**

In the import section of the same file, remove (or comment out for now) the import lines for `LikeEventPublisher` and `LikeEventSubscriber`. Find them via:

```bash
grep -n "LikeEventPublisher\|LikeEventSubscriber" module-app/src/test-legacy/java/maple/expectation/service/v2/like/realtime/LikeRealtimeSyncIntegrationTest.java
```

Delete the two matching `import maple.expectation.core.port.out.LikeEventPublisher;` and `import maple.expectation.core.port.out.LikeEventSubscriber;` lines.

- [ ] **Step 3: Remove the two bean-existence test methods (lines 105-115)**

Delete the following block (keep surrounding tests intact):

```java
  @Test
  @DisplayName("LikeEventPublisher Bean이 정상 생성됨")
  void shouldCreatePublisherBean() {
    assertThat(likeEventPublisher).isNotNull();
  }

  @Test
  @DisplayName("LikeEventSubscriber Bean이 정상 생성됨")
  void shouldCreateSubscriberBean() {
    assertThat(likeEventSubscriber).isNotNull();
  }

```

- [ ] **Step 4: Verify no other references in this test file**

Run: `grep -n "LikeEventPublisher\|LikeEventSubscriber" module-app/src/test-legacy/java/maple/expectation/service/v2/like/realtime/LikeRealtimeSyncIntegrationTest.java`
Expected: no output

- [ ] **Step 5: Verify test file compiles**

Run: `./gradlew :module-app:compileTestLegacyJava --continue 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL` (no Java errors). NOTE: This module is marked legacy, so the gradle task name may be `compileTestJava` — try both. If neither works, run full compile `./gradlew compileJava compileKotlin --continue 2>&1 | tail -10`.

- [ ] **Step 6: Commit**

```bash
git add module-app/src/test-legacy/java/maple/expectation/service/v2/like/realtime/LikeRealtimeSyncIntegrationTest.java
git commit -m "test(app): remove LikeEventPublisher/Subscriber assertions — ports deleted"
```

---

## Task 4: Final verification

- [ ] **Step 1: Run full compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run unit tests**

Run: `./gradlew test 2>&1 | tail -20`
Expected: no new failures. Existing failures (if any pre-existed) unchanged.

- [ ] **Step 3: Verify port count in module-core**

Run:
```bash
find module-core/src/main/kotlin/maple/expectation/core/port/out -name "*.kt" | sort
```
Expected: `LikeBufferStrategy.kt` present, none of the 5 removed files present.

- [ ] **Step 4: Verify the empty `like/` directory was removed**

Run: `ls module-core/src/main/kotlin/maple/expectation/core/port/out/`
Expected: output does not contain `like/`

- [ ] **Step 5: Verify no stale references anywhere**

Run:
```bash
grep -rln "LikeAtomicFetchStrategy\|LikeRelationBufferStrategy\|LikeRelationSyncPort\|LikeEventPublisher\|LikeEventSubscriber" --include="*.kt" --include="*.java" .
```
Expected: no output (all removed)

(Note: `LikeSyncPort` may still appear — confirm only in `LikeSyncPort` is gone, but `BufferedLike` annotation file may match. Use the broader regex above to catch the five port names + Subscriber. The surviving `LikeBufferStrategy` is intentional and not in the list.)

- [ ] **Step 6: Final commit (if any cleanup needed)**

If any cleanup was needed, commit it. Otherwise this step is a no-op.

---

## Task 5: Annotate active spec files (1-line note each)

> Historical reports (40+ `.md` files in `docs/05_Reports/`, `docs/09_Plans/`, etc.) that mention the removed ports are **left untouched** — they describe the system's *history at the time of writing*. Only the 3 active spec files describing this work get a 1-line note.

**Files:**
- Modify: `docs/superpowers/specs/2026-06-05-897-port-audit-design.md`
- Modify: `docs/superpowers/specs/2026-06-05-port-abstraction-cleanup-design.md`
- Modify: `docs/superpowers/plans/2026-06-05-897-port-audit.md`
- Modify: `docs/superpowers/plans/2026-06-05-port-abstraction-cleanup.md`

- [ ] **Step 1: Read each spec/plan file's header section to find insertion point**

For each of the 4 files, find the first heading or frontmatter line. The annotation goes at the very top, after the title block.

- [ ] **Step 2: Add annotation to all 4 files**

For each file, prepend the following note after the title/frontmatter (before any other content):

```markdown
> **Note (2026-06-06):** 5 dead Like ports (LikeAtomicFetchStrategy, LikeRelationBufferStrategy, LikeRelationSyncPort, LikeSyncPort, LikeEventPublisher) were deleted in PR #X. See [2026-06-06-like-port-merge-design.md](2026-06-06-like-port-merge-design.md) for the actual deletion rationale.
```

(Replace `#X` with the actual PR number once opened. If pre-PR, use `TBD`.)

- [ ] **Step 3: Verify no other active specs need updating**

Run:
```bash
grep -rln "LikeAtomicFetchStrategy\|LikeRelationBufferStrategy\|LikeRelationSyncPort\|LikeEventPublisher" docs/ --include="*.md"
```
Expected: only the 4 modified files appear (plus the new spec/plan we just created). All others are historical reports.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-06-05-897-port-audit-design.md \
        docs/superpowers/specs/2026-06-05-port-abstraction-cleanup-design.md \
        docs/superpowers/plans/2026-06-05-897-port-audit.md \
        docs/superpowers/plans/2026-06-05-port-abstraction-cleanup.md
git commit -m "docs: annotate 4 active specs — 5 Like ports deleted (see 2026-06-06-like-port-merge)"
```

---

## Task 6: Push branch and open PR

- [ ] **Step 1: Push branch**

```bash
git push -u origin refactor/like-port-merge-pr1
```

- [ ] **Step 2: Open PR against `develop`**

```bash
gh pr create --base develop --head refactor/like-port-merge-pr1 \
  --title "refactor(core): delete 5 dead Like port hypothetical seams (#897)" \
  --body "Closes #897 partial

Removes 5 unused outbound port interfaces in module-core (LikeAtomicFetchStrategy, LikeRelationBufferStrategy, LikeRelationSyncPort, LikeSyncPort, LikeEventPublisher + nested LikeEventSubscriber). Spec: docs/superpowers/specs/2026-06-06-like-port-merge-design.md.

Verification: only LikeBufferStrategy remains as a live port with one adapter (InMemoryLikeBufferStorage). All other Like ports were hypothetical seams with zero adapter implementations."
```

---

## Self-Review

### Spec coverage

| Spec section | Task |
|--------------|------|
| §3 Delete 5 port files | Task 2 |
| §3 Remove empty `like/` directory | Task 2 |
| §5 Fix KDoc in LikeBufferStrategy | Task 1 |
| §5 Update legacy test | Task 3 |
| §6 Test strategy (compile + test) | Task 4 |
| §7 Success signal (LOC, files) | Task 4 |
| §5 Push + PR | Task 5 |

### Placeholder scan

No "TBD", "TODO", "implement later". All steps show exact commands and exact code.

### Type consistency

* `LikeBufferStrategy` — name unchanged
* `InMemoryLikeBufferStorage` — name unchanged
* No method renames

### Gaps

None found.
