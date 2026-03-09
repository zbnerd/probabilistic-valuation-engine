# ADR-001: PostgreSQL 단일 데이터베이스 전략

## 상태
제안됨 (Proposed)

## 날짜
2026-03-09

## 결정자
MapleExpectation Team

## 관련 이슈
- #547: PostgreSQL + PGMQ Docker Compose
- #548: Kotlin 변환 기반 작업
- #551: ADR 문서화

## 컨텍스트

### 현재 아키텍처
MapleExpectation은 현재 3개의 데이터베이스를 운영:
1. **MySQL 8.0**: 영구 데이터 저장 (JPA/Hibernate)
2. **MongoDB 7.0**: V5 CQRS Read Side (이벤트 소싱)
3. **Redis 7.0 HA**: 분산 캐시, 분산 락, 작업 큐

### 문제점
- 인프라 복잡도 증가 (3개 DB 관리)
- 운영 비용 상승
- 데이터 일관성 보장 어려움
- Scale-out 시 복잡도 기하급수적 증가

## 결정

PostgreSQL 16을 단일 데이터베이스로 통합:
- **MySQL → PostgreSQL**: 표준 RDBMS 기능
- **MongoDB → PostgreSQL JSONB**: 문서 저장
- **Redis Queue → PGMQ**: 메시지 큐 (Redis Cache는 유지)

### 기술 스택
| 기존 | 신규 | 목적 |
|------|------|------|
| MySQL 8.0 | PostgreSQL 16 | 영구 데이터 |
| MongoDB 7.0 | PostgreSQL JSONB | 문서 저장 |
| Redis Queue | PGMQ | 메시지 큐 |
| Redis Cache | Redis Cache (유지) | 캐시 |

## 근거

### PostgreSQL 선택 이유
1. **ACID 준수**: 강력한 트랜잭션 지원
2. **JSONB**: MongoDB 대체 가능한 문서 저장
3. **PGMQ**: Redis Queue 대체 가능한 내장 메시지 큐
4. **성능**: 대용량 처리에 최적화
5. **오픈소스**: 라이선스 비용 없음

### PGMQ 선택 이유
1. **PostgreSQL 내장**: 별도 인프라 불필요
2. **Redis 호환 API**: 마이그레이션 용이
3. **트랜잭션 지원**: DB 트랜잭션 내 큐 작업
4. **내구성**: 디스크 기반 영속성

## 결과

### 긍정적
- 인프라 단순화 (3개 → 2개 DB)
- 운영 비용 절감
- 트랜잭션 내 큐 작업 가능
- Scale-out 용이

### 부정적
- 초기 마이그레이션 비용
- 학습 곡선
- Redis Cache 추가 유지 필요

### 위험 및 완화
| 위험 | 확률 | 영향 | 완화 전략 |
|------|------|------|----------|
| 데이터 손실 | 낮음 | 치명적 | 단계별 마이그레이션 + 백업 |
| 성능 저하 | 중간 | 높음 | 로드 테스트 + 튜닝 |
| PGMQ 미성숙 | 중간 | 중간 | Redis 폴백 유지 |

## 마이그레이션 계획

### Phase 1: 기반 구축 (현재)
- PostgreSQL + PGMQ Docker Compose
- Gradle 의존성 추가
- 테스트 인프라 구축

### Phase 2: MySQL → PostgreSQL
- Entity 마이그레이션
- Repository 마이그레이션
- 데이터 이관

### Phase 3: MongoDB → PostgreSQL JSONB
- Document → JSONB 변환
- Repository 마이그레이션
- CQRS 재설계

### Phase 4: Redis Queue → PGMQ
- 큐 로직 마이그레이션
- 폴백 테스트
- 모니터링 구축

## 참고 자료
- [PGMQ Documentation](https://github.com/tembo-io/pgmq)
- [PostgreSQL JSONB](https://www.postgresql.org/docs/current/datatype-json.html)
- [ADR Template](../98_Templates/)
