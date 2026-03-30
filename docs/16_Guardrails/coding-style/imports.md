---
id: GR-004
category: coding-style
severity: warning
keywords: [FQCN, fully qualified, import]
---

# FQCN 대신 import 사용

## DON'T (안티패턴)

```java
// Bad - FQCN 사용
org.springframework.stereotype.Service service = 
    new org.springframework.example.MyComponent();
```

**위험성:**
- 가독성 저하
- 코드가 길어짐
- IDE 지원 미흡

## DO (베스트 프랙티스)

```java
// Good - import 사용
import org.springframework.stereotype.Service;
import org.springframework.example.MyComponent;

@Service
MyComponent component = new MyComponent();
```

## 출처
- CLAUDE.md Section 17: FQCN 금지 규칙
