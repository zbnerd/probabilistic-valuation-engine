# 부하테스트 규칙 (Load Test Rules)

## 명령어

```bash
RESET_ACTIVE_JOBS=1 RESET_VIEWS=1 COUNT=10000 CONCURRENCY=50 SAMPLE_INTERVAL=30 POST_SAMPLE_COUNT=6 ./load-test/run-v5-db-throughput.sh
```

**항상 `RESET_VIEWS=1` + `RESET_ACTIVE_JOBS=1` 함께 사용.** 캐시 히트가 섞이면 throughput 측정이 무의미.

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

## 실행 및 보고 규칙

- 부하테스트는 **백그라운드로 실행** (`run_in_background: true`)
- 부하테스트 스크립트의 샘플 출력은 백그라운드에서 오지 않으므로 **직접 DB 쿼리로 수집**
- 부하테스트 시작 전 **직접 TRUNCATE로 초기화** (RESET_VIEWS 스크립트에 의존하지 않음):
  ```bash
  source .env
  PSQL_DB_HOST=$(echo "$DB_URL" | sed -n 's|.*://\([^:/]*\).*|\1|p')
  PSQL_DB_PORT=${DB_PORT:-6543}
  PSQL_DB_NAME=$(echo "$DB_URL" | sed -n 's|.*/\([^?]*\).*|\1|p')
  PSQL_DB_USER=$(echo "$DB_URL" | sed -n 's|.*user=\([^&]*\).*|\1|p')
  PSQL_DB_PASS=$(echo "$DB_URL" | sed -n 's|.*password=\(.*\)|\1|p')

  PGPASSWORD="$PSQL_DB_PASS" psql "host=$PSQL_DB_HOST port=$PSQL_DB_PORT user=$PSQL_DB_USER dbname=$PSQL_DB_NAME sslmode=require" -c "
    TRUNCATE TABLE character_valuation_views;
    SELECT pgmq.purge_queue('external_api_queue');
    SELECT pgmq.purge_queue('result_ready_queue');
    SELECT pgmq.purge_queue('expectation_calc_high_queue');
  "
  ```
- **DB 연결은 `.env`의 `DB_URL` JDBC URL에서 파싱** — `psql "$DB_URL"` 직접 사용 불가 (JDBC 형식이므로)
- 서버 시작 완료(health check 200) 대기 후 아래 쿼리로 **30초마다 샘플 수집** — 총 6회:
  ```bash
  PGPASSWORD="$PSQL_DB_PASS" psql "host=$PSQL_DB_HOST port=$PSQL_DB_PORT user=$PSQL_DB_USER dbname=$PSQL_DB_NAME sslmode=require" -t -A -F',' -c "
    SELECT
      (SELECT count(*) FROM character_valuation_views) AS views,
      (SELECT count(*) FROM pgmq.q_external_api_queue WHERE visible_at <= now()) AS queue_external_api,
      (SELECT count(*) FROM pgmq.q_result_ready_queue WHERE visible_at <= now()) AS queue_result_ready,
      (SELECT count(*) FROM calculation_jobs WHERE status = 'API_REQUESTED') AS active_api_requested
  "
  ```
- 각 샘플에서 `delta_views`, `views_per_sec`를 계산하여 사용자에게 즉시 보고
- 6번째 샘플 후 load-test Python 프로세스와 bootRun 서버 중지
- 부하테스트 종료 후 slow task 분석:
  - `module-app/logs/load-test-bootrun-*.log`에서 StepTrace 및 slow task 분석
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
