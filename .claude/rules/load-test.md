# 부하테스트 규칙 (Load Test Rules)

## 명령어

```bash
RESET_ACTIVE_JOBS=1 COUNT=10000 CONCURRENCY=50 SAMPLE_INTERVAL=30 POST_SAMPLE_COUNT=6 ./load-test/run-v5-db-throughput.sh
```

## 스크립트 동작

- `localhost:8080`이 healthy하지 않으면 `:module-app:bootRun` 자동 시작
- `RESET_VIEWS=1` 시 `character_valuation_views` 삭제
- `RESET_ACTIVE_JOBS=1` 시 첫 `COUNT`개 CSV IGN의 active job을 `FAILED/LOAD_TEST_RESET`으로 마킹
- `load_test_v5.py` 실행 후 `SAMPLE_INTERVAL`초 간격으로 DB progress 샘플링

## 파괴적 플래그

- `RESET_VIEWS=1`, `RESET_ACTIVE_JOBS=1`은 **파괴적 DB 작업** — 사용자가 명시적으로 요청한 경우만 실행
- `RESET_ACTIVE_JOBS=1`은 `module-app/src/main/resources/data/userIgn_List.csv`에서 첫 `COUNT` IGN을 임시 테이블에 로드 후 매칭되는 active job만 업데이트 (전체 테이블 reset 금지)
- True cold-miss throughput test는 두 리셋 플래그 모두 필요. `character_valuation_views`만 리셋하면 active job이 `API_REQUESTED`/`RETRYING`에 남아 `createJob()`이 기존 job을 반환하고 throughput이 제한될 수 있음

## DB Progress Sampler 지표

샘플러가 리포트하는 항목:
- `character_valuation_views` count
- `pgmq.q_expectation_calc_high` depth
- `pgmq.q_external_api_queue` depth
- `pgmq.q_result_ready_queue` depth
- Active `API_REQUESTED` job count

**주의:** `q_expectation_calc_high=0`만으로 external/result pipeline이 drain되었다고 판단 금지. 모든 지표를 함께 확인.

## 탐색적 Worker-Pool Throughput Test

- 기본: DB progress 샘플 6개, 30초 간격 (`POST_SAMPLE_COUNT=6`, `SAMPLE_INTERVAL=30`)
- 6번째 샘플 후 load-test Python 프로세스와 bootRun 서버 중지
- 부하테스트 종료 후 slow task 분석:
  - `module-app/logs/load-test-bootrun-*.log`에서 slow task 분석
  - 각 샘플의 `delta_views`, `views_per_sec`, 에러, slow-task 카테고리 리포트
  - 스크립트 출력과 boot 로그 경로 보존

## 병목 분석 체크리스트

view count가 증가하지 않을 때 확인:
1. `ResultReadyProjectionWorker` 로그
2. `ResultProjection:ProjectBatch` slow task
3. `ReadModel:BestEffortBatchWrite` slow task
4. `UnexpectedRollbackException`
5. JDBC type error (`BadSqlGrammarException`)
6. `PgmqClient:Send:*` slow task
7. 외부 API 병목이 아닌 경우 위 항목을 먼저 확인

## 프로세스 정리

중단된 실행 후 잔여 프로세스 정리:
```bash
pgrep -af 'load_test_v5|python3 load_test|gradlew :module-app:bootRun|ExpectationApplication'
```
load-test 관련 프로세스만 종료. 다른 프로세스는 건드리지 않음.
