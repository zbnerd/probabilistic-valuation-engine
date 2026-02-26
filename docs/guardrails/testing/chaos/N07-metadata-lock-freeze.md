---
id: GR-NIGHTMARE-N07
category: testing/chaos
severity: critical
keywords: [metadata lock, ddl, freeze, pt-online-schema-change, gh-ost]
languages: [java, kotlin, sql]
---

# N07: Metadata Lock Freeze

## DON'T (안티패턴)

```sql
-- 운영 환경에서 직접 DDL 실행 (위험!)
ALTER TABLE users ADD COLUMN email VARCHAR(255);

-- 문제: 장시간 실행 중인 SELECT 트랜잭션이 있으면
-- DDL이 Metadata Lock 획득을 위해 대기
-- 후속 SELECT도 모두 대기열에 추가 → 전체 Freeze!
```

**장애 수치 (Before):**
- DDL 실행 시 후속 쿼리 블로킹: 10+ 건
- Query 대기 시간: 3000+ ms
- Metadata Lock 대기 시간: 무제한
- 서비스 영향: 전체 테이블 Freeze

## DO (베스트 프랙티스)

```bash
# pt-online-schema-change 사용 (무중단 DDL)
pt-online-schema-change \
  --alter "ADD COLUMN email VARCHAR(255)" \
  --chunk-size=1000 \
  --max-load Threads_running=25 \
  --critical-load Threads_running=1000 \
  D=maple_expectation,t=users \
  --execute

# 또는 gh-ost 사용 (GitHub Online Schema Tool)
gh-ost \
  --database=maple_expectation \
  --table=users \
  --alter="ADD COLUMN email VARCHAR(255)" \
  --allow-on-master \
  --execute
```

**개선 수치 (After):**
- DDL 실행 중 후속 쿼리: 정상 처리
- Query 대기 시간: < 10ms
- 서비스 영향: 없음 (무중단)
- 완료 시간: 대용량 테이블도 안전 완료

## 핵심 원칙

1. **Online Schema Change 도구 사용**: pt-online-schema-change, gh-ost
2. **저부하 시간대 DDL**: 가능하면 새벽 시간대 배포
3. **트랜잭션 타임아웃**: 장시간 트랜잭션 자동 종료
4. **DDL 사전 고지**: 배포 팀과 협의

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N07-metadata-lock-freeze.md`
- Nightmare Test N07: Metadata Lock Freeze
- Test Class: `MetadataLockFreezeNightmareTest`
