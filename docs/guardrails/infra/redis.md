---
id: GR-002
category: infra
severity: critical
keywords: [Redis, Redisson, Lua Script, Hash Tag, DLQ, Distributed Lock]
---
# Redis & Redisson Integration

## DON'T (안티패턴)

### 1. Hash Tag 없는 다중 키 연산
```java
// Bad (Cluster에서 다른 슬롯 -> 실패)
String sourceKey = "buffer:likes";
String tempKey = "buffer:likes:sync:uuid";
redis.rename(sourceKey, tempKey);  // CROSSSLOT Keys
```

### 2. 임시 키 TTL 미설정
```java
// Bad (JVM 크래시 시 영구 메모리 누수)
String tempKey = "{buffer:likes}:sync:" + UUID;
redis.set(tempKey, data);  // TTL 없음
```

### 3. 보상 트랜잭션 없는 원자적 연산
```java
// Bad (DB 저장 실패 시 데이터 손실)
FetchResult result = atomicFetch(sourceKey, tempKey);
database.save(result);  // 여기서 실패하면 tempKey에 데이터만 존재
// 복구 로직 없음
```

### 4. DLQ 없는 보상 로직
```java
// Bad (복구 실패 시 영구 손실)
private void compensate() {
    strategy.restore(tempKey, sourceKey);  // 여기서 실패하면?
    // DLQ 이벤트 발행 없음 -> 데이터 영구 손실 (P0 #287)
}
```

### 5. 루프 내 LogicExecutor 사용
```java
// Bad (TaskContext 생성 오버헤드)
for (Object value : hashValues) {
    long num = executor.executeOrDefault(
        () -> Long.parseLong(String.valueOf(value)),
        0L,
        TaskContext.of("Parse", "long", value)  // 루프마다 새 객체
    );
}
```

### 6. onStatus()로 WebClient 에러 처리
```java
// Bad (에러 본문 로깅 불가)
.retrieve()
.onStatus(
    HttpStatusCode::is4xxClientError,
    response -> {
        log.warn("Error: {}", response.statusCode());  // 상태 코드만
        return Mono.empty();
    }
)
```

### 7. RScript.Mode 잘못 사용
```java
// Bad (데이터 변경 시 READ_ONLY 모드)
script.eval(
    RScript.Mode.READ_ONLY,  // 잘못됨
    LUA_SET_SCRIPT,
    ReturnType.STATUS,
    keys, args
);
```

## DO (베스트 프랙티스)

### 1. Hash Tag 패턴 (Cluster 호환)
```java
// Good (같은 슬롯 보장)
String sourceKey = "{buffer:likes}";
String tempKey = "{buffer:likes}:sync:" + UUID.randomUUID();
// RENAME, Lua Script 모두 정상 작동
```

**Hash Tag 적용 대상:**
- RENAME 키 쌍: `{domain}:source` <-> `{domain}:target`
- Lua Script 다중 키: 모든 KEYS는 같은 Hash Tag
- MGET/MSET 키들: 같은 Hash Tag 사용

### 2. 임시 키 TTL 안전장치
```java
// Good (1시간 TTL -> 영구 메모리 누수 방지)
redis.call('EXPIRE', KEYS[2], 3600);

// application.yml 설정화
like:
  sync:
    temp-key-ttl-seconds: 3600  # 1시간
```

### 3. 보상 트랜잭션 패턴 (Command Pattern)
```java
// Good
CompensationCommand cmd = new RedisCompensationCommand(sourceKey, strategy, executor);
executor.executeWithFinally(
    () -> {
        FetchResult result = strategy.fetchAndMove(sourceKey, tempKey);
        cmd.save(result);
        processDatabase(result);  // DB 저장
        cmd.commit();             // 성공 -> 임시 키 삭제
        return null;
    },
    () -> {
        if (cmd.isPending()) {
            cmd.compensate();     // 실패 -> 원본 키 복원
        }
    },
    context
);
```

### 4. DLQ (Dead Letter Queue) 패턴 (P0 필수)
```java
// Good (복구 실패 시 DLQ 이벤트 발행)
private void compensate() {
    executor.executeOrCatch(
        () -> strategy.restore(tempKey, sourceKey),
        e -> {
            LikeSyncFailedEvent event = LikeSyncFailedEvent.fromFetchResult(result, sourceKey, e);
            eventPublisher.publishEvent(event);  // DLQ 이벤트 발행
            return null;
        },
        context
    );
}

@Async
@EventListener
public void handleSyncFailure(LikeSyncFailedEvent event) {
    // 1. 파일 백업 (데이터 보존 최우선)
    persistenceService.appendLikeEntry(event.userIgn(), event.lostCount());
    // 2. 메트릭 기록
    meterRegistry.counter("like.sync.dlq.triggered").increment();
    // 3. Discord 알림 (운영팀 인지)
    discordAlertService.sendCriticalAlert("DLQ 발생", event.errorMessage());
}
```

**DLQ 처리 우선순위:**
1. 파일 백업 (데이터 보존 최우선)
2. 메트릭 기록 (모니터링)
3. 알림 발송 (운영팀 인지)

### 5. 루프 내 Pattern Matching (성능 최적화)
```java
// Good (Pattern Matching + 직접 예외 처리)
private long parseLongSafe(Object value) {
    if (value == null) return 0L;
    if (value instanceof Number n) return n.longValue();
    if (value instanceof String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            log.warn("Malformed data ignored: value={}", s);
            recordParseFailure();  // 메트릭으로 모니터링
            return 0L;
        }
    }
    return 0L;
}
```

### 6. onErrorResume()으로 WebClient 에러 처리
```java
// Good (상태 코드 + 에러 본문 로깅)
.retrieve()
.bodyToMono(Response.class)
.onErrorResume(WebClientResponseException.class, ex -> {
    if (ex.getStatusCode().is4xxClientError()) {
        log.warn("API Failed. Status: {}, Body: {}",
            ex.getStatusCode(), ex.getResponseBodyAsString());
        return Mono.empty();
    }
    // 5xx: 서킷브레이커 동작을 위해 상위 전파
    return Mono.error(ex);
})
.timeout(API_TIMEOUT)
```

### 7. RScript.Mode/ReturnType 올바른 사용
```java
// Good
RScript script = redissonClient.getScript(StringCodec.INSTANCE);

// 데이터 변경 (SET, DEL, RENAME)
List<Object> result = script.eval(
    RScript.Mode.READ_WRITE,     // 데이터 변경 시
    LUA_ATOMIC_MOVE,
    RScript.ReturnType.MULTI,    // 복수 결과 반환
    Arrays.asList(sourceKey, tempKey),
    String.valueOf(ttlSeconds)
);

// 조회만 (GET, HGETALL)
Object value = script.eval(
    RScript.Mode.READ_ONLY,      // 조회만
    LUA_GET_SCRIPT,
    RScript.ReturnType.VALUE,
    List.of(key),
    null
);
```

### 8. Orphan Key Recovery (@PostConstruct)
```java
// Good (JVM 크래시 대응)
@PostConstruct
public void recoverOrphanKeys() {
    RKeys keys = redissonClient.getKeys();
    Iterable<String> orphans = keys.getKeysByPattern("{buffer:likes}:sync:*");

    for (String orphanKey : orphans) {
        atomicFetchStrategy.restore(orphanKey, SOURCE_KEY);
    }
}
```

### 9. Redis 키 네이밍 규칙
```java
// Good (domain:sub-domain:id 형식 + TTL 필수)
String key = "character:equipment:" + characterId;
redis.set(key, data);
redis.expire(key, Duration.ofMinutes(10));
```

### 10. Distributed Lock (try-finally)
```java
// Good (데드락 방지)
RLock lock = redissonClient.getLock("lock:key");
try {
    if (lock.tryLock(30, TimeUnit.SECONDS)) {
        // 비즈니스 로직
    }
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

## 출처
- infrastructure.md Section 8: Redis & Redisson Integration
- infrastructure.md Section 8-1: Redis Lua Script & Cluster Hash Tag
- ADR-007: AOP/Async Cache Integration
- P0 Incident #287: DLQ Data Loss Prevention
