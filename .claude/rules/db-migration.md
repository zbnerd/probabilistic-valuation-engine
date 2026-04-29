---
paths:
  - "**/migration/**"
  - "**/db/migration/**"
  - "**/*.sql"
---

# DB Migration 규칙 (Migration Completeness)

## 필수 규칙

- 코드에서 참조하는 모든 DB table에 대응하는 CREATE migration 필수
- 암시적 table 생성 금지 (코드에서 table 참조 = migration 필요)
- Migration은 idempotent해야 함: `CREATE IF NOT EXISTS`, `INSERT ... ON CONFLICT`
- 새 index는 참조하는 table definition과 함께 작성
- **근거**: #715 (cache_storage CREATE migration 누락), #663 (column migration idempotency)

## Migration 리뷰 체크리스트

- [ ] table 존재 여부
- [ ] index 존재 여부
- [ ] column이 code entity와 일치
- [ ] rollback path 정의
- [ ] unique index가 모든 동시성 상태 커버 (NULL 행 허용 시 중복 가능성 검토)
- [ ] enum 값 제거 시 기존 persisted row에 해당 값 있는지 확인
- [ ] 새 기능이 PGMQ queue 사용 시 migration에서 queue 생성

## Forward Compatibility

- Migration은 실행 중인 코드와 호환되어야 함
- Enum 값 제거 시 `valueOf()` crash 방지
- Unique index 변경 시 동시 실행 코드가 생성할 수 있는 모든 상태 커버
- **근거**: #768 (unique index NULL 행), #769 (enum value 제거), #771 (queue 미생성)
