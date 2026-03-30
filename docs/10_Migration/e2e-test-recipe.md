# PostgreSQL Migration: E2E Test Recipe

## 개요
PostgreSQL 마이그레이션 검증을 위한 End-to-End 테스트 가이드.

## 관련 이슈
- #547: PostgreSQL + PGMQ Docker Compose
- #548: Kotlin 변환 기반 작업
- #551: ADR 문서화

---

## Phase 1: Infrastructure Verification

### 1.1 PostgreSQL 컨테이너 시작
```bash
# PostgreSQL 서비스만 시작
docker-compose up -d postgres

# 로그 확인
docker-compose logs -f postgres
```

**예상 결과:**
- 컨테이너가 정상적으로 시작됨
- "PostgreSQL init process complete" 로그 확인

### 1.2 PostgreSQL 연결 확인
```bash
# 컨테이너 내부에서 연결 테스트
docker exec -it maple-postgres psql -U maple -d maple_expectation

# 버전 확인
SELECT version();
```

**예상 결과:**
- PostgreSQL 16.x 버전 표시
- psql 프롬프트 진입 성공

### 1.3 PGMQ Extension 확인
```bash
# PGMQ 확장 설치 확인
docker exec -it maple-postgres psql -U maple -d maple_expectation \
  -c "SELECT * FROM pg_extension WHERE extname = 'pgmq';"

# 생성된 큐 확인
docker exec -it maple-postgres psql -U maple -d maple_expectation \
  -c "SELECT * FROM pgmq.list_queues();"
```

**예상 결과:**
- pgmq 확장이 installed로 표시
- v4_buffer_queue, v5_event_queue, donation_outbox_queue 큐 목록

### 1.4 PGMQ 기본 동작 테스트
```bash
# 메시지 전송
docker exec -it maple-postgres psql -U maple -d maple_expectation \
  -c "SELECT pgmq.send('v4_buffer_queue', '{\"test\": \"data\"}');"

# 메시지 수신
docker exec -it maple-postgres psql -U maple -d maple_expectation \
  -c "SELECT pgmq.read('v4_buffer_queue', 30, 1);"

# 메시지 삭제
docker exec -it maple-postgres psql -U maple -d maple_expectation \
  -c "SELECT pgmq.delete('v4_buffer_queue', 1);"
```

**예상 결과:**
- send: 메시지 ID 반환
- read: 메시지 내용 반환
- delete: true 반환

---

## Phase 2: Spring Boot Integration

### 2.1 Build
```bash
# 전체 빌드 (테스트 제외)
./gradlew clean build -x test
```

**성공 기준:**
- BUILD SUCCESSFUL
- 컴파일 오류 없음

### 2.2 Spotless Check (ktlint)
```bash
# 코드 포맷팅 검증
./gradlew spotlessCheck
```

**성공 기준:**
- BUILD SUCCESSFUL
- ktlint 검증 통과

### 2.3 Application 시작 (기본 프로필)
```bash
# 로컬 프로필로 시작 (MySQL + Redis + MongoDB)
./gradlew bootRun --args='--spring.profiles.active=local'
```

**성공 기준:**
- Spring Boot 시작 성공
- 모든 데이터베이스 연결 성공

### 2.4 PostgreSQL 연결 테스트 (선택)
```bash
# PostgreSQL 프로필로 시작 (향후 구현)
./gradlew bootRun --args='--spring.profiles.active=pglocal'
```

**성공 기준:**
- PostgreSQL 연결 성공
- HikariCP 풀 초기화

---

## Phase 3: Testcontainers Verification

### 3.1 PostgreSQL Testcontainers 테스트
```bash
# PostgreSQL 관련 테스트만 실행
./gradlew test --tests "*Postgres*"
```

**성공 기준:**
- 테스트 컨테이너 시작 성공
- PostgreSQL 연결 성공
- 모든 테스트 통과

### 3.2 통합 테스트
```bash
# 전체 테스트 실행
./gradlew test
```

**성공 기준:**
- 모든 테스트 통과
- 플래키 테스트 없음

---

## Success Criteria Checklist

### Infrastructure
- [ ] `docker-compose up -d postgres` 성공
- [ ] PostgreSQL 컨테이너 healthy 상태
- [ ] PGMQ extension 설치 확인
- [ ] 기본 큐 생성 확인 (v4_buffer_queue, v5_event_queue, donation_outbox_queue)

### Build
- [ ] `./gradlew build -x test` 성공
- [ ] `./gradlew spotlessCheck` 통과

### Application
- [ ] Spring Boot 시작 성공
- [ ] 데이터베이스 연결 성공
- [ ] 헬스 체크 정상

### Testing
- [ ] Testcontainers 테스트 통과
- [ ] 전체 테스트 스위트 통과

---

## Troubleshooting

### PostgreSQL 컨테이너가 시작되지 않음
```bash
# 포트 충돌 확인
lsof -i :5432

# 기존 PostgreSQL 중지
sudo systemctl stop postgresql
```

### PGMQ Extension 설치 실패
```bash
# PostgreSQL 16 확인
docker exec -it maple-postgres psql -c "SELECT version();"

# 수동 설치
docker exec -it maple-postgres psql -U maple -d maple_expectation \
  -c "CREATE EXTENSION IF NOT EXISTS pgmq;"
```

### 연결 거부 오류
```bash
# 컨테이너 상태 확인
docker-compose ps

# 네트워크 확인
docker network inspect maple-network
```

---

## 참고 자료
- [PGMQ Documentation](https://github.com/tembo-io/pgmq)
- [Testcontainers PostgreSQL](https://testcontainers.com/modules/postgresql/)
- [Spring Boot PostgreSQL](https://spring.io/guides/gs/accessing-data-postgresql/)
