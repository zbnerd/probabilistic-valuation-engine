# Direct Buffer Tuning (Netty Arenas + Kafka Buffer Memory) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tune Netty direct buffer arena count and Kafka client buffer memory to fit within the 512MB direct memory cap from Phase 1 (#1310), reducing RSS by 100-200MB per module.

**Architecture:** Config-only change. Gradle property drives Netty JVM arg. YAML env var drives Kafka buffer. Per-environment override without rebuild. No code, no test impact.

**Tech Stack:** Gradle (Groovy DSL), Spring Boot Kafka auto-config, Reactor Netty (ext-api only).

**Supersedes:** Task 5 of `docs/superpowers/plans/2026-06-19-offheap-streaming.md` — the original Task 5 used literal values and a `-Dbuffer.memory` JVM arg; this plan uses gradle property + YAML env var per the approved spec.

**Spec:** `docs/superpowers/specs/2026-06-19-issue-1314-direct-buffer-tuning-design.md`

---

## File Structure

| File | Change | Responsibility |
|------|--------|----------------|
| `gradle.properties` (root) | Append 1 line | Default `netty.numDirectArenas=4` |
| `module-external-api/build.gradle` | Edit `jvmArgs` list (lines 64-68) | Add Netty arena JVM arg with `${project.findProperty(...)}` |
| `module-external-api/src/main/resources/application.yml` | Extend `spring.kafka.producer` (lines 15-19) | Add `properties.buffer.memory` |
| `module-calculator/src/main/resources/application.yml` | Insert new `spring.kafka.producer` block before `listener:` (line 21) | Add `properties.buffer.memory` (consumer side untouched — producer-only config) |

Single atomic commit at end. All 4 files together — they form one cohesive tuning change.

---

## Task 1: Apply Tuning + Verify Build

**Files:**
- Modify: `gradle.properties` (root)
- Modify: `module-external-api/build.gradle` (lines 64-68)
- Modify: `module-external-api/src/main/resources/application.yml` (lines 15-19)
- Modify: `module-calculator/src/main/resources/application.yml` (lines 13-20 + new block)

- [ ] **Step 1.1: Read each file to confirm current state**

```bash
grep -n "jvmArgs" module-external-api/build.gradle
grep -n "kafka\|producer\|consumer" module-external-api/src/main/resources/application.yml | head -10
grep -n "kafka\|producer\|consumer" module-calculator/src/main/resources/application.yml | head -10
test -f gradle.properties && cat gradle.properties || echo "no root gradle.properties"
```

Expected output:
- ext-api: `tasks.named("bootRun") { jvmArgs = [ "-XX:MaxDirectMemorySize=512m", ] }` block visible
- ext-api yaml: `producer:` at line 15 with `key-serializer`, `value-serializer`, `acks`, `retries`
- calculator yaml: `consumer:` at line 13 with `group-id`, deserializers, `auto-offset-reset`, etc. No existing `producer:` block.
- gradle.properties: may not exist yet — that's fine, will create.

- [ ] **Step 1.2: Add gradle property default**

If `gradle.properties` exists at repo root, append the line. If it doesn't exist, create it.

```bash
test -f gradle.properties && echo "" >> gradle.properties
echo "# Netty direct buffer arena count (issue #1314). Default 4 (cores/2 on 8-core)." >> gradle.properties
echo "# Override per env: ./gradlew :module-external-api:bootRun -Pnetty.numDirectArenas=8" >> gradle.properties
echo "netty.numDirectArenas=4" >> gradle.properties
```

Verify:

```bash
tail -3 gradle.properties
```

Expected last 3 lines:
```
# Netty direct buffer arena count (issue #1314). Default 4 (cores/2 on 8-core).
# Override per env: ./gradlew :module-external-api:bootRun -Pnetty.numDirectArenas=8
netty.numDirectArenas=4
```

- [ ] **Step 1.3: Add Netty arena JVM arg to ext-api build.gradle**

In `module-external-api/build.gradle`, replace the existing `jvmArgs` block (lines 64-68):

```groovy
tasks.named("bootRun") {
    jvmArgs = [
        "-XX:MaxDirectMemorySize=512m",
        providers.gradleProperty("netty.numDirectArenas")
            .orElse("4")
            .map { "-Dio.netty.allocator.numDirectArenas=$it" }
            .get(),
    ]
}
```

Use `providers.gradleProperty()` (lazy Provider chain) instead of GString `${project.findProperty(...)}` (which can be eagerly evaluated at configuration time, ignoring `-P` overrides at task-execution). Use the Edit tool with the exact 5-line block as `old_string` and the 8-line block above as `new_string`.

Verify:

```bash
grep -A7 'tasks.named("bootRun")' module-external-api/build.gradle
```

Expected:
```
tasks.named("bootRun") {
    jvmArgs = [
        "-XX:MaxDirectMemorySize=512m",
        providers.gradleProperty("netty.numDirectArenas")
            .orElse("4")
            .map { "-Dio.netty.allocator.numDirectArenas=$it" }
            .get(),
    ]
}
```

- [ ] **Step 1.4: Add Kafka producer buffer.memory to ext-api application.yml**

In `module-external-api/src/main/resources/application.yml`, the `spring.kafka.producer` block currently is (lines 15-19):

```yaml
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      retries: 3
```

Add a `properties:` sub-key after `retries: 3`. Use Edit with the exact 5-line block as `old_string` and the new 7-line block as `new_string`:

```yaml
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      retries: 3
      properties:
        buffer.memory: ${KAFKA_BUFFER_MEMORY:67108864}  # 64MB, up from default 32MB (issue #1314)
```

Verify:

```bash
grep -A8 '    producer:' module-external-api/src/main/resources/application.yml
```

Expected: `properties:` line followed by `buffer.memory: ${KAFKA_BUFFER_MEMORY:67108864}` with 8-space indent under `properties:`.

- [ ] **Step 1.5: Add Kafka producer block to calculator application.yml**

`buffer.memory` is a producer-only Kafka client config. Do NOT add it to `spring.kafka.consumer.properties` — Kafka client silently ignores unknown keys. Only add the producer block.

In `module-calculator/src/main/resources/application.yml`, find the line number for the `listener:` block:

```bash
grep -n "listener:" module-calculator/src/main/resources/application.yml
```

Expected: line 21 (or close — verify before editing).

Make 1 edit — add a new `spring.kafka.producer` block immediately before the `listener:` block. Insert the 6-line block below, with 4-space indent for `producer:`, 6-space for the comment, 6-space for `properties:`, and 8-space for the actual setting:

```yaml
    producer:
      # Minimal block: only override buffer.memory. Spring Boot auto-config
      # keeps StringSerializer (matches KafkaTemplate<String, String>), acks=1,
      # retries=0, etc. — preserving current producer behavior.
      properties:
        buffer.memory: ${KAFKA_BUFFER_MEMORY:67108864}  # 64MB (issue #1314)
```

Verify the full `spring.kafka:` section:

```bash
sed -n '11,32p' module-calculator/src/main/resources/application.yml
```

Expected: `consumer:` block is unchanged (no `properties:` sub-key). New `producer:` block exists with only `properties.buffer.memory`. `listener:` block follows unchanged.

- [ ] **Step 1.6: Verify build succeeds**

```bash
./gradlew :module-external-api:compileKotlin :module-calculator:compileKotlin --continue 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. The `--continue` ensures both modules compile even if one fails. Only error lines should print; success is silent.

```bash
./gradlew :module-external-api:bootJar :module-calculator:bootJar 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. Both `bootJar` tasks complete. This validates:
- Groovy interpolation in build.gradle (`${project.findProperty(...)}`) resolves
- YAML structure is parseable
- Spring Boot can read the kafka producer/consumer config

- [ ] **Step 1.7: Verify gradle property override works**

```bash
./gradlew :module-external-api:tasks --quiet 2>&1 | head -5
./gradlew :module-external-api:help --task bootRun -Pnetty.numDirectArenas=8 2>&1 | tail -3
```

Expected: tasks list renders, bootRun help renders. The override syntax works (no "unknown property" error). Note: this only validates the property is read; the actual JVM arg value is logged at bootRun time, not at task help time.

To confirm the JVM arg is actually applied at boot, do a dry run with `--dry-run` or print args:

```bash
./gradlew :module-external-api:bootRun --dry-run 2>&1 | grep -E "numDirectArenas|MaxDirectMemory"
```

If `--dry-run` doesn't show JVM args (it varies by Gradle version), skip this verification. The build success in Step 1.6 is the primary check.

- [ ] **Step 1.8: Verify YAML env var default works**

```bash
unset KAFKA_BUFFER_MEMORY
./gradlew :module-external-api:processResources 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`. Spring's `${KAFKA_BUFFER_MEMORY:67108864}` resolves to `67108864` when env var is unset. The `processResources` task expands placeholders.

Inspect the generated resource:

```bash
grep -A1 "buffer.memory" module-external-api/build/resources/main/application.yml
```

Expected: `buffer.memory: 67108864` (env var was unset, so default applied).

- [ ] **Step 1.9: Commit**

```bash
git add gradle.properties \
        module-external-api/build.gradle \
        module-external-api/src/main/resources/application.yml \
        module-calculator/src/main/resources/application.yml
git commit -m "perf(pipeline): tune Netty/Kafka direct buffer pools (issue #1314)

Phase 5 of off-heap streaming plan. Netty: numDirectArenas=4 (cores/2
on 8-core, gradle property override per env). Kafka: buffer.memory=64MB
producer+consumer (down from default 256MB), env var override.

Combined with Phase 1 cap (-XX:MaxDirectMemorySize=512m from #1310),
targets RSS <500MB per module.

Config-only: no code or test changes. Per yaml-config rule: merged
into existing spring: blocks, no duplicate root keys. Calculator
spring.kafka.producer is minimal (properties.buffer.memory only) to
preserve Spring Boot auto-config defaults.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

Expected: commit created with 4 files changed, message shown in `git log --oneline -1`.

- [ ] **Step 1.10: Verify commit and final state**

```bash
git log --oneline -1
git show --stat HEAD | head -10
```

Expected: top commit is the perf(pipeline) commit. 4 files changed: `gradle.properties`, `module-external-api/build.gradle`, both `application.yml` files.

---

## Self-Review

**Spec coverage:**
- §3.1 Configuration layer → Task 1 Steps 1.2-1.5 ✓
- §3.2 Module split → Step 1.3 (ext-api only for Netty), Steps 1.4-1.5 (both for Kafka) ✓
- §4.1 build.gradle → Step 1.3 ✓
- §4.2 gradle.properties → Step 1.2 ✓
- §4.3 ext-api application.yml → Step 1.4 ✓
- §4.4 calculator application.yml → Step 1.5 (2 edits: consumer extension + new producer block) ✓
- §5 Configuration surface → Step 1.7 (gradle override), Step 1.8 (env var override) ✓
- §6 Error handling — `findProperty` elvis default + Spring `${VAR:default}` — implicit in Steps 1.2-1.5 ✓
- §7 Verification (build) → Steps 1.6-1.8 ✓
- §10 Rollback — single commit, `git revert` works — implicit in Step 1.9 ✓

**Placeholder scan:** No TBD/TODO. Every step has exact commands or file content.

**Type consistency:** Property name `netty.numDirectArenas` appears in Step 1.2 (gradle.properties) and Step 1.3 (build.gradle interpolation). Env var `KAFKA_BUFFER_MEMORY` appears in Steps 1.4-1.5 (yaml) and 1.8 (test).

**Build-conventions check:** Plain JAR pattern preserved. No new dependencies. No code change. Gradle property follows `findProperty` pattern (Kotlin DSL has same pattern).

**yaml-config check:** All edits merge into existing `spring:` block. No new root key. 2-space indent verified. env var pattern matches existing `NEXON_HTTP_*` style.
