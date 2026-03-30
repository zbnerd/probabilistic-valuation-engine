# 7장: 차가운 시작 — 캐시 웜업의 중요성

> "서버 재시작 후 3초간 지옥이 펼쳐진다."

## 문제: Cold Start의 비극

6장에서 V5 Stateless 아키텍처를 구현했다. 325 RPS. 그런데 이건 캐시가 이미 채워진 상태의 수치다.

서버 재시작 직후, 캐시가 비어있는 상태에서 부하 테스트를 돌려봤다:

| 상태 | RPS | 타임아웃 | p50 |
|------|-----|---------|-----|
| **Cold (빈 캐시)** | **287** | **20%+** | **760ms** |
| Warm (100 conn) | 561 | 2.7% | ~530ms |
| Warm (200 conn) | 940 | 0.9% | ~630ms |

Cold 상태에서는 20% 요청이 타임아웃된다. 모든 요청이 캐시 미스이기 때문에 Nexon API 호출 + 파싱 + 계산이 매 번 발생한다.

## 고민: 캐시를 미리 채울 수 없을까?

캐시가 비어있으면 느리다. 그렇다면 **서버 시작 시 미리 인기 캐릭터의 캐시를 채워두자**.

질문: 어떤 캐릭터를 미리 채울 것인가?

답: **전날 가장 많이 조회된 캐릭터 Top N**.

### 고려사항

1. **Thundering Herd 방지**: 100개 캐릭터를 동시에 웜업하면 Nexon API에 100개 동시 요청이 간다. 50ms 간격으로 순차 실행.
2. **워밍업 실패 허용**: 일부 캐릭터 웜업이 실패해도 서버는 정상 기동.
3. **스케줄러 기반**: 매일 새벽에 인기 캐릭터 목록을 갱신.

## 구현: Auto Warmup

```yaml
scheduler:
  warmup:
    enabled: true           # 자동 웜업 활성화
    top-count: 100          # 전날 인기 캐릭터 100명
    delay-between-ms: 50    # Thundering Herd 방지 (50ms 간격)
```

```java
@Scheduled(fixedDelay = "${scheduler.warmup.delay-between-ms:50}")
public void warmupPopularCharacters() {
    List<String> topIgns = popularCharacterService.getTopN(topCount);
    for (String ign : topIgns) {
        if (!tieredCacheManager.isCached(CACHE_NAME, ign)) {
            equipmentExpectationService.warmup(ign);
        }
        Thread.sleep(delayBetweenMs);  // 50ms 간격
    }
}
```

## 결과: Cold → Warm 227% 향상

2026년 1월 27일, Multi-Instance + Auto Warmup 테스트:

```
╔════════════════════════════════════════════════════════════╗
║  AUTO WARMUP                                               ║
║                                                            ║
║  Cold → Warm 비교:                                         ║
║  Cold:      287 RPS (Timeout 20%+, p50 ~760ms)            ║
║  Warm(100): 561 RPS (Timeout 2.7%, p50 ~530ms)            ║
║  Warm(200): 940 RPS (Timeout 0.9%, p50 ~630ms)            ║
║                                                            ║
║  개선: +227% (Cold → Warm)                                 ║
╚════════════════════════════════════════════════════════════╝
```

### Scale-out 한계 발견

인스턴스를 늘려가면서 테스트:

| 인스턴스 | RPS | 병목 |
|----------|-----|------|
| 3 | 940 | ✅ 최적 |
| **5** | **833** | **❌ HikariCP 포화** |

5대부터 오히려 RPS가 떨어졌다. 원인: **DB 커넥션 풀 고갈**. 5대 × 30커넥션 = 150커넥션이 MySQL에 몰리는데, MySQL이 감당하지 못한다.

## 새로운 방향

여기서 큰 깨달음이 있었다:

> **"Redis, MySQL, MongoDB 세 개를 유지하는 게 병목의 근원이다. 하나로 줄이면 안 되나?"**

그동안의 최적화는 "기존 인프라 위에서 성능을 끌어올리는" 작업이었다. 하지만 근본적으로, **3개의 데이터베이스를 1개로 줄이면**:
- 네트워크 왕복 감소
- 운영 복잡도 감소
- 비용 절감
- Scale-out 단순화

이 발상이 **PostgreSQL 단일 DB로의 대이주**로 이어진다.

---

> **이 시점의 RPS: 940 (Auto Warmup, 3인스턴스 기준)**
> **커밋**: `a98b6716` feat: Auto Warmup - 인기 캐릭터 자동 웜업 (#275)
> **관련 이슈**: #275 (Auto Warmup), #278 (Scale-out 실시간 좋아요 동기화)
> **PR**: Issue #275

**다음 장**: [8장 — 대이주: Redis, MySQL, MongoDB를 버리다](./08_great_migration.md)
