---
id: GR-NIGHTMARE-N01
category: testing/chaos
severity: critical
keywords: [singleflight, race condition, cache stampede, thundering herd]
languages: [java, kotlin]
---

# N01: Singleflight Race Condition

## DON'T (안티패턴)

```java
// Java - Race Condition 가능
public <T> T compute(String key, Function<String, T> loader) {
    return cache.computeIfAbsent(key, k -> {
        // 다중 스레드가 동시에 진입하여 중복 계산 발생
        return loader.apply(k);
    });
}
```

```kotlin
// Kotlin - Race Condition 가능
fun <T> compute(key: String, loader: (String) -> T): T {
    return cache.getOrPut(key) {
        // 다중 스레드가 동시에 진입하여 중복 계산 발생
        loader(key)
    }
}
```

**장애 수치 (Before):**
- 동시 요청 100건 시 중복 계산: 50-100건
- API 호출 중복: 50-100%
- Cache Stampede 발생: 매일

## DO (베스트 프랙티스)

```java
// Java - Singleflight 패턴 적용
public <T> T compute(String key, Function<String, T> loader) {
    return singleflightExecutor.execute(key, () -> {
        return cache.computeIfAbsent(key, k -> loader.apply(k));
    });
}
```

```kotlin
// Kotlin - Singleflight 패턴 적용
fun <T> compute(key: String, loader: (String) -> T): T {
    return singleflight.execute(key) {
        cache.getOrPut(key) { loader(key) }
    }
}
```

**개선 수치 (After):**
- 동시 요청 100건 시 중복 계산: 1건 (99% 감소)
- API 호출 중복: 1%
- Cache Stampede 발생: 0건

## 핵심 원칙

1. **Singleflight 패턴**: 동일 키에 대한 동시 요청을 단일 실행으로 병합
2. **Lock 분리**: 키별 락을 사용하여 전체 경합 방지
3. **Future 캐싱**: 진행 중인 요청의 Future를 캐싱하여 재사용

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N01-thundering-herd.md`
- Nightmare Test N01: Singleflight Race Condition
- Test Class: `SingleflightRaceConditionNightmareTest`
