# Nexon Access Consolidation Evidence

## Verification protocol

- Base commit: `ebc1c565199b4d61086caabda14381aab90d5f60`
- JDK: repository JDK 21 toolchain
- Network: deterministic local/static fixtures only; no live Nexon calls
- Approved ceiling: focused planned tests per task, one final affected-module ordered compile, source and secret guards
- Intentionally skipped: evidence load corpus, runtime boot smoke, performance/load matrices, Docker, and live-network checks

## Before characterization

| Contract | Frozen baseline |
| --- | --- |
| Encoding | `DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY` |
| System endpoints | `/maplestory/v1/id`, `/character/basic`, `/character/item-equipment`, `/ranking/overall` |
| Query names | `character_name`, `ocid`, `date`, `page` |
| Headers | one request-scoped `x-nxopen-api-key`; `Accept: application/json` |
| System profile | pool `nexon-pool`, max 250, pending 1,000/5 s, connect 3 s, response 5 s, call 10 s, body 2 MiB |
| BYOK baseline | unpooled client, connect 3 s, response 5 s, facade block 5 s |
| Known defects | raw URI metric mapping, raw error-body logging, 4xx/transient/empty-result collapse |

Throughput, latency percentiles, provider gauges, and JSON evidence hashes were not collected under the approved speed override. No production credential or response body was read or emitted.

## After consolidation

| Contract | Consolidated result |
| --- | --- |
| Construction | One base URL and one Reactor Netty/WebClient construction path in `module-nexon-client` |
| Profiles | Independent bounded `SYSTEM_BULK` and `USER_BYOK` providers with deterministic shared-module lifecycle ownership |
| Active external-api | Direct `SystemKeyNexonClient`/`ByokNexonClient` use; no infra Nexon configuration or client import |
| Infra compatibility | Legacy bean names, client interfaces, DTOs, and OCID not-found app exception retained through thin delegates |
| Auth outcomes | Credential/not-found/invalid request map to the legacy empty contract; timeout/429/5xx/cap/decode remain typed failures |
| Security | No raw error-body logging, URI-query metric mapping, `LogicExecutor` defaulting, or stored BYOK credential |

## Focused verification

- Infra compatibility and post-migration characterization: 11/11 passed.
- Deterministic Nexon transport/BYOK/redaction slice: 8/8 passed. A parallel cross-module run briefly caused two local-stub call timeouts under host contention; the isolated prescribed BYOK rerun passed 3/3.
- External auth handler/subscription slice: 10/10 passed.
- App `ApiKeyValidator` compatibility: 4/4 passed.
- Task-level focused suites across Tasks 1-7 cover static characterization, failure taxonomy, profile isolation, system/BYOK clients, credential redaction, send-before-success, and legacy mapping.

The source-only architecture guard resolves the Nexon base URL, `ConnectionProvider.builder`, and `DefaultUriBuilderFactory` only in `module-nexon-client`. External-api has no `MaplestoryApiConfig` or infra external-client import, and the legacy auth interface has no `validateApiKey` caller or declaration. Diff whitespace checks are clean.

Runtime health/startup, Docker dependencies, live Nexon traffic, load/performance matrices, and before/after latency or throughput medians remained intentionally unexecuted under the approved verification ceiling. Consequently, no performance comparison is claimed; the deterministic compatibility and security evidence above is the completion evidence for this run.
