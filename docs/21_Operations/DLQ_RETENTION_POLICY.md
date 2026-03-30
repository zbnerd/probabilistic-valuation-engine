# 🔒 DLQ (Dead Letter Queue) Retention Policy

**버전**: 1.0
**마지막 업데이트**: 2026-02-05
**적용 범위**: probabilistic-valuation-engine Production DLQ (NexonApiDlq, DonationOutboxDlq)

---

## 📋 목차

1. [개요 (Overview)](#1-개요-overview)
2. [보관 정책 (Retention Policy)](#2-보관-정책-retention-policy)
3. [삭제 규칙 (Deletion Rules)](#3-삭제-규칙-deletion-rules)
4. [아카이빙 절차 (Archival Procedures)](#4-아카이빙-절차-archival-procedures)
5. [규정 준수 (Compliance)](#5-규정-준수-compliance)
6. [모니터링 (Monitoring)](#6-모니터링-monitoring)
7. [실행 절차 (Operations)](#7-실행-절차-operations)

---

## 1. 개요 (Overview)

### 1.1 목적

본 정책은 probabilistic-valuation-engine 프로젝트의 Dead Letter Queue(DLQ) 데이터 보관, 삭제, 아카이빙에 대한 표준 절차를 정의합니다.

**적용 대상**:
- `nexon_api_dlq` (Nexon API 실패 이벤트)
- `donation_outbox_dlq` (기타 Outbox 실패 이벤트)

### 1.2 Triple Safety Net (Evidence: ADR-016)

DLQ는 데이터 영구 손실 방지를 위한 3중 안전망의 최후 수단입니다:

```
1차: DB DLQ INSERT → nexon_api_dlq 테이블
2차: File Backup → /var/log/maple-expectation/dlq-backup.log
3차: Discord Critical Alert → #alerts 채널 알림
```

---

## 2. 보관 정책 (Retention Policy)

### 2.1 표준 보관 기간

| 데이터 유형 | 보관 기간 | 이유 |
|-------------|------------|------|
| **DLQ Records** | **30일** | 장애 분석, 재처리, 규정 준수 |
| **File Backup** | **90일** | 추가적인 사고 조사를 위한 백업 |
| **Discord Alerts** | **영구** | 기록 보관 (Discord 서버 정책 따름) |

### 2.2 보관 기간 결정 근거

**30일 보관의 이유**:

1. **장애 분석**: 대부분의 장애는 7일 이내에 발견 및 해결
2. **재처리 가능성**: 30일 이내에 외부 API 복구 가능성 높음
3. **규정 준수**: 금융 서비스 기준 30일 로그 보관 (일반적)
4. **저장소 비용**: 30일 보관 시 월 약 3GB 예상 ($0.23/GB, RDS MySQL)

**비용 산출**:
```
일일 DLQ 발생: 100건 (N19: 0.002% * 2,160,000)
건당 평균 크기: 1KB
월간 저장소: 100건 * 1KB * 30일 = 3MB
비용: 3MB * $0.23/GB = $0.0007/월 (무시할 수준)
```

### 2.3 예외적 연장 보관

다음 경우에는 30일을 초과하여 보관할 수 있습니다:

- [ ] **Active Investigation**: 진행 중인 장애 조건 (사유지 필요)
- [ ] **Legal Hold**: 법적 요청 시 (법무팀 요청)
- [ ] **Compliance Audit**: 감사 대상 기간 (감사팀 요청)

**연장 절차**:
1. Engineering Manager 승인
2. `docs/operations/DLQ_RETENTION_EXTENSION.md`에 사유 기록
3. 30일 단위로 재승인

---

## 3. 삭제 규칙 (Deletion Rules)

### 3.1 자동 삭제 (Automated Deletion)

**스케줄**: 매일 새벽 3시 (KST)
**방식**: Spring Batch Job

**삭제 조건**:
```sql
DELETE FROM nexon_api_dlq
WHERE created_at < DATE_SUB(NOW(), INTERVAL 30 DAY)
AND status != 'UNDER_INVESTIGATION';
```

**삭제 전 안전장치**:
1. [ ] 삭제 전일 백업 (S3에 export)
2. [ ] `UNDER_INVESTIGATION` status는 제외
3. [ ] 삭제 건수 Slack 알림 (#dlq-deletion)

### 3.2 수동 삭제 (Manual Deletion)

**트리거**:
- 저장소 한도 도달 시
- Data privacy 요청 시 (GDPR right to be forgotten)

**절차**:
```bash
# 1. 삭제 대상 확인
mysql> SELECT COUNT(*) FROM nexon_api_dlq
       WHERE created_at < DATE_SUB(NOW(), INTERVAL 30 DAY);

# 2. 삭제 전 백업
mysqldump -u root -p maple_expectation nexon_api_dlq \
  --where="created_at < DATE_SUB(NOW(), INTERVAL 30 DAY)" \
  > dlq_backup_$(date +%Y%m%d).sql

# 3. 삭제 실행
mysql> DELETE FROM nexon_api_dlq
       WHERE created_at < DATE_SUB(NOW(), INTERVAL 30 DAY);

# 4. 삭제 검증
mysql> SELECT ROW_COUNT();
```

**승인**: Engineering Manager 사전 승인 필수

### 3.3 Soft Delete (권장)

삭제 대신 `archived` 컬럼을 사용하는 방식 권장:

```sql
-- Soft delete
UPDATE nexon_api_dlq
SET archived = true, archived_at = NOW()
WHERE created_at < DATE_SUB(NOW(), INTERVAL 30 DAY);

-- 조회 시 archived 제외
SELECT * FROM nexon_api_dlq WHERE archived = false;
```

**장점**:
- 실수로 인한 data loss 방지
- 필요 시 언제든 복원 가능
- Audit trail 유지

---

## 4. 아카이빙 절차 (Archival Procedures)

### 4.1 아카이빙 대상

30일 보관 기간이 지난 DLQ 레코드 중:
- [ ] Root Cause 분석에 활용된 경우
- [ ] 패턴 발견에 기여한 경우
- [ ] 교육용 예시로 활용 가치가 높은 경우

### 4.2 아카이빙 형식

**1차 저장소**: S3 (Cold Storage)
- Bucket: `maple-expectation-dlq-archive`
- Prefix: `year=YYYY/month=MM/`
- Format: JSON Lines (NDJSON)

**2차 저장소**: File System (Local Backup)
- 경로: `/var/log/maple-expectation/dlq-archive/`
- Rotation: 매월
- 보관: 90일

### 4.3 아카이빙 절차

**Step 1: Export**

```bash
#!/bin/bash
# export_dlq_to_s3.sh

DATE=$(date +%Y%m%d)
YEAR=$(date +%Y)
MONTH=$(date +%m)

# MySQL에서 DLQ export
mysql -u root -p maple_expectation \
  -e "SELECT * FROM nexon_api_dlq
       WHERE created_at < DATE_SUB(NOW(), INTERVAL 30 DAY)
       AND archived = false" \
  | jq -r '. | @json' \
  > /tmp/dlq_export_${DATE}.jsonl

# S3에 upload
aws s3 cp /tmp/dlq_export_${DATE}.jsonl \
  s3://maple-expectation-dlq-archive/year=${YEAR}/month=${MONTH}/dlq_export_${DATE}.jsonl

# Local backup
cp /tmp/dlq_export_${DATE}.jsonl \
   /var/log/maple-expectation/dlq-archive/dlq_export_${DATE}.jsonl
```

**Step 2: 아카이빙 표시**

```sql
UPDATE nexon_api_dlq
SET archived = true, archived_at = NOW(), s3_location = 's3://maple-expectation-dlq-archive/year=2026/month=02/dlq_export_20260205.jsonl'
WHERE created_at < DATE_SUB(NOW(), INTERVAL 30 DAY);
```

**Step 3: 삭제**

```sql
-- Soft delete (권장)
UPDATE nexon_api_dlq
SET archived = true
WHERE created_at < DATE_SUB(NOW(), INTERVAL 30 DAY);

-- Hard delete (선택, 저장소 절약)
DELETE FROM nexon_api_dlq
WHERE archived = true
AND archived_at < DATE_SUB(NOW(), INTERVAL 7 DAY);
```

### 4.4 아카이빙 복원 (Restore)

```bash
# S3에서 download
aws s3 cp s3://maple-expectation-dlq-archive/year=2026/month=02/dlq_export_20260205.jsonl - | \
  jq -r '. | @sql' | \
  mysql -u root -p maple_expectation
```

---

## 5. 규정 준수 (Compliance)

### 5.1 데이터 보존 법규

| 규정 | 요구사항 | probabilistic-valuation-engine 준수 |
|------|----------|---------------------|
| **전자상거래법** | 3년 보안 점검 기록 | ✅ CloudTrail 3년 보관 |
| **개인정보 보호법** | 1년 이용 기록 | ✅ 30일 DLQ + 1년 액세스 로그 |
| **금융 서비스** | 30일~1년 장애 기록 | ✅ 30일 DLQ (계획) |
| **GDPR** | Right to be forgotten | ✅ 수동 삭제 절차 |

### 5.2 Privacy 이슈 (OCID 민감성)

DLQ에는 `ocid` (사용자 식별자)가 포함됩니다:

**민감도**: 중간 (Direct identifier)
**익명화**: 필요 시 OCID 해싱 고려

**Privacy Request 처리**:

```sql
-- 사용자 요청 시 DLQ 삭제
DELETE FROM nexon_api_dlq
WHERE ocid = 'requested_ocid'
AND created_at < DATE_SUB(NOW(), INTERVAL 30 DAY);
```

### 5.3 Audit Trail

모든 삭제/아카이빙 작업은 기록해야 합니다:

```sql
CREATE TABLE dlq_retention_audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  operation ENUM('DELETE', 'ARCHIVE', 'RESTORE'),
  record_count INT,
  performed_by VARCHAR(100),
  performed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  reason TEXT,
  s3_location VARCHAR(500),
  INDEX idx_performed_at (performed_at)
);
```

---

## 6. 모니터링 (Monitoring)

### 6.1 DLQ 모니터링

**Grafana Dashboard**: `maple-expectation-dlq`

| Panel | 쿼리 | 경고 |
|-------|------|------|
| DLQ 건수 | `SELECT COUNT(*) FROM nexon_api_dlq WHERE created_at >= NOW() - INTERVAL 1 DAY` | > 1000 |
| 30일 초과 건수 | `SELECT COUNT(*) FROM nexon_api_dlq WHERE created_at < NOW() - INTERVAL 30 DAY` | > 0 (삭제 미실행) |
| 저장소 사용량 | `SELECT SUM(LENGTH(payload)) / 1024 / 1024 FROM nexon_api_dlq` | > 10GB |
| 삭제 실패 | `SELECT COUNT(*) FROM dlq_retention_audit_log WHERE operation = 'DELETE' AND record_count = 0` | > 0 |

### 6.2 Alerting

**Slack #dlq-alerts**:

```yaml
# Alertmanager config
alerts:
  - name: DLQRetentionExceeded
    condition: dlq_age_days > 30
    for: 1h
    annotations:
      summary: "DLQ records older than 30 days not deleted"
      action: "Check deletion job logs"
```

---

## 7. 실행 절차 (Operations)

### 7.1 초기 설정 (One-time Setup)

**Step 1: Spring Batch Job 생성**

```java
// maple.expectation.batch.DlqRetentionJob
@Component
@RequiredArgsConstructor
public class DlqRetentionJob {

    private final NexonApiDlqRepository dlqRepository;
    private final AmazonS3 s3Client;
    private final LogicExecutor executor;

    @Scheduled(cron = "0 0 3 * * ?")  // 매일 새벽 3시
    public void archiveAndDeleteOldDlq() {
        executor.executeVoid(() -> {
            // 1. Export to S3
            String s3Location = exportToS3();

            // 2. Mark as archived
            markAsArchived(s3Location);

            // 3. Soft delete
            softDeleteOldRecords();

            // 4. Log audit
            logAudit("ARCHIVE_AND_DELETE", getRecordCount());
        }, TaskContext.of("DlqRetention", "ArchiveAndDelete"));
    }

    private String exportToS3() {
        // Implementation
    }

    private void markAsArchived(String s3Location) {
        // Implementation
    }

    private void softDeleteOldRecords() {
        int deleted = dlqRepository.archiveOldRecords(30);
        metrics.dlqArchived(deleted);
    }
}
```

**Step 2: S3 Bucket 생성**

```bash
aws s3 mb s3://maple-expectation-dlq-archive
aws s3api put-bucket-versioning \
  --bucket maple-expectation-dlq-archive \
  --versioning-configuration Status=Enabled
```

**Step 3: Audit Log 테이블 생성**

```sql
CREATE TABLE dlq_retention_audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  operation ENUM('DELETE', 'ARCHIVE', 'RESTORE'),
  record_count INT,
  performed_by VARCHAR(100) DEFAULT 'system',
  performed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  reason TEXT,
  s3_location VARCHAR(500),
  INDEX idx_performed_at (performed_at)
) ENGINE=InnoDB;
```

### 7.2 정기 점검 (Weekly Checklist)

- [ ] **월요일 09:00**: 지난주 DLQ 삭제 건수 확인
- [ ] **월요일 09:05**: S3 아카이빙 성공 여부 확인
- [ ] **월요일 09:10**: 저장소 사용량 확인 (10GB 미만 유지)

### 7.3 장애 시 복구 절차

**장애 시나리오 1: 삭제 Job 실패**

1. 실패 원인 확인 (CloudWatch Logs)
2. 수동 삭제 실행 (Section 3.2)
3. Job 재시작

**장애 시나리오 2: S3 upload 실패**

1. S3 권한 확인 (IAM role)
2. Local backup 확인 (`/var/log/maple-expectation/dlq-archive/`)
3. S3 연결 복구 후 재시도

**장애 시나리오 3: Data loss 발견**

1. 즉시 Engineering ManagerEscalation
2. S3 아카이브에서 복원
3. Local backup 확인
4. Root Cause 분석

---

## 8. 부록 (Appendix)

### 8.1 비용 산출 (Cost Breakdown)

**월간 비용**:

| 항목 | 용량 | 단가 | 비용 |
|------|------|------|------|
| RDS MySQL (DLQ) | 3MB | $0.23/GB | $0.0007 |
| S3 Standard (30일) | 3MB | $0.023/GB | $0.00007 |
| S3 Glacier (90일) | 9MB | $0.004/GB | $0.00004 |
| 합계 | - | - | **$0.0008/월** |

**연간 비용**: 약 $0.01 (무시할 수준)

### 8.2 관련 문서

- **ADR-016**: Nexon API Outbox Pattern (Triple Safety Net)
- **N19 Chaos Test**: DLQ 발생률 0.002% 검증
- **On-call Checklist**: 일일 DLQ 모니터링 절차

### 8.3 변경 이력

| 버전 | 일자 | 변경 사항 |
|------|------|----------|
| v1.0 | 2026-02-05 | 최초 작성 |

---

*이 정책은 probabilistic-valuation-engine 프로젝트의 데이터 보존 및 규정 준수를 위해 작성되었습니다.*
*모든 변경 사항은 Engineering Manager 승인 후 반영하세요.*
