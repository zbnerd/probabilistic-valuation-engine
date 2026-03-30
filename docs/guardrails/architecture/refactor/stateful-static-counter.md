---
id: GR-REFACTOR-012
category: architecture/refactor
severity: critical
keywords: [static, atomic-long, stateful, distributed, counter]
languages: [java, kotlin]
---

# Static AtomicLong Counter

## DON'T (위반 사항/장애 원인)

### 위험 코드
```java
// static 변수로 전역 카운터 유지
private static final AtomicLong requestCounter = new AtomicLong(0);

public long getNextId() {
    return requestCounter.incrementAndGet();
}
```

### 위험 요소
- **Scale-out 불가**: 각 인스턴스가 독립적인 카운터 유지
- **충돌 가능성**: 다중 인스턴스에서 중복 ID 발급
- **재시작 시 초기화**: 애플리케이션 재시작 시 카운터 리셋

### 수치 (Before)
- 다중 인스턴스 충돌: 확정적 발생
- 재시작 후 중복: 가능성 HIGH

## DO (수정 방법/재발 방지)

### 수정 코드 (옵션 1: Redis AtomicLong)
```java
// Redisson의 AtomicLong 사용 (분산 환경 안전)
@Component
public class DistributedIdGenerator {
    private final RedissonClient redisson;

    public long getNextId(String key) {
        RAtomicLong atomicLong = redisson.getAtomicLong("counter:" + key);
        return atomicLong.incrementAndGet();
    }
}
```

### 수정 코드 (옵션 2: Database Sequence)
```java
// PostgreSQL Sequence 사용
@Repository
public interface SequenceRepository {
    @Query("SELECT nextval('request_id_seq')")
    Long getNextId();
}

@Service
public class IdGeneratorService {
    private final SequenceRepository sequenceRepository;

    public long getNextId() {
        return sequenceRepository.getNextId();
    }
}
```

### 수정 코드 (옵션 3: Snowflake ID)
```java
// Twitter Snowflake 패턴 (분산 ID 생성)
@Component
public class SnowflakeIdGenerator {
    private final long datacenterId;
    private final long workerId;
    private long sequence = 0;

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        return ((timestamp - twepoch) << 22)
            | (datacenterId << 17)
            | (workerId << 12)
            | sequence++;
    }
}
```

### 개선 수치 (After)
- 다중 인스턴스 충돌: 방지됨
- 영구성 보장: Redis/DB에 저장

### 핵심 원칙
1. **분산 저장소 사용**: Redis AtomicLong 또는 DB Sequence
2. **충돌 방지**: Datacenter/Worker ID로 영역 분리 (Snowflake)
3. **영구성 보장**: 애플리케이션 재시작 후에도 ID 유지

## 출처
- 문서: [docs/05_Reports/05_08_Refactor/STATEFUL_REFACTORING_TARGETS.md](../../../05_Reports/05_08_Refactor/STATEFUL_REFACTORING_TARGETS.md)
- 관련 ADR: [ADR-010](../../../01_ADR/ADR-010-outbox-pattern.md) (Write-Behind Buffer)
