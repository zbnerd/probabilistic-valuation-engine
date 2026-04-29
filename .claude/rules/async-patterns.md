# 비동기 프로그래밍 패턴 (Async Patterns)

## join() / get() / runBlocking 금지

서버 코드(Controller, Service, Worker)에서 `join()`, `get()`, `runBlocking` 사용 금지.

**금지 패턴:**
- `thread.join()` / `future.join()` / `future.get()` — 스레드 블로킹
- `runBlocking { }` — 코루틴 장점 상실, blocking 세계로 회귀
- 코루틴 안에서 `Thread.join()` — 코루틴을 블로킹
- lock 잡은 상태에서 `join()` — 데드락 위험

**대체 패턴:**

| 목적 | Kotlin | Java |
|------|--------|------|
| 결과 없는 비동기 | `launch { }` | `runAsync()` |
| 결과 있는 비동기 | `async { }.await()` | `supplyAsync()` |
| 결과 변환 | suspend 함수 | `thenApply()` |
| 다음 비동기 연결 | 그냥 suspend 호출 | `thenCompose()` |
| 여러 결과 합치기 | 여러 `async` 후 `await` | `thenCombine()` |
| 예외 처리 | `try/catch` | `exceptionally()`, `handle()` |

**핵심:** "기다리지 말고, 이어서 실행되게 만들어라"

- Kotlin: `suspend` 중심 설계, `launch` (결과 불필요) / `async` (결과 필요)
- Java: `CompletableFuture` 체이닝 (`thenApply`, `thenCompose`, `thenCombine`)
- 병렬 실행 시 모든 `async`를 먼저 생성한 후 `await` (순차 `await`는 순차 실행과 동일)

## CompletionException 언래핑

- `CompletableFuture.join()` / `.get()`은 `CompletionException` / `ExecutionException`으로 래핑
- `instanceof` 체크는 항상 cause에 대해 수행: `ex.cause instanceof XxxException`
- 래핑을 무시하면 비즈니스 exception 타입 매칭 실패 → 에러 처리 스킵

## Resource Lifecycle: finally 보장

- 모든 resource 획득(lock, counter increment, connection)은 finally-equivalent 블록에서 해제
- LogicExecutor의 `executeWithFinally` 또는 `CompletionStage.whenComplete`(success/failure 모두) 사용
- increment-then-decrement 패턴에서 decrement 누락은 버그
- **근거**: finally/lifecycle cleanup 누락이 8+ PR에서 P1 이슈 (#706, #709, #725, #729)
