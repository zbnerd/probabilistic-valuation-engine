# Prometheus Metrics 참조

모듈별 Prometheus 메트릭 엔드포인트:
- External API: `http://localhost:8081/actuator/prometheus`
- Calculator: `http://localhost:8082/actuator/prometheus`

## External API 메트릭

### Counter (누적)
| 메트릭명 | 설명 |
|----------|------|
| `external_api_users_fetched_total` | 전체 유저 fetch 수 |
| `external_api_users_failed_total` | 전체 유저 실패 수 |
| `external_api_character_basic_fetched_total` | CHARACTER_BASIC 성공 수 |
| `external_api_character_basic_failed_total` | CHARACTER_BASIC 실패 수 |
| `external_api_item_equipment_fetched_total` | ITEM_EQUIPMENT 성공 수 |
| `external_api_item_equipment_failed_total` | ITEM_EQUIPMENT 실패 수 |
| `external_api_chunks_total` | 생성된 chunk 수 |

### Timer (시간)
| 메트릭명 | 설명 |
|----------|------|
| `external_api_character_basic_duration_seconds` | CHARACTER_BASIC 전체 소요 시간 |
| `external_api_item_equipment_duration_seconds` | ITEM_EQUIPMENT 전체 소요 시간 |
| `external_api_lookup_duration_seconds` | Lookup 전체 소요 시간 |

### 초당 처리율 쿼리
```bash
# 초당 fetch 수
curl -s http://localhost:8081/actuator/prometheus | grep "external_api_item_equipment_fetched_total" | grep -v "^#"

# Prometheus rate 쿼리
rate(external_api_item_equipment_fetched_total{application="external-api"}[1m])
```

## Calculator 메트릭

### Counter (누적)
| 메트릭명 | 설명 |
|----------|------|
| `calculator_chunks_processed_total` | 처리 완료 chunk 수 |
| `calculator_chunks_failed_total` | 실패 chunk 수 |
| `calculator_chunks_skipped_total` | 스킵 chunk 수 (reason 태그: endpoint_mismatch, source_not_found, result_exists) |
| `calculator_users_processed_total` | 처리 유저 수 |
| `calculator_items_processed_total` | 처리 아이템 수 |
| `calculator_items_calculated_total` | 계산 완료 아이템 수 |
| `calculator_items_errored_total` | 계산 에러 아이템 수 |

### Timer (시간)
| 메트릭명 | 설명 |
|----------|------|
| `calculator_chunk_duration_seconds` | chunk 처리 시간 (avg, max, count) |

### Gauge (실시간)
| 메트릭명 | 설명 |
|----------|------|
| `calculator_chunk_users_per_second` | 마지막 완료 chunk의 초당 유저 처리율 |
| `calculator_chunk_items_per_second` | 마지막 완료 chunk의 초당 아이템 처리율 |

### 초당 처리율 쿼리
```bash
# 실시간 처리율
curl -s http://localhost:8082/actuator/prometheus | grep -E "calculator_chunk_(users|items)_per_second" | grep -v "^#"

# Prometheus rate 쿼리
rate(calculator_items_calculated_total{application="calculator"}[1m])
rate(calculator_users_processed_total{application="calculator"}[1m])
```

## JVM & System 메트릭 (Spring Boot Actuator 자동 수집)

### Memory
| 메트릭명 | 설명 |
|----------|------|
| `jvm_memory_used_bytes` | JVM 메모리 사용량 (area 태그: heap, non_heap) |
| `jvm_memory_max_bytes` | JVM 메모리 최대치 |
| `jvm_memory_committed_bytes` | JVM 메모리 커밋량 |

### GC
| 메트릭명 | 설명 |
|----------|------|
| `jvm_gc_pause_seconds` | GC pause 시간 (action 태그: major/minor) |
| `jvm_gc_memory_promoted_bytes_total` | GC로 old generation 승격 바이트 |
| `jvm_gc_max_data_size_bytes` | Old generation 최대 크기 |

### CPU & Threads
| 메트릭명 | 설명 |
|----------|------|
| `process_cpu_usage` | 프로세스 CPU 사용률 (0~1) |
| `system_cpu_usage` | 시스템 전체 CPU 사용률 (0~1) |
| `system_cpu_count` | CPU 코어 수 |
| `jvm_threads_live_threads` | 활성 스레드 수 |
| `jvm_threads_states_threads` | 스레드 상태별 수 (state 태그) |

### Process & Disk
| 메트릭명 | 설명 |
|----------|------|
| `process_uptime_seconds` | 프로세스 가동 시간 |
| `disk_free_bytes` | 디스크 여유 공간 |
| `disk_total_bytes` | 디스크 전체 공간 |

### 쿼리 예시
```bash
# Heap 메모리 사용량
curl -s http://localhost:8082/actuator/prometheus | grep 'jvm_memory_used_bytes.*area="heap"' | grep -v "^#"

# GC pause
curl -s http://localhost:8082/actuator/prometheus | grep "jvm_gc_pause_seconds_count" | grep -v "^#"

# CPU 사용률
curl -s http://localhost:8082/actuator/prometheus | grep "process_cpu_usage" | grep -v "^#"

# 스레드 수
curl -s http://localhost:8082/actuator/prometheus | grep "jvm_threads_live_threads" | grep -v "^#"
```

## Grafana 대시보드

`grafana/dashboard-pipeline.json` — 위 메트릭 전체를 포함한 대시보드. Import → Upload JSON으로 가져가기.
