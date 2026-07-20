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
