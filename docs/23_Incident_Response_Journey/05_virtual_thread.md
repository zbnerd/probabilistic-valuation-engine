# 5장: 가상의 그림자 — Virtual Thread Pinning과의 사투

> "synchronized 하나가 carrier thread를 고정시킨다고?"
>
> — ADR-088, HikariCP Virtual Thread 튜닝

---

## 발생: OOM 장애대응 테스트에서 발견된 그림자

2026년 1월, 장애대응 테스트 Scenario 03 — **OOM(Out of Memory)**.

```
장애대응 테스트 결과 — OOM

Heap Initial: 512MB → Peak: 2.1GB
GC Pressure: 85% Heap utilization
Virtual Thread Pinning: CONFIRMED ❌
Thread Pool Exhaustion: YES ❌
Service Availability: DEGRADED (40% timeout)
Recovery Time: 32s (auto-heal)
```

서킷 브레이커도, 캐시도, SingleFlight도 아닌 — **Virtual Thread Pinning**이 원인이었다.

---

## 탐지: "스레드가 사라졌다"

Java 21의 Virtual Thread는 수백만 개의 스레드를 가볍게 생성할 수 있다고 들었다. 그래서 적극적으로 활용했다. 비동기 파이프라인은 Virtual Thread로 전환했고, RPS는 89에서 719로 8배 향상되었다.

하지만 장애대응 테스트에서 이상한 현상이 관찰되었다.

```
Virtual Thread 수: 10,000+
Carrier Thread 수: CPU 코어 수 (2-4)
실행 중인 Virtual Thread: 2-4
대기 중인 Virtual Thread: 9,996+

→ 수만 개의 Virtual Thread가 대기하고 있다
→ Carrier Thread가 고정(pinned)되어 있다
→ 새 Virtual Thread를 실행할 Carrier Thread가 없다
```

---

## 분석: synchronized의 함정

Virtual Thread의 약점은 **synchronized 블록**이었다.

```java
// 이론적 예시 — synchronized가 blocking 작업을 포함할 때의 문제
public class TieredCache {
    private final ConcurrentHashMap<String, ValueWrapper> cache = new ConcurrentHashMap<>();

    public synchronized ValueWrapper get(String key) {  // ← synchronized!
        ValueWrapper wrapper = cache.get(key);
        if (wrapper != null) {
            return wrapper;
        }
        // DB에서 로드...
        wrapper = loadFromDb(key);
        cache.put(key, wrapper);
        return wrapper;
    }
}
```

`synchronized` 블록 안에서 blocking 작업(DB 쿼리, 네트워크 호출 등)을 수행하면, Virtual Thread를 실행하던 **Carrier Thread가 고정(pinned)**된다. Carrier Thread는 다른 Virtual Thread를 실행할 수 없게 된다.

```
Carrier Thread 1: Virtual Thread A 실행 중
  → synchronized 진입
  → DB 대기 (blocking)
  → Carrier Thread 1 고정 (pinning!)
  → 다른 Virtual Thread 실행 불가

Carrier Thread 2: Virtual Thread B 실행 중
  → synchronized 진입
  → DB 대기 (blocking)
  → Carrier Thread 2 고정 (pinning!)

... 모든 Carrier Thread가 고정되면 전체 시스템 마비
```

CPU 코어가 2개면 Carrier Thread도 2개. 두 개가 모두 고정되면 아무것도 실행할 수 없다.

---

## 대응: ReentrantLock으로 전환

해결책은 간단했다. `synchronized`를 `ReentrantLock`으로 교체하는 것.

```java
// Before: synchronized (Virtual Thread Pinning 발생)
public synchronized ValueWrapper get(String key) { ... }

// After: ReentrantLock (Pinning 방지)
private final ReentrantLock lock = new ReentrantLock();

public ValueWrapper get(String key) {
    lock.lock();
    try {
        ValueWrapper wrapper = cache.get(key);
        // ...
    } finally {
        lock.unlock();
    }
}
```

`ReentrantLock`은 Virtual Thread 친화적이다. blocking 시 Carrier Thread를 해제하고 다른 Virtual Thread를 실행할 수 있게 한다.

### Caffeine Cache의 synchronized

Caffeine 캐시 자체도 내부적으로 synchronized를 사용한다. 이것은 우리가 제어할 수 없는 부분이었다.

해결책: Caffeine의 AsyncCache를 사용하고, 동기 접근을 최소화했다.

```kotlin
// Caffeine AsyncCache 사용
private val cache: AsyncCache<String, ByteArray> = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .buildAsync()

// 비동기 접근
fun get(key: String): CompletableFuture<ByteArray?> {
    return cache.get(key) { k -> loadFromDb(k) }
}
```

### HikariCP와 Virtual Thread

HikariCP도 내부적으로 synchronized를 사용한다. 커넥션 획득/반환이 모두 synchronized 블록 안에서 이루어진다.

이건 피할 수 없는 제약이었다. 대신 설정으로 완화했다:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30           # Virtual Thread 환경에서는 여유 있게
      connection-timeout: 30000       # 타임아웃 확장
      idle-timeout: 600000
      max-lifetime: 1800000
      leak-detection-threshold: 15000 # 누수 감지
```

---

## P0 수정 사항

Virtual Thread Pinning과 관련된 P0 이슈들을 정리했다.

```
P0 수정 목록:

1. Jitter — 재시도 간격에 무작위성 추가 (동시 재시도 폭주 방지)
2. Scope Leak — ThreadLocal 스레드 로컬 변수 누수 수정
3. VT Overload — Virtual Thread 과다 생성 방지
4. Shutdown Safety — 종료 시 진행 중인 작업 안전하게 완료
```

### ThreadLocal 누수

Virtual Thread 환경에서 ThreadLocal은 특히 위험하다. 수백만 개의 Virtual Thread가 각자 ThreadLocal을 가지면 메모리가 폭발한다.

```java
// Anti-Pattern: Virtual Thread + ThreadLocal
private static final ThreadLocal<OCIDMap> ocidMap = ThreadLocal.withInitial(OCIDMap::new);

// 100만 개의 Virtual Thread = 100만 개의 OCIDMap 인스턴스
// → OOM
```

**해결:** ThreadLocal 대신 ConcurrentHashMap 기반 컨텍스트 전달.

```kotlin
// 해결: 명시적 컨텍스트 전달
class TaskContext private constructor(
    val module: String,
    val task: String,
    val key: String,
) {
    companion object {
        fun of(module: String, task: String, key: String) =
            TaskContext(module, task, key)
    }
}
```

---

## 장애대응 테스트: GC Pause와의 관계

Virtual Thread Pinning은 GC Pause와도 관련이 있었다.

```
장애대응 테스트 — GC Pause:

GC Type: G1 Full GC
Pause Duration: 847ms
Heap Before: 1,024 MB → After: 256 MB (75% 해제)

Advisory Lock TTL: 5,000ms
GC Pause: 847ms
남은 TTL: 4,153ms
상태: 안전 ✅ (TTL > GC Pause)
```

GC Pause가 847ms면 Virtual Thread도 847ms 동안 멈춘다. 하지만 Advisory Lock TTL이 5초이므로, 847ms의 GC Pause에는 안전하다. GC가 4초 이상 걸리면 락이 만료되고 데이터 무결성에 문제가 생긴다.

이것도 장애대응 테스트에서만 확인할 수 있었다.

---

## 교훈

**1. Virtual Thread는 은총알이 아니다.**

수백만 개의 스레드를 만들 수 있다고 해서 모든 문제가 해결되는 건 아니다. synchronized와의 상호작용, ThreadLocal 메모리, Carrier Thread 고정 — 새로운 문제가 생긴다.

**2. 프레임워크의 내부 구현을 이해해야 한다.**

HikariCP의 synchronized, Caffeine의 synchronized — 우리가 직접 쓰지 않아도 의존하는 라이브러리가 Pinning을 유발할 수 있다.

**3. ReentrantLock은 선택이 아니라 필수다.**

Virtual Thread 환경에서는 `synchronized` 대신 `ReentrantLock`을 사용해야 한다. 이건 Java 21의 명시적 권장 사항이다.

**4. 장애대응 테스트만이 Virtual Thread 문제를 발견할 수 있다.**

일반적인 부하 테스트로는 Pinning을 재현하기 어렵다. 의도적인 메모리 압박과 GC 유발이 필요하다.

---

> **다음 장:** [6장: 락의 미로 — Advisory Lock과 교착상태의 함정](06_advisory_lock.md)
