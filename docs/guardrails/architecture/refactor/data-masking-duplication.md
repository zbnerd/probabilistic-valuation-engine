---
id: GR-REFACTOR-013
category: architecture/refactor
severity: info
keywords: [masking, duplication, string-utils, logging, security]
languages: [java, kotlin]
---

# 데이터 마스킹 로직 중복

## DON'T (위반 사항/장애 원인)

### 중복 코드
```java
// GameCharacterControllerV4.java
private String maskIgn(String ign) {
    if (ign == null || ign.length() < 2) return "***";
    return ign.charAt(0) + "***" + ign.substring(ign.length() - 1);
}

// PopularCharacterWarmupScheduler.java (동일한 로직)
private String maskIgn(String ign) {
    if (ign == null || ign.length() < 2) return "***";
    return ign.charAt(0) + "***" + ign.substring(ign.length() - 1);
}
```

### 위험 요소
- **동일한 마스킹 알고리즘 구현**: 2개 파일에서 중복
- **StringMaskingUtils 존재**: 이미 유틸리티가 있음에도 로컬 구현
- **마스킹 정책 불일치**: 각기 다른 구현으로 보안 취약점

### 수치
- 중복 횟수: 2회
- 로그 보안 취약: 가능

## DO (수정 방법/재발 방지)

### 수정 코드
```java
// StringMaskingUtils에 메서드 사용 (또는 이미 있으면 활용)
public class StringMaskingUtils {
    public static String maskIgn(String ign) {
        if (ign == null || ign.length() < 2) return "***";
        return ign.charAt(0) + "***" + ign.substring(ign.length() - 1);
    }

    public static String maskOcid(String ocid) { /* 기존 구현 */ }
    public static String maskAccountId(String accountId) { /* 기존 구현 */ }
}

// Controller에서 사용
import static maple.expectation.global.util.StringMaskingUtils.maskIgn;

log.debug("Processing: {}", maskIgn(userIgn));
```

### 개선 수치 (After)
- 코드 라인 수: 8 → 0 (삭제)
- 마스킹 정책 일관성 보장
- 로그 보안 강화

### 핵심 원칙
1. **기존 유틸리티 사용**: StringMaskingUtils에 이미 구현되어 있으면 활용
2. **static import**: 코드 간결성을 위해 static import 사용
3. **보안 정책 중앙화**: 모든 마스킹 로직을 한 곳에서 관리

## 출처
- 문서: [docs/05_Reports/04_08_Refactor/duplicated-code-analysis.md](../../../05_Reports/04_08_Refactor/duplicated-code-analysis.md)
- 카테고리: P1 (중간 수준 중복)
