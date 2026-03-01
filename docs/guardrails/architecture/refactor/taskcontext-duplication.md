---
id: GR-REFACTOR-016
category: architecture/refactor
severity: info
keywords: [logicexecutor, taskcontext, duplication, stack-frame, reflection]
languages: [java, kotlin]
---

# LogicExecutor TaskContext 패턴 중복

## DON'T (위반 사항/장애 원인)

### 중복 패턴
```java
// 모든 Service/Facade/Controller에서 반복
executor.execute(
    () -> doSomething(),
    TaskContext.of("ServiceName", "MethodName", identifier));
```

### 위험 요소
- **TaskContext 빌더 패턴 반복**: 423개 위치
- **문자열 기반 식별자**: 오타 위험, 리팩토링 어려움
- **메서드명 하드코딩**: IDE 리팩토링 시 동기화되지 않음

### 수치
- 중복 횟수: 423회

## DO (수정 방법/재발 방지)

### 수정 코드
```java
// 1. StackFrame 기반 자동 TaskContext 생성
public class TaskContext {

    public static TaskContext fromStack(Object... params) {
        StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
        String className = caller.getClassName();
        String methodName = caller.getMethodName();

        // SimpleClassName 추출
        String simpleName = className.substring(className.lastIndexOf('.') + 1);

        return new TaskContext(simpleName, methodName, params);
    }
}

// 2. 사용
public GameCharacter findCharacter(String userIgn) {
    return executor.execute(
        () -> doFind(userIgn),
        TaskContext.fromStack(userIgn)); // 자동으로 클래스명/메서드명 추출
}
```

### 개선 수치 (After)
- 코드 라인 수: 423 → 211 (50% 감소)
- 리팩토링 안전성 강화
- 로그 품질 향상 (일관된 네이밍)

### 핵심 원칙
1. **StackFrame 활용**: 자동으로 클래스명/메서드명 추출
2. **리팩토링 안전성**: IDE 리팩토링 시 자동 동기화
3. **일관된 로그**: 모든 TaskContext가 동일한 형식 사용

## 출처
- 문서: [docs/05_Reports/04_08_Refactor/duplicated-code-analysis.md](../../../05_Reports/04_08_Refactor/duplicated-code-analysis.md)
- 카테고리: P1 (중간 수준 중복)
