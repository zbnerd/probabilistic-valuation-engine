---
paths:
  - "**/build.gradle"
  - "**/build.gradle.kts"
  - "**/settings.gradle"
  - "**/settings.gradle.kts"
---

# 빌드 컨벤션 (Build Conventions)

## Library Module Plain JAR

- 모든 module에 plain JAR 출력 활성화: `tasks.named("jar") { enabled = true; archiveClassifier = "plain" }`
- `bootJar` 활성화는 `module-app`만
- 나머지 module은 library JAR로 downstream에 제공

## Kotlin → Java 컴파일 순서

- `compileJava`는 `compileKotlin`에 의존: `tasks.named('compileJava').configure { dependsOn(tasks.named('compileKotlin')) }`
- Kotlin compiler options: `jvmTarget = "21"`, `-Xjsr305=strict`

## JPA allOpen (Kotlin Entity Proxying)

- Kotlin JPA entity는 Hibernate proxy 생성을 위해 open 필요:
```groovy
allOpen {
    annotation('jakarta.persistence.Entity')
    annotation('jakarta.persistence.MappedSuperclass')
    annotation('jakarta.persistence.Embeddable')
}
```
- `module-app`, `module-infra`에 적용

## 설정 외부화

- Thread pool size, batch size, timeout, TTL, buffer capacity, queue depth를 코드에 hardcode 금지
- `@ConfigurationProperties` + YAML defaults 사용
- YAML에 각 값의 용도와 tuning range 주석 필수
- 기술 제거 후(Redis 등) 잔여 import, config, conditional annotation 확인
