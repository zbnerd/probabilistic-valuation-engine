# 아키텍처 가드레일 (Architecture Guardrails)

## 6. Stateless Architecture (절대 준수)

**금지 패턴:**
- `HttpSession`, `@SessionScope`, `@SessionAttributes` 사용 금지
- `static mutable` 상태 금지

**상태 저장소:** Redis, MySQL, MongoDB만 사용

## 7. Advisory Lock & Single Flight 규칙

- **Lock Scope:** advisory lock을 사용할 때는 `pg_try_advisory_xact_lock`(트랜잭션 스코프)을 우선 사용. 세션 스코프(`pg_advisory_lock`)는 HikariCP에서 위험
- **Pattern:** hot-key cache stampede 방지가 필요한 L2 cache 경로에서만 Leader/Follower 패턴을 사용한다. Cold-miss external API pipeline은 request idempotency key + DB unique constraint를 우선 사용한다.
- **AOP 주의:** 같은 클래스 내부에서 `@Transactional` 메서드 직접 호출 금지 (프록시 미작동)
- **Key 일치:** 락 키, 캐시 키, NOTIFY 페이로드는 반드시 동일한 생성 로직 사용
- **Cache Key 일관성:** 캐시 키 포맷 변경 시 모든 producer/consumer 업데이트 필수. 키 불일치는 silent data loss 유발

## 8. TieredCache 흐름

```
L1 (Caffeine) → L2 (PostgreSQL UNLOGGED) → SingleFlight → Loader
```

## 9. 비동기 실행 모델 선택

- **CPU-bound** (계산, 파싱, 변환): **Kotlin Coroutine** 사용 (`Dispatchers.Default`)
- **IO-bound** (DB, HTTP, 파일): **Virtual Thread** 사용 (`@Async` + virtual thread executor)

## 10. Virtual Thread 주의사항

`synchronized` 블록 안에서 blocking하면 carrier thread pinning 발생. `ReentrantLock` 사용.
