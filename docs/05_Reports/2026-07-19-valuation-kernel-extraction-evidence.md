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

To be completed from the focused parity/source guards allowed at Task 8. Runtime and performance matrices remain skipped unless the verification ceiling changes.
