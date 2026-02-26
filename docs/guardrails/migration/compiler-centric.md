# Compiler-Centric Migration Guardrails

> **핵심 원칙**: LLM은 컴파일러가 아니다. 큰 리팩토링을 한 방에 시키면 100% 터진다.
> **해결책**: 컴파일이 절대 깨지지 않게 작업을 쪼개고, 에이전트에게 "빌드 플랜"을 강제하라.

---

## GR-MIGRATION-001: Multi-File Migration Without Build Plan

### Problem
LLM이 의존성/빌드 그래프/생성코드/annotation processor 같은 "보이지 않는 제약"을 놓쳐서 대량 컴파일 오류 발생.

### Detection
- 3개 이상의 파일 동시 수정
- `.java` 삭제 + `.kt` 생성 패턴
- "migration", "refactor", "convert" 키워드

### DON'T
```
❌ 한 번에 여러 파일 마이그레이션
❌ 빌드 플랜 없이 리팩토링 시작
❌ 의존성 분석 없이 코드만 옮기기
```

### DO
```
✅ 코드 수정 전에 반드시 출력:
   1. 변경 대상 목록 (파일/클래스)
   2. 예상 영향 범위 (호출부/DI/serialization)
   3. 컴파일 체크 포인트 (어떤 gradle task를 돌릴지)
   4. 롤백 전략

✅ 변경 단위를 '컴파일 유닛' 기준으로:
   - 모듈 단위
   - 패키지 단위
   - 클래스 단위

✅ 기능 변화 0인 단계부터:
   - rename/move/signature 유지
```

### Build Plan Template
```markdown
## 마이그레이션 빌드 플랜

### 1. 변경 대상
- [ ] File1.java → File1.kt
- [ ] File2.java → File2.kt

### 2. 의존성 분석
- 호출부: [ServiceA, ServiceB]
- DI: @Autowired → @Inject
- Serialization: Jackson annotations

### 3. 컴파일 체크 포인트
- [ ] `./gradlew :module-infra:compileKotlin`
- [ ] `./gradlew :module-app:compileKotlin`
- [ ] `./gradlew test --tests "RelatedTest"`

### 4. 롤백 전략
- git stash 또는 feature branch
```

---

## GR-MIGRATION-002: Kotlin Interop - @JvmStatic/@JvmOverloads

### Problem
Java에서 호출되는 Kotlin API가 `@JvmStatic`/`@JvmOverloads` 없어서 런타임 오류.

### Detection
- `companion object` 내부 함수
- `@JvmStatic` 없는 정적 메서드 호출
- Java → Kotlin 호출 패턴

### DON'T
```kotlin
// ❌ Java에서 호출 불가
companion object {
    fun of(value: String): Result = Result(value)
}
```

### DO
```kotlin
// ✅ Java에서도 호출 가능
companion object {
    @JvmStatic
    fun of(value: String): Result = Result(value)

    @JvmStatic
    @JvmOverloads
    fun create(name: String, age: Int = 0): Person = Person(name, age)
}
```

### Checklist
- [ ] Java에서 호출되는 companion object 함수 → `@JvmStatic`
- [ ] 기본값 있는 파라미터 → `@JvmOverloads`
- [ ] 상수 → `const val` 또는 `@JvmField`

---

## GR-MIGRATION-003: Kotlin Interop - Nullability

### Problem
Java ↔ Kotlin 간 플랫폼 타입(nullability unknown)으로 인한 NPE.

### Detection
- Java 코드에서 가져온 타입
- `!!` (non-null assertion) 사용
- `?` 없는 nullable 가능성

### DON'T
```kotlin
// ❌ 플랫폼 타입 - NPE 위험
val name = javaObject.getName()  // String? or String?

// ❌ !! 남용
val value = map["key"]!!
```

### DO
```kotlin
// ✅ 명시적 nullability
val name: String? = javaObject.getName()  // nullable 명시
val nameSafe: String = javaObject.getName() ?: "default"  // Elvis

// ✅ Safe call + let
javaObject.getName()?.let { name ->
    // non-null context
}
```

### Checklist
- [ ] Java API 반환값 → `?` 명시적 표시
- [ ] `!!` 사용 → 근거 필요 (불가피한 경우만)
- [ ] `requireNotNull()` / `checkNotNull()` 고려

---

## GR-MIGRATION-004: Annotation Processor / Generated Code

### Problem
Dagger/Hilt, Room, Lombok, MapStruct, KAPT/KSP가 걸리면 컴파일 폭발.

### Detection
- `@Entity`, `@Dao` (Room)
- `@Module`, `@Component` (Dagger/Hilt)
- `@Mapper` (MapStruct)
- `@Data`, `@Builder` (Lombok)

### DON'T
```
❌ 생성코드 관련 모듈을 리팩토링 중에 건드림
❌ KAPT/KSP 설정 변경 없이 annotation processor 의존성만 수정
```

### DO
```
✅ 생성코드 모듈은 먼저 빌드 파이프라인 안정화
✅ Entity/DAO는 최소한의 변경만
✅ KAPT → KSP 마이그레이션은 별도 이슈로
```

### Safe Order
1. Plain Kotlin/Java 클래스 먼저 마이그레이션
2. Data class / DTO
3. Service / Repository (annotation 없는 것)
4. 마지막에 Entity / DAO / DI 모듈

---

## GR-MIGRATION-005: Error Clustering (Bulk Fix)

### Problem
"에러 하나 → 수정 → 또 에러" 패턴은 LLM 호출량 폭발.

### Detection
- 컴파일 오류 10개 이상
- 동일한 원인의 반복 에러

### DON'T
```bash
# ❌ 에러마다 개별 수정
./gradlew compileKotlin  # error 1
# 수정
./gradlew compileKotlin  # error 2
# 수정
... (무한 루프)
```

### DO
```bash
# ✅ 에러 모아서 한 번에 분석
./gradlew :module:compileKotlin :module:compileJava --no-daemon 2>&1 | tee build.log

# 에러만 추출
grep -n "e: " build.log | head -200
grep -n "error:" build.log | head -200

# LLM에게 전달:
# "에러 1~50번을 같은 원인인지 클러스터링하고, 원인별로 한 번에 패치해라"
```

### Error Clustering Prompt
```
다음 컴파일 에러를 분석해서:
1. 동일한 원인으로 묶을 수 있는 에러들을 클러스터링
2. 각 클러스터별로 한 번에 수정할 패치 제안
3. 우선순위 정렬 (의존성 고려)

[에러 로그 붙여넣기]
```

---

## GR-MIGRATION-006: Compile Unit Workflow

### Problem
여러 파일을 동시에 수정하면 어디서 터졌는지 추적 불가.

### Detection
- Write/Edit 호출 시 다중 파일
- 한 세션에서 5개 이상 파일 수정

### DON'T
```
❌ 10개 파일 한 번에 수정
❌ 수정 후 컴파일 안 해봄
❌ 터지면 원인 파악 불가
```

### DO
```
✅ 파일 단위 컴파일:
   1. 파일 하나 수정
   2. 즉시 ./gradlew :module:compileKotlin
   3. 통과하면 다음 파일
   4. 실패하면 그 파일에서 끝내고 수정

✅ 패키지 단위:
   1. 패키지 전체 수정
   2. ./gradlew :module:compileKotlin
   3. 통과하면 커밋
   4. 실패하면 롤백 후 재시도
```

### Compile Guard Script
```bash
#!/bin/bash
# compile-guard.sh - 파일 단위 컴파일 가드

FILE=$1
MODULE=$(echo $FILE | grep -oP 'module-\w+')

echo "Compiling $MODULE after $FILE modification..."
./gradlew :$MODULE:compileKotlin :$MODULE:compileJava

if [ $? -eq 0 ]; then
    echo "✅ Compile passed for $FILE"
else
    echo "❌ Compile failed for $FILE"
    echo "Rollback suggestion: git checkout -- $FILE"
    exit 1
fi
```

---

## Summary Checklist

### Before Migration
- [ ] 빌드 플랜 작성 (변경 대상, 의존성, 체크포인트)
- [ ] Annotation processor 모듈 식별
- [ ] Java→Kotlin interop 지점 파악 (@JvmStatic 필요한 곳)

### During Migration
- [ ] 파일/패키지 단위로만 수정
- [ ] 수정 즉시 컴파일
- [ ] 실패 시 롤백 후 재시도

### After Migration
- [ ] 에러 클러스터링으로 묶어서 수정
- [ ] 전체 테스트 통과 확인
- [ ] Kotlin interop 규칙 준수 확인

---

## References
- [Kotlin Java Interop](https://kotlinlang.org/docs/java-to-kotlin-interop.html)
- [Spring Boot Kotlin Best Practices](https://docs.spring.io/spring-boot/docs/current/reference/html/howto.html#howto.kotlin)
- [KAPT vs KSP](https://kotlinlang.org/docs/ksp-overview.html)
