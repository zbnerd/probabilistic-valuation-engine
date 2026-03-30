# ADR-363: ExpectationCalculationQueue PGMQ Migration

## 상태 (Status)

**수락됨 (Accepted)**

## 컨텍스트 (Context)

### 현재 상태

기존 ExpectationCalculationQueue는 LinkedBlockingQueue를 사용하여 인메모리 큐를 구현합니다:

```java
// ExpectationCalculationQueue.java
public class ExpectationCalculationQueue {
    private final LinkedBlockingQueue<ExpectationCalcMessage> queue = new LinkedBlockingQueue<>();

    public void enqueue(ExpectationCalcMessage message) {
        queue.offer(message);
    }
}
```

### 문제 정의

1. **Instance Failure Loss**: 인스턴스 장애 시 큐 데이터 손실
2. **Scale-out 한계**: 다중 인스턴스 간 작업 분배 불가
3. **Durability 부재**: 메시지 영구 저장 보장 불가
4. **Backpressure 없음**: 큐 과부하 시 처리 불가

## 결정 (Decision)

### 1. PGMQ 큐 도입

```kotlin
-- 기존: 단일 큐
INSERT INTO queue (payload) VALUES (?)

-- 변경: 우선순위별 분리
-- expectation_calc_high: 고우선순위 (즉시 처리)
-- expectation_calc_low: 저우선순위 (지연 처리)
```

### 2. PgmqWorker 상속 구조

```kotlin
// ExpectationCalcWorker.kt
@Component
class ExpectationCalcWorker(
    private val pgmq: PgmqClient
) : PgmqWorker<ExpectationCalcMessage>(
    queueName = "expectation_calc_high",
    pollInterval = 100, // 100ms
    maxBatchSize = 10
) {
    override fun processBatch(messages: List<ExpectationCalcMessage>) {
        // 고우선순위 작업 처리
    }
}

// ExpectationCalcLowWorker.kt
@Component
class ExpectationCalcLowWorker(
    private val pgmq: PgmqClient
) : PgmqWorker<ExpectationCalcMessage>(
    queueName = "expectation_calc_low",
    pollInterval = 5000, // 5s
    maxBatchSize = 50
) {
    override fun processBatch(messages: List<ExpectationCalcMessage>) {
        // 저우선순위 작업 처리
    }
}
```

### 3. Backpressure 구현

```kotlin
// ExpectationCalculationQueue.kt
class ExpectationCalculationQueue {
    private val queueLengthThreshold = 1000

    fun enqueue(message: ExpectationCalcMessage, priority: Priority) {
        // 큐 길이 확인
        val currentLength = pgmq.getQueueLength(queueName)

        if (currentLength >= queueLengthThreshold) {
            throw QueueBackpressureException("Queue is full: $currentLength")
        }

        pgmq.send(queueName, message)
    }
}
```

## 결과 (Consequences)

### 긍정적 영향

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| Crash Recovery | 불가능 | PGMQ 복구 |
| Scale-out | 단일 인스턴스 | 다중 인스턴스 |
| Durability | 없음 | PGMQ 보장 |
| Priority 분리 | 없음 | High/Low 분리 |

### 부정적 영향

| 항목 | 영향 | 완화 방안 |
|------|------|---------|
| Latency 증가 | 네트워크 지연 | Batch 처리로 최적화 |
| 복잡성 증가 | PGMQ 학습 필요 | 기존 PGMQ 재사용 |

### 거부된 옵션 (Rejected Options)

1. **Single PGMQ with Priority Field**: 우선순위 제어 불명확 → 별도 큐 생성
2. **Keep LinkedBlockingQueue**: 원문제 해결 불가 → 완전 마이그레이션
3. **RabbitMQ**: 기존 인프라와의 의존성 → PGMQ 재사용

### 마이그레이션 경로

1. **Phase 1**: PGMQ 큐 생성 (V104 마이그레이션)
2. **Phase 2**: PgmqWorker 구현체 생성
3. **Phase 3**: ExpectationCalculationQueue 리팩토링
4. **Phase 4**: Backpressure 테스트

## 이력 (History)

| 날짜 | 변경 내용 | 작성자 |
|------|---------|--------|
| 2026-03-29 | 초안 작성 | Claude (Haiku 4.5) |

## 참조 (References)

### 관련 문서
- [PGMQ Documentation](https://github.com/tembo-io/pgmq)
- [Message Queue Patterns](https://www.enterpriseintegrationpatterns.com/patterns/messaging/MessageChannel.html)

### 구현 파일
- `module-core/src/main/kotlin/maple/expectation/core/domain/ExpectationCalcMessage.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/ExpectationCalcWorker.kt`
- `module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java`
- `db/migration/V104__expectation_calculation_queue_pgmq.sql`

### 관련 Issue
- Issue #634: Expectation Calculation Queue PGMQ Migration