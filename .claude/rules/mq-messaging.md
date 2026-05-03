# 메시지 큐 규칙 (MQ Messaging)

## MQ Message ACK는 성공 확인 후

- Message acknowledgment(archive/delete)는 비즈니스 로직 성공 확인 후에만 수행
- `executeOrDefault`에 ACK 기본값 사용 금지 — 예외 시 메시지가 자동 archive되어 재전송 불가
- 실패 경로는 명시적으로 NACK 또는 visibility timeout 재전달에 맡김

## Retry / Visibility Window 정렬

- Timeout/scanner threshold는 실제 message visibility timeout에 맞게 설정
- Stale-check가 visibility 만료 전에 발생하면 중복 처리 발생
- Retry backoff delay는 계산만 하고 버리지 않고 실제로 적용

## 복구 불가능한 In-Memory 상태 금지

- 업무 크리티컬 작업에 in-memory queue (`LinkedBlockingQueue`) 사용 금지 → PGMQ 또는 DB-backed queue 사용
- in-flight tracking에 in-memory map (`ConcurrentHashMap`) 사용 금지 → PostgreSQL UNLOGGED table 사용
- memory buffer의 데이터는 반드시 durability strategy 필요: periodic flush, WAL, write-ahead to DB
