# Valuation Kernel Extraction Evidence

## Baseline

- Base commit: `0ff9666b9f1ca4e4f2eaeb5070d6b71332c8375a`
- Captured: 2026-07-20
- JDK: OpenJDK `21.0.11+10-1-24.04.2-Ubuntu`
- CPU: 8-vCPU AMD EPYC Processor (KVM, x86_64)
- JVM flags requested by the full evidence protocol: `-Xms1g -Xmx1g -XX:+UseG1GC`

### Probability resource

| Resource | SHA-256 | Lines | Data rows |
| --- | --- | ---: | ---: |
| `module-infra/src/main/resources/data/cube_probability.csv` | `9a329fe4b861c9f21b69d766e1847e59982c2a02ea4f30c6e5b332a7f2e955c0` | 413,803 | 413,802 |
| `module-calculator/src/main/resources/data/cube_probability.csv` | Not present at baseline | — | — |

The calculator copy assumed by the plan does not exist at the clean base commit. Task 5 will add it with the local loader and prove byte identity against the infra source. No baseline value is fabricated for the missing file.

### Frozen output corpus

- Fixture: `module-infra/src/test/resources/golden/valuation-kernel-v1-cases.json`
- Cases: 26
- Fixture SHA-256: `4eb178a35b04a29157a3464f3f139124f80f6c9fb02bb14a21ecd5ee21ccc8c2`
- Coverage: absent/potential/additional/starforce/all components; BLACK/ADDITIONAL/starforce public entry points; four grades; levels 0/100/150/200/250; regular and Noljang star boundaries; secondary normalization; option order; all-stat; compound fallback; invalid grade.
- Focused command: `./gradlew :module-infra:test --tests '*LegacyValuationGoldenMasterTest'`
- Result: exit `0`; 1 golden assertion test passed, evidence-only test skipped as designed.
- Loaded table evidence from the focused test: 413,802 rows and 18,072 indexed keys.

### Deliberate verification ceiling

The user speed override permits only quick golden/hash baseline fixtures and explicitly skips long benchmarks/runtime work. Therefore the following Task 1 plan commands were not run:

- calculator runtimeClasspath dump;
- calculator bootJar size capture;
- 25/250/five-repetition legacy performance and allocation run;
- runtime startup timing.

The evidence-only test is committed and remains opt-in through `VALUATION_EVIDENCE_ENABLED=1`; no throughput, allocation, table-load-duration, boot-size, or startup number is reported without an actual run.

## After extraction

### Frozen compatibility and direct-core parity

- The infra and calculator CSV resources remain byte-identical at SHA-256 `9a329fe4b861c9f21b69d766e1847e59982c2a02ea4f30c6e5b332a7f2e955c0`, with 413,802 data rows.
- `CoreLegacyValuationParityTest` runs every one of the 26 frozen cases through both the stable V4 factory facade and the direct core kernel. Costs, rounded public trials, totals, enhance paths, table logical version, and error types agree with the frozen corpus outside the two explicit V1 permutation-compatibility cases below.
- `full-all-stat-contribution`: the stable facade retains the frozen V1 zero cost/trials; the corrected slot-specific core result has `136755000000` black-cube cost and 3,039 rounded trials.
- `full-compound-option-permutation-fallback`: the stable facade retains the frozen V1 zero cost/trials; the direct core result exposes positive-infinity raw trials (projecting to `Long.MAX_VALUE` rounded trials and `4.150517416584649E26` cost). This difference is isolated in the compatibility facade and does not weaken the standalone core permutation contract.
- Unsupported potential grades retain `InvalidPotentialGradeException`: the core valuation path validates cost policy before probability lookup, avoiding an accidental `MissingProbabilityException` drift.
- The V4 factory FQN and four public methods, repository FQN and `cubeProbabilityRepositoryV1` bean name, and `CalculatorEngineAutoConfiguration` bean remain present. The compatibility auto-configuration no longer creates `CoreExecutorConfig`.

Focused verification:

- `./gradlew :module-infra:test --tests '*CoreLegacyValuationParityTest' --tests '*LegacyValuationGoldenMasterTest'` — exit `0`; three assertion tests passed and the opt-in evidence method skipped as designed.
- `./gradlew :module-app:test --tests '*CubeServiceTest'` — exit `0`; 8/8 focused V1/V2 cube-service compatibility tests passed.
- `./gradlew :module-core:test --tests '*ValuationKernelTest' --tests '*ValuationKernelPropertyTest'` — exit `0`; 8/8 focused full-kernel tests passed.
- `./gradlew :module-calculator:dependencyInsight --dependency module-infra --configuration runtimeClasspath` — exit `0`; no matching dependency.
- The single final ordered core → infra → calculator → app Kotlin/Java compile exited `0` with no compilation errors.

### Performance/runtime evidence ceiling

The calculator evidence harness now records both direct-kernel and preloaded zero-miss cache-hit 25/250/five-repetition phases, but it was not executed. bootJar sizing, runtimeClasspath capture beyond the dependency guard, calculator startup/health, live chunk execution, allocation/throughput comparison, and application startup timing remain intentionally unmeasured under the approved speed override. No benchmark or runtime number is fabricated.
