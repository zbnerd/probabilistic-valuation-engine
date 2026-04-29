# 동시성 관리 (Concurrency Management)

## Semaphore 퍼밋 누수 방지

- Semaphore 획득 후 반드시 `finally` 블록에서 release
- `tryAcquire` 사용 시에도 guaranteed release 필요
- LogicExecutor의 `executeWithFinally`로 acquire/release 래핑
- **근거**: 퍼밋 누수가 4번 이상 반복 발생 (#693, pgmq batchWrite, pipeline buffer)

## Executor Sizing 규칙

- Thread pool size는 HikariCP `maximumPoolSize`와 명시적으로 정렬
- 모든 `ThreadPoolTaskExecutor` bean에 `corePoolSize`, `maxPoolSize`, `queueCapacity`, `threadNamePrefix`를 YAML에 선언
- `ForkJoinPool.commonPool()` 또는 CompletableFuture 기본 executor를 DB-bound 작업에 사용 금지
- **근거**: executor-pool 불일치로 인한 exhaustion/starvation 4번 발생

## Graceful Shutdown

- Thread/Executor/Queue를 생성하는 모든 component에 graceful shutdown 필수
- Spring의 `DisposableBean` 또는 `@PreDestroy` 사용
- `spring.lifecycle.timeout-per-shutdown-phase` YAML 설정
- Shutdown 순서: 새 작업 차단 → in-flight drain → buffer flush → terminate
- **근거**: async component마다 shutdown logic 누락으로 4번 데이터 유실

## Fan-Out 제한

- 모든 병렬 fan-out에 bounded concurrency (Semaphore 또는 동등 mechanism) 필수
- 동시성 제한은 YAML config로 외부화
- 보수적 bound (2x available cores)에서 시작, 부하 테스트로 상향 조정

## Flat Work Queue 우선

- 중첩 CompletableFuture fan-out (message × preset × item) 금지
- Flat bounded work queue + fixed workers 사용
- `thenCombine`을 error-sensitive pipeline에 사용 금지 (per-item error isolation 필요)
- 연산 key 중복 제거 후 dispatch, 결과를 모든 requester에 매핑
- **근거**: Epic #732 pipeline 재구성

## Lock Scope는 실제 작업까지 커버

- Advisory/distributed lock은 보호할 작업이 완료될 때까지 유지
- `executeWithLock`에서 `CompletableFuture` 반환하면 lock이 async 작업 전에 해제됨
- 항상 확인: unlock 시점이 비즈니스 효과 발생 **이후**인가?
