# 작업 규칙 (Workflow Rules)

## 10. Definition of Done

- [ ] ADR 문서 작성 (구현 작업만)
- [ ] Unit 테스트 통과 (`./gradlew test`)
- [ ] 서버 구동 후 API 런타임 검증 (커밋/푸시 전 필수)
- [ ] CLAUDE.md 원칙 준수
- [ ] 통합테스트 금지 (Testcontainers 포함) - Issue #207
- [ ] 브랜치 생성

**서버 런타임 검증 절차 (커밋 전 필수):**

`module-app`은 레거시. 아래 4개 모듈이 현재 활성 서비스:

| 모듈 | 용도 | Port |
|------|------|------|
| `module-external-api` | 외부 API 호출 파이프라인 | 8081 |
| `module-calculator` | 계산 파이프라인 | 8082 |
| `module-synchronizer` | Read model 동기화 | 8083 |
| `module-rest-controller` | REST API 엔드포인트 | 8080 |

1. 수정한 모듈에 해당하는 `bootRun`으로 서버 구동:
   ```bash
   set -a && source .env && set +a
   ./gradlew :module-rest-controller:bootRun    # REST API
   ./gradlew :module-external-api:bootRun       # External API
   ./gradlew :module-calculator:bootRun         # Calculator
   ./gradlew :module-synchronizer:bootRun       # Synchronizer
   ```
2. API 검증:
   ```bash
   curl -s -w "\nHTTP %{http_code}" "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
   ```
   - 경로: `/api/v5/characters` (복수형 주의)
3. **202는 접수일 뿐, 실제 성공 여부는 서버 로그로 확인** (해당 모듈의 `logs/` 디렉토리)
   ```bash
   # 계산 완료 로그 확인 (필수)
   grep "Calculation completed" module-calculator/logs/app.log | tail -5
   # 에러 로그 확인 (ERROR가 없어야 함)
   grep "ERROR" <module>/logs/app.log | tail -10
   ```
   - 성공 기준: `Calculation completed with result saved` 로그 확인 + `ERROR` 없음
   - 202만 보고 성공 판단 금지 — 비동기 파이프라인 전체 완료까지 로그로 추적
4. **컴파일 + 테스트 통과 ≠ 런타임 정상 동작 보장** — 반드시 실제 서버에서 검증

## 11. 검증 명령어

```bash
./gradlew compileKotlin compileJava --continue  # 컴파일 확인
./gradlew test                        # 전체 테스트
```
- 컴파일검증시 --continue 반드시 사용할것.
- 컴파일, 테스트 검증시 처음부터 실패하는경우, 에러나는경우만 메시지 나타나도록 할것. 없으면 성공.

## 12. Flaky Test Prevention
- kotlin `delay()` 사용금지
- `Thread.sleep()` 금지 → `Awaitility` 사용
- 테스트 간 상태 공유 금지
- `@DirtiesContext` 남용 금지

## 13. 부하테스트 (Load Test)

**기본 명령어:**
```bash
RESET_ACTIVE_JOBS=1 COUNT=10000 CONCURRENCY=50 SAMPLE_INTERVAL=30 POST_SAMPLE_COUNT=6 ./load-test/run-v5-db-throughput.sh
```
- `RESET_ACTIVE_JOBS=1`: 첫 `COUNT`개 CSV IGN의 active job을 FAILED/LOAD_TEST_RESET으로 마킹 (파괴적 — 명시적 요청 시만)
- `RESET_VIEWS=1` 추가 시 `character_valuation_views` 초기화 (파괴적)
- 부하테스트 전 필수: `RESET_ACTIVE_JOBS=1`로 stale job 정리
- 각 모듈의 `logs/` 디렉토리에 부트 로그 기록
