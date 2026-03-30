# 3장: 발견 — 캐시 히트인데 왜 느리지?

> "캐시에 있는데 왜 200ms나 걸리지?" — 이 질문이 473% 성능 향상의 시작이었다.

## 문제: 캐시 히트가 느리다

2장의 LocalSingleFlight 롤백 후, 다시 원래 코드로 돌아왔다. 그런데 이상한 점을 발견했다.

캐시 히트가 발생했는데도 응답에 200ms 이상 걸렸다. 캐시에서 꺼내서 반환하기만 하면 되는데, 왜 이렇게 느린가?

## 원인 추적: 요청의 여정

요청 하나가 거치는 경로를 추적해봤다:

```
Client → Controller → Service → Executor.submit()
  → TieredCacheManager.get() → L1 조회 → Redis 조회 → 역직렬화
  → GZIP 해제 → JSON → Java Object → 다시 JSON 직렬화 → GZIP 압축 → 응답
```

문제가 명확해졌다:

1. **Executor.submit() 오버헤드**: 캐시 히트인데도 스레드풀에 작업을 제출한다. 큐 대기 + 스레드 할당 = 50~100ms
2. **불필요한 직렬화/역직렬화**: 캐시에 GZIP byte[]로 저장 → 꺼낼 때 String으로 역직렬화 → 응답을 위해 다시 GZIP 압축. 300KB를 두 번 변환
3. **Redis 네트워크 왕복**: L1에 없으면 Redis까지 갔다 와야 한다

**핵심 인사이트**: 캐시에 이미 GZIP 압축된 응답이 있는데, 이를 풀었다가 다시 압축하고 있었다.

## 해결: L1 Fast Path — Zero-Copy 직접 접근

발상의 전환이었다. 캐시 히트 시 **스레드풀을 우회하고, 직렬화 없이, GZIP byte[]를 그대로 반환**하자.

```
Before (200ms):
Controller → Executor → L1.get() → Deserialize → Serialize → GZIP → Response

After (4~29ms):
Controller → L1.getGzipDirect() → Response  // 끝!
```

### 구현

```java
// TieredCacheManager에 직접 접근 메서드 추가
public Cache getL1CacheDirect(String name) {
    return l1Manager.getCache(name);
}

// EquipmentExpectationServiceV4 — Fast Path
public Optional<byte[]> getGzipFromL1CacheDirect(String userIgn) {
    Cache l1Cache = tieredCacheManager.getL1CacheDirect(CACHE_NAME);
    Cache.ValueWrapper wrapper = l1Cache.get(userIgn);
    if (wrapper == null) return Optional.empty();
    return Optional.of(Base64.getDecoder().decode((String) wrapper.get()));
}
```

컨트롤러에서 먼저 Fast Path를 확인하고, 미스면 기존 비동기 경로로 간다:

```java
// GameCharacterControllerV4
public ResponseEntity<byte[]> getExpectation(String userIgn) {
    // Fast Path: L1에 있으면 즉시 반환
    Optional<byte[]> cached = service.getGzipFromL1CacheDirect(userIgn);
    if (cached.isPresent()) {
        return ResponseEntity.ok()
            .header("Content-Encoding", "gzip")
            .body(cached.get());
    }
    // Slow Path: 비동기 계산
    return service.calculateExpectation(userIgn);
}
```

### 캐시 설정 조정

```java
// Before → After
.expireAfterWrite(30, MINUTES) → .expireAfterWrite(60, MINUTES)
.maximumSize(1000)             → .maximumSize(5000)
```

캐시를 더 오래, 더 많이 보관하게 했다. t3.small에서 25MB 추가 메모리.

## 결과: 555 RPS (+473%)

2026년 1월 24일, 같은 날 오후. L1 Fast Path 적용 후 부하 테스트:

```
╔════════════════════════════════════════════════════════════╗
║  V4 PHASE 2 — L1 FAST PATH                                ║
║  wrk (C Native):                                          ║
║  - RPS:       555~569 (+473% vs 97)                       ║
║  - Error:     1.4~3.3%                                    ║
║  - L1 Hit:    99.99%                                      ║
║  - Min:       4ms                                         ║
║  - p50:       871~991ms                                   ║
║  Locust (Python):                                         ║
║  - RPS:       241 (Python GIL 병목!)                      ║
╚════════════════════════════════════════════════════════════╝
```

### 부수 발견: Locust의 GIL 병목

Locust는 Python 기반이라 C 기반 wrk보다 절반 이하의 RPS를 보여줬다. **측정 도구 자체가 병목**이었다. 이후 모든 성능 측정은 wrk로 통일했다.

```
측정 도구 비교:
Locust (Python): 241 RPS — GIL 병목으로 실제 서버 성능 미반영
wrk (C Native):  555 RPS — 실제 서버 성능
```

## "괴물 스펙" 환산

일반적인 웹 API는 2KB 응답을 10,000 RPS로 처리한다. 우리 API는 **300KB** 응답을 555 RPS로 처리한다.

```
일반 API: 10,000 RPS × 2KB = 20 MB/s
우리 API:    555 RPS × 300KB = 166.5 MB/s → 8.3배 더 많은 데이터 처리
```

등가 환산: **8,300 RPS급** 데이터 처리 능력.

## 새로운 문제

555 RPS. 좋아졌지만 아직 부족하다. p50이 여전히 871ms. 분석해보니:

- 캐시 히트는 4ms인데, **캐시 미스 시 DB 저장이 15~30ms**를 차지
- 프리셋 3개를 순차 계산: 100ms × 3 = 300ms
- 동기 DB 저장이 전체 요청 시간의 30%

다음 타겟이 정해졌다: **DB 저장을 비동기로 만들자.**

---

> **이 시점의 RPS: 555 (이전 97 대비 +473%)**
> **커밋**: `6fbadecc` feat: V4 API L1 Fast Path 최적화 및 캐시 튜닝 (#264) (#265)
> **관련 이슈**: #264, #265
> **PR**: Issue #264 (L1 Fast Path + GZIP 최적화)

**다음 장**: [4장 — DB 저장이 발목을 잡다](./04_write_behind_buffer.md)
