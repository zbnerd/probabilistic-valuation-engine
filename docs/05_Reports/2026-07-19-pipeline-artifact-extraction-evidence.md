# Pipeline Artifact Extraction Baseline Evidence

- Captured: 2026-07-20 (Europe/Berlin)
- Detached base/runtime subject: `a35809235de1f92cd7a7c546bd3bed060f62abab`
- Initial scaffold commit: `008c79bc5638f3cab3950341ed5c117b992d4004`
- Review-corrected measurement/harness capture revision: `b9b8810d1`
- Representation/style follow-up: the later commit containing this metadata only sanitizes committed endpoint text and adopts the approved Jackson builder; it does not rerun or alter the recorded raw measurements.
- Branch: `refactor/etl-infra-deepening`
- Purpose: preserve numeric dependency, executable-size, startup, LocalFS, and gzip baselines before artifact ownership moves out of `module-infra`.

## Measurement environment

| Property | Exact value |
| --- | --- |
| JDK | OpenJDK `21.0.11`, build `21.0.11+10-1-24.04.2-Ubuntu` |
| Gradle | `8.5` |
| OS | Linux `6.8.0-134-generic` amd64 |
| CPU | 8 vCPUs, `AMD EPYC Processor (with IBPB)`, 1 thread/core, full virtualization |
| Filesystem | `/dev/sda1`, `ext4`, mounted at `/` |
| Filesystem at final evidence capture | 414,921,494,528 bytes total; 265,480,073,216 used; 149,424,644,096 available (64% used) |

The benchmark worker heap is controlled by the narrow Gradle property `-PartifactEvidence`. Normal test workers retain the repository defaults (`-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxMetaspaceSize=512m`). Evidence workers replace only those defaults with:

```text
-Xms1g -Xmx1g -XX:+UseG1GC -XX:MaxMetaspaceSize=512m
```

Both final JSON reports record their complete ordered `RuntimeMXBean.inputArguments`. In addition to the four flags above, those lists contain the module-specific Gradle flaky-log and worker-temp system properties, `-Dorg.gradle.native=false`, the module-specific JaCoCo agent path, UTF-8/user-locale properties, and `-ea`. The tests reject the old `-Xms512m`/`-Xmx2048m` values.

```text
LocalFS inputArguments = [
  -Dflaky.log.dir=/home/maple/probabilistic-valuation-engine/.worktrees/etl-infra-deepening/module-infra/build/flaky,
  -Dflaky.logging.enabled=true,
  -Dorg.gradle.internal.worker.tmpdir=/home/maple/probabilistic-valuation-engine/.worktrees/etl-infra-deepening/module-infra/build/tmp/test/work,
  -Dorg.gradle.native=false,
  -XX:+UseG1GC,
  -XX:MaxMetaspaceSize=512m,
  -javaagent:/home/maple/probabilistic-valuation-engine/.worktrees/etl-infra-deepening/module-infra/build/tmp/.cache/expanded/zip_cde35f471dab581134460fc9a50e2c59/jacocoagent.jar=destfile=build/jacoco/test.exec,append=true,inclnolocationclasses=false,dumponexit=true,output=file,jmx=false,
  -Xms1g, -Xmx1g, -Dfile.encoding=UTF-8,
  -Duser.country, -Duser.language=en, -Duser.variant, -ea
]
Gzip inputArguments = [
  -Dflaky.log.dir=/home/maple/probabilistic-valuation-engine/.worktrees/etl-infra-deepening/module-external-api/build/flaky,
  -Dflaky.logging.enabled=true,
  -Dorg.gradle.internal.worker.tmpdir=/home/maple/probabilistic-valuation-engine/.worktrees/etl-infra-deepening/module-external-api/build/tmp/test/work,
  -Dorg.gradle.native=false,
  -XX:+UseG1GC,
  -XX:MaxMetaspaceSize=512m,
  -javaagent:/home/maple/probabilistic-valuation-engine/.worktrees/etl-infra-deepening/module-external-api/build/tmp/.cache/expanded/zip_cde35f471dab581134460fc9a50e2c59/jacocoagent.jar=destfile=build/jacoco/test.exec,append=true,inclnolocationclasses=false,dumponexit=true,output=file,jmx=false,
  -Xms1g, -Xmx1g, -Dfile.encoding=UTF-8,
  -Duser.country, -Duser.language=en, -Duser.variant, -ea
]
```

| Worker observation | LocalFS | Gzip |
| --- | ---: | ---: |
| `Runtime.totalMemory()` | 1,073,741,824 | 1,073,741,824 |
| `Runtime.maxMemory()` | 1,073,741,824 | 1,073,741,824 |
| `MemoryMXBean.heapMemoryUsage.init` | 1,073,741,824 | 1,073,741,824 |
| `MemoryMXBean.heapMemoryUsage.max` | 1,073,741,824 | 1,073,741,824 |

## Detached-base protocol

The baseline was rebuilt in `/tmp/artifact-base-worktree.jLjmmj`, a detached worktree at the exact commit above. The durable read-only measurement task is `gradle/artifact-runtime-classpath-metrics.init.gradle`; it rejects non-regular classpath entries, sorts every canonical entry, and prints its byte length plus a numeric total.

```bash
artifact_base_dir=$(mktemp -d /tmp/artifact-base-worktree.XXXXXX)
rmdir "$artifact_base_dir"
git worktree add --detach "$artifact_base_dir" a35809235de1f92cd7a7c546bd3bed060f62abab
cd "$artifact_base_dir"
./gradlew --no-daemon \
  :module-external-api:bootJar :module-calculator:bootJar \
  :module-synchronizer:bootJar :module-cleanup:bootJar
./gradlew --no-daemon \
  --init-script /home/maple/probabilistic-valuation-engine/.worktrees/etl-infra-deepening/gradle/artifact-runtime-classpath-metrics.init.gradle \
  :module-external-api:artifactRuntimeClasspathMetrics \
  :module-calculator:artifactRuntimeClasspathMetrics \
  :module-synchronizer:artifactRuntimeClasspathMetrics \
  :module-cleanup:artifactRuntimeClasspathMetrics \
  > /tmp/artifact-base-runtime-classpath.txt
```

- `bootJar`: exit `0`, `BUILD SUCCESSFUL in 51s`.
- Classpath task: exit `0`, `BUILD SUCCESSFUL in 27s`.
- Classpath output: `/tmp/artifact-base-runtime-classpath.txt`, 1,017 lines / 239,093 bytes, SHA-256 `857a7d969dec598784b0e8b452dfc5af86a30e9b9fcd63f78cc6bb9fd26e36e5`.
- The detached worktree was clean and was removed with `git worktree remove /tmp/artifact-base-worktree.jLjmmj`; the path was confirmed absent.

### Resolved runtime classpath and executable JARs

| Executable | Runtime entries | Runtime bytes | Boot JAR bytes | Boot JAR SHA-256 |
| --- | ---: | ---: | ---: | --- |
| `module-external-api` | 249 | 146,863,467 | 147,427,322 | `b5955122ada3d960331f6d14701bbdf3ddcac2e547441c82c9e93ff8f616604d` |
| `module-calculator` | 252 | 147,581,557 | 148,002,474 | `5b7a0f2646f35670851590289a5bc9b9da0adb5d92945d49db6cf6d406dae826` |
| `module-synchronizer` | 255 | 152,042,966 | 152,462,231 | `3840d38d5edd562336ff2a931c7290ae9cdbb5034b1449c09edddabe84497369` |
| `module-cleanup` | 242 | 145,089,789 | 145,352,802 | `db16c2bf39dbdb55fa6ba1af3940c0599dd4a349536f6aa570635eaefcbb95bd` |

The original dependency-tree captures remain available for structural diffing:

| Runtime classpath | Output | Lines | SHA-256 | Exit |
| --- | --- | ---: | --- | ---: |
| external-api | `/tmp/artifact-ext-runtime-before.txt` | 758 | `78bdbf75cd04a4a32e1b853f9895537f003ab1009fac43460399aec313821ed6` | 0 |
| calculator | `/tmp/artifact-calc-runtime-before.txt` | 771 | `fb59b54cd8b90c25e1d635b4f13b33f085c1355115dece55085874dee01f70d7` | 0 |
| synchronizer | `/tmp/artifact-sync-runtime-before.txt` | 776 | `11a4254a77045b3522e87a190748ea465e249de90d74875eff6a191e7445ebb5` | 0 |
| cleanup | `/tmp/artifact-cleanup-runtime-before.txt` | 736 | `246c761c1c29fb80068412b75727b4aaf39ae9d9b0a2361a2baf4728edd158ac` | 0 |

Commands were `./gradlew :<module>:dependencies --configuration runtimeClasspath > <output>`.

## Detached-base startup-to-health

The application JAR checks used the `runtime_closure_boot_check` function byte-for-byte from Task 6 Step 4 of `docs/superpowers/plans/2026-07-19-etl-runtime-ownership-closure.md`, invoked independently so one failure did not hide the remaining modules:

```bash
runtime_closure_boot_check module-external-api 8081
runtime_closure_boot_check module-calculator 8082
runtime_closure_boot_check module-synchronizer 8083
runtime_closure_boot_check module-cleanup 8084
```

The repository `.env` was sourced without printing values. Dependencies were brought up with `docker compose up -d postgres redis kafka minio minio-bootstrap`; PostgreSQL, Redis, Kafka, and MinIO were healthy. Because ADR-744 intentionally does not publish PostgreSQL, Redis, or the MinIO S3 port to the host, `DB_URL`, `REDIS_HOST`, and `MINIO_ENDPOINT` were pointed at the same running containers' bridge addresses. The production storage profile used `STORAGE_BACKEND=minio`, per-module `MINIO_ACCESS_KEY`, and `MINIO_SECRET_KEY_FILE` mode-0600 copies obtained from the already configured service-container secret mounts. No credential value was logged or committed. All four short-lived copies were unlinked and their exact temporary directory was removed.

The helper checked that ports 8081-8084 were free, launched each JAR directly, captured only its application PID, stored its complete health response, sent `TERM`, and escalated only that PID if necessary. No escalation was needed. All four ports were confirmed free afterward and no matching application PID remained.

### Production MinIO profile result

| Executable | Health | Startup | Shutdown | Wrapper elapsed | Command output |
| --- | --- | ---: | ---: | ---: | --- |
| external-api | `UP` | 30 s | 3 s | 33 s | `/tmp/artifact-base-runtime-boot-check-module-external-api-minio.txt` |
| calculator | `UP` | 32 s | 4 s | 36 s | `/tmp/artifact-base-runtime-boot-check-module-calculator-minio.txt` |
| synchronizer | `UP` | 34 s | 5 s | 39 s | `/tmp/artifact-base-runtime-boot-check-module-synchronizer-minio.txt` |
| cleanup | `UP` | 33 s | 6 s | 39 s | `/tmp/artifact-base-runtime-boot-check-module-cleanup-minio.txt` |

Sanitized committed health representation (the raw endpoint value is replaced by the environment placeholder `${MINIO_ENDPOINT}`):

```json
{"module-external-api":{"status":"UP","components":{"db":{"status":"UP","details":{"database":"H2","validationQuery":"isValid()"}},"diskSpace":{"status":"UP","details":{"total":414921494528,"free":148658278400,"threshold":10485760,"path":"/tmp/artifact-base-worktree.jLjmmj/.","exists":true}},"maple.expectation.infrastructure.storage.Minio":{"status":"UP","details":{"bucket":"maple-expectation","endpoint":"${MINIO_ENDPOINT}"}},"ping":{"status":"UP"},"ssl":{"status":"UP","details":{"validChains":[],"invalidChains":[]}}}}}
{"module-calculator":{"status":"UP","components":{"db":{"status":"UP","details":{"database":"H2","validationQuery":"isValid()"}},"diskSpace":{"status":"UP","details":{"total":414921494528,"free":148658036736,"threshold":10485760,"path":"/tmp/artifact-base-worktree.jLjmmj/.","exists":true}},"maple.expectation.infrastructure.storage.Minio":{"status":"UP","details":{"bucket":"maple-expectation","endpoint":"${MINIO_ENDPOINT}"}},"ping":{"status":"UP"},"ssl":{"status":"UP","details":{"validChains":[],"invalidChains":[]}}}}}
{"module-synchronizer":{"status":"UP","components":{"db":{"status":"UP","details":{"database":"PostgreSQL","validationQuery":"SELECT 1","result":1}},"diskSpace":{"status":"UP","details":{"total":414921494528,"free":148655898624,"threshold":10485760,"path":"/tmp/artifact-base-worktree.jLjmmj/.","exists":true}},"maple.expectation.infrastructure.storage.Minio":{"status":"UP","details":{"bucket":"maple-expectation","endpoint":"${MINIO_ENDPOINT}"}},"ping":{"status":"UP"},"redis":{"status":"UP","details":{"version":"7.4.9"}},"ssl":{"status":"UP","details":{"validChains":[],"invalidChains":[]}}}}}
{"module-cleanup":{"status":"UP","components":{"db":{"status":"UP","details":{"database":"H2","validationQuery":"isValid()"}},"diskSpace":{"status":"UP","details":{"total":414921494528,"free":148658008064,"threshold":10485760,"path":"/tmp/artifact-base-worktree.jLjmmj/.","exists":true}},"maple.expectation.infrastructure.storage.Minio":{"status":"UP","details":{"bucket":"maple-expectation","endpoint":"${MINIO_ENDPOINT}"}},"ping":{"status":"UP"},"ssl":{"status":"UP","details":{"validChains":[],"invalidChains":[]}}}}}
```

The health hashes below refer to the uncommitted raw capture files, not to the sanitized representation above.

| Executable | App log SHA-256 | Raw health JSON SHA-256 | Command output SHA-256 |
| --- | --- | --- | --- |
| external-api | `f33322df7fea07d66ba0a9614fd0764ef742ca099a884b87e481dccc06edfdab` | `ee446767783f14ecd0a65215cd8e0c9f79fb0934da0c02a4f1a7cf518a642312` | `de8c58fa0b7547bdd722155b6875ecc44d090d38fde231e0b4ead0815b4497a7` |
| calculator | `a35688d048cbbf25ffb6e81a42dcc1fa975508af0489ef5b8fb95406d468cf75` | `1a0df6418e2356162582843d5c7946ab7177a30c96078b78f5e804dcc8f5b9f6` | `2b2a0f9e4d282dfa1521064a27640114a88967b4f2ced3fee9d506701a22aa63` |
| synchronizer | `1a9859c74e3847794baaa03b00b4cc6ebbe094c2f3a6c256db2b7b63c630ae39` | `521b5c348936178c9415fde8d51f3d9740fe64b284f4a66bbe6b4b736c65945a` | `742b652202d416d42637bb083f49ee64f15ae3a3b7b59015696735750ad7d5c9` |
| cleanup | `1cd7c7fdbc7f9d95a52950114d03a182f8819fe7aa7b281ff46edea12f009a4e` | `8f790c4b7f70e9ebbb7e81b94ea8d010f160398288363eef245fae45a975abc0` | `75b9e13399e73db76ead6caa0d1390ad6304a67692228decfcb388b218cea167` |

Original in-worktree log paths were `logs/runtime-closure-<module>.log`; copied evidence paths are `/tmp/artifact-base-runtime-<module>-minio.log` and `...-minio-health.json`. The combined wrapper summary SHA-256 is `dacaad824afeb5c46303337b4f2e91fbe0335ca029c4d899209cc96e5dcce6fb`.

Strict log audit: calculator, synchronizer, and cleanup had zero matches for application failure, missing/duplicate bean, unresolved configuration, executor rejection, or shutdown timeout. External-api reached `UP` but emitted 10 pre-existing `RankingFetch` `RejectedExecutionException` warnings during shutdown; therefore its health result passes while the stricter no-executor-rejection log criterion does not.

### LocalFS profile observation

This additional profile exposed an honest pre-existing base condition rather than being used as the production baseline:

| Executable | Result | Elapsed | Cause / timing |
| --- | --- | ---: | --- |
| external-api | failed before health | 20 s | `localObjectStorage` has 9 unqualified `Executor` candidates |
| calculator | failed before health | 20 s | `localObjectStorage` has 2 unqualified `Executor` candidates |
| synchronizer | failed before health | 17 s | `localObjectStorage` has 7 unqualified `Executor` candidates |
| cleanup | `UP`, stopped cleanly | 26 s | startup 25 s / shutdown 1 s |

Local app-log SHA-256 values are external-api `f0cfbc7da3d2488eda169f1ff5718b2d7b7b14ccb7e196771946a2956c6253e6`, calculator `adb4ec02bde99f92b619a85fa30b9129ae7a1a381692e093db3460e491ddb750`, synchronizer `aa8f586f5a4ddf1df625b2ebd9b4e9fd436a1d546b1f9971d15047a7b8dee3b9`, and cleanup `c29855d49fab2ac5dcd82651af6c3bb2c3c5606ec7bb98a5df75f2dd8dc91b41`. Failed health files are empty (SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`); cleanup health JSON is `UP` with SHA-256 `d70d4bd70afb2b9143394051e88a0f79199976eee7136dbcc289aa46c08f505d`. Command-output SHA-256 values are `6e9dd983b9606ee27d763852308b0cf9be6fbcf6fcff4fa744cd1e83a2838dfe`, `854e3bf76d3f3318d9a08dfc0fbff5be7ddc1a820dbc3357f7903bfd06d17657`, `8e0c3f7ca67c3b5f022e50d6eef757cc7ff8da09e5343be97e71900f255258e6`, and `0ce1fdf119fd82f091831029302a6aef7e933986e53c79754caf94c95886b5ab`, in the table's order.

## Evidence timing and executor protocol

Each aggregate `CompletableFuture` installs a `whenComplete` continuation that captures both failure and `System.nanoTime()`. Awaitility observes only that captured state; measured elapsed time ends at the continuation's timestamp and excludes Awaitility polling delay. The evidence code contains no future `get`/`join`, `runBlocking`, `Thread.sleep`, or coroutine `delay`.

The LocalFS workload requires exact concurrency 8. Repository adapters were evaluated: `ExecutorSelector` uses registry pools whose configured IO pool is core 8 / max 16 with a queue and Spring lifecycle behavior, while `BoundedSemaphore` is suspend-only. Reusing either would change the workload or require forbidden coroutine bridging. The evidence therefore keeps a test-only `Executors.newFixedThreadPool(8)` exception inside `ExecutorService.use`, guaranteeing shutdown and changing no production convention.

## LocalFS object-write baseline

- Fixture: 1 MiB generated by `java.util.Random` with seed `745`.
- Fixture SHA-256: `c88226f5f1975eb82b420c3c4779ac0c186d9610b5473e7905c57ce9d1ce4550`.
- Protocol: 32 warmup objects, then 256 measured objects (256 MiB) at concurrency 8; five repetitions, each in a fresh JUnit-owned directory.

| Repetition | Elapsed ns | MiB/s |
| ---: | ---: | ---: |
| 1 | 1,172,962,214 | 218.25084980955745 |
| 2 | 693,836,252 | 368.9631368526417 |
| 3 | 670,372,114 | 381.877459777511 |
| 4 | 495,186,491 | 516.9769463682724 |
| 5 | 504,160,875 | 507.7744281525218 |
| **Median** | — | **381.877459777511** |

Machine-readable output: `module-infra/build/reports/artifact-evidence/local-fs-object-storage.json`; SHA-256 `120a29e93b75683d05c8e9a16a60ef50f0e68f77d3dd5e39c6df919431e013ee`.

## Gzip JSONL writer + LocalFS baseline

- Fixture: 10,000 deterministic valid JSON lines, exactly 1,024 bytes each (10,240,000 bytes/chunk).
- Fixture SHA-256: `515ff01635ebc40241e1f2c7626f68248c9617db155eb9898dc28884339a37df`.
- Storage: real, JUnit-owned `LocalFsObjectStorage`; compression level `1` (`Deflater.BEST_SPEED`).
- Protocol: 3 warmup chunks; 20 measured chunks per repetition; five repetitions, each in a fresh JUnit-owned directory.

| Rep | Elapsed ns | Records | Records/s | Compressed bytes | Compressed MiB/s | Temp before/after | Added / removed / delta |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 587,377,363 | 200,000 | 340496.60848097753 | 3,058,220 | 4.965369882529712 | 1 / 1 | 0 / 0 / 0 |
| 2 | 669,755,294 | 200,000 | 298616.5272476368 | 3,058,220 | 4.354643993183459 | 1 / 1 | 0 / 0 / 0 |
| 3 | 562,529,827 | 200,000 | 355536.70294535335 | 3,058,220 | 5.184695509345715 | 1 / 1 | 0 / 0 / 0 |
| 4 | 730,144,064 | 200,000 | 273918.54547762236 | 3,058,220 | 3.9944800097969733 | 1 / 1 | 0 / 0 / 0 |
| 5 | 622,074,256 | 200,000 | 321505.02624239767 | 3,058,220 | 4.688420779013755 | 1 / 1 | 0 / 0 / 0 |
| **Median** | — | — | **321505.02624239767** | — | **4.688420779013755** | — | — |

Warmup also observed 1 matching path before/after and zero added, removed, or net change. The test snapshots actual matching path sets before/after every phase and asserts both set differences are empty. JSON includes only the deterministic empty added/removed sets plus counts/delta; it does not serialize ambient path names.

Machine-readable output: `module-external-api/build/reports/artifact-evidence/gzip-jsonl-chunk-writer.json`; SHA-256 `c2e89edddeef7a5f8af95f1e5d371fc2d262f053d87ec603e886b003d5dd2c6e`.

## Required fresh-worker commands

```bash
ARTIFACT_EVIDENCE_ENABLED=1 \
./gradlew --no-daemon -PartifactEvidence :module-infra:test \
  --tests '*LocalFsObjectStorageTest' --rerun-tasks

ARTIFACT_EVIDENCE_ENABLED=1 \
./gradlew --no-daemon -PartifactEvidence :module-external-api:test \
  --tests '*GzipJsonlChunkWriterTest' --rerun-tasks

sha256sum \
  module-infra/build/reports/artifact-evidence/local-fs-object-storage.json \
  module-external-api/build/reports/artifact-evidence/gzip-jsonl-chunk-writer.json
```

- LocalFS: exit `0`, 15 tests passed, `BUILD SUCCESSFUL in 4m 3s`; captured output `/tmp/artifact-evidence-localfs-final.txt`, SHA-256 `e6c404943088dc33751ff457c5eb767d1ae171cf5302aa756b74ca9f9e0f2b99`.
- Gzip: exit `0`, 4 tests passed, `BUILD SUCCESSFUL in 3m 46s`; captured output `/tmp/artifact-evidence-gzip-final.txt`, SHA-256 `43d05b1fb069327d5bf377d403699a36c09dd54aeebdc1d127322752f852fe02`.
- `jq -c .` parsed both JSON reports; `sha256sum` exited `0`.
- Existing compiler warnings were outside the changed evidence code; no timing threshold is asserted.

## Optional real-MinIO throughput baseline

`INTEGRATION_MINIO` was unset. The existing `INTEGRATION_MINIO=true` throughput gate was therefore disabled, so real-MinIO **throughput** was not measured or estimated. This is separate from the successful MinIO-backed executable health checks above. When enabled, the throughput gate must reuse the 1-MiB fixture, 32/256 object counts, concurrency 8, and five repetitions.

## Empty-boundary verification

```bash
./gradlew :module-pipeline-artifact:compileKotlin :module-pipeline-artifact:compileJava --continue
./gradlew --no-daemon :module-pipeline-artifact:dependencies --configuration runtimeClasspath > /tmp/artifact-module-runtime-after-final.txt
rg -q "project :module-common" /tmp/artifact-module-runtime-after-final.txt && ! rg -q "project :module-infra" /tmp/artifact-module-runtime-after-final.txt
```

- Compile: exit `0`, `BUILD SUCCESSFUL in 26s`; both module compile tasks correctly reported `NO-SOURCE`. Captured output SHA-256: `8c69aebeabb4814c3312cfd3e9e23c03249f2c5f2cd956d380eb61f9e37bc4b3`.
- Dependency output: `/tmp/artifact-module-runtime-after-final.txt`, 353 lines, `BUILD SUCCESSFUL in 21s`, SHA-256 `8d1e7eda179cc840d29b823eda7bedccb22424d67a9efe843e6840c8ebd167c0`.
- Boundary assertion: exit `0`; line 13 contains `project :module-common` and `module-infra` is absent.

## Task 8 direct active-service wiring

- Captured: 2026-07-20 (Europe/Berlin).
- Task base: `96fb655953cec2a3b211e08a8384ee0e65826241`.
- The four active executable modules already had direct `module-pipeline-artifact` dependencies at the task base. Task 8 retained those dependencies and each unrelated `module-infra` dependency.
- `ExternalApiApplication`, `CalculatorApplication`, `SynchronizerApplication`, and `CleanupApplication` now import `ArtifactStorageAutoConfiguration` directly. Their storage wiring no longer imports `maple.expectation.infrastructure.storage`; unrelated executor, Kafka, Nexon, and lifecycle infra imports remain.
- `ArtifactIdentitySourceGuardTest` scans the four active `src/main` trees. It rejects the legacy storage package anywhere in supported production text sources and rejects quoted raw artifact key/prefix/marker fragments while excluding production comments from the literal scan.

### Focused verification performed

```bash
./gradlew :module-pipeline-artifact:test --tests '*ArtifactIdentitySourceGuardTest'

./gradlew --continue \
  :module-external-api:compileKotlin :module-external-api:bootJar \
  :module-calculator:compileKotlin :module-calculator:bootJar \
  :module-synchronizer:compileKotlin :module-synchronizer:bootJar \
  :module-cleanup:compileKotlin :module-cleanup:bootJar
```

- Focused source guard: exit `0`; 1/1 test passed; `BUILD SUCCESSFUL in 16s`.
- Four-module compile/packaging: exit `0`; all four `compileKotlin` and `bootJar` tasks completed; `BUILD SUCCESSFUL in 23s`.
- `module-common:generateAvroJava` ran as an up-to-date dependency in both invocations, so no separate generation command was needed.
- The final scoped `rg` found all four direct artifact dependencies/imports and no legacy storage-package match in the active production trees; `git diff --check` exited `0`.
- The compile emitted two pre-existing `SnapshotChunkPipeline.workerCount` deprecation warnings and the repository's existing Java installation-discovery warning; neither affected the successful build.

### Verification intentionally skipped by user speed override

- The Task 8 full affected-module test matrix and the repository-wide compile/test matrix were not run.
- Runtime boot/health checks on ports 8081-8084 were not run; the successful `bootJar` build is not represented as runtime-health evidence.
- After-migration runtime-classpath sizes, executable JAR sizes/hashes, and startup timing were not recaptured.
- LocalFS/gzip and real-MinIO throughput, injected-upload-failure temp-file measurements, and before/after evidence JSON hashes were not recaptured.
- No broad edge-discovery or additional reviewer pass was performed.
