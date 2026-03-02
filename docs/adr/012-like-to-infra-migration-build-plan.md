# Like 패키지 Core/Infra 분리 - 빌드 플랜

## 1. 변경 대상

### module-core로 이관 (신규 생성)
- [ ] `FetchResult.java` → `core/dto/like/FetchResult.kt`
- [ ] `CompensationCommand.java` → `core/port/out/like/CompensationCommand.kt`

### module-infra로 이관 (후속 작업)
- [ ] `RedisCompensationCommand.java` → `infra/queue/like/compensation/`
- [ ] `LikeSyncFailedEvent.java` → `infra/queue/like/event/`
- [ ] `LikeSyncEventListener.java` → `infra/queue/like/listener/`
- [ ] `LikeSyncMetricsRecorder.java` → `infra/queue/like/metrics/`
- [ ] `OrphanKeyRecoveryService.java` → `infra/queue/like/recovery/`
- [ ] `realtime/*` → `infra/queue/like/realtime/`
- [ ] `strategy/*` → `infra/queue/like/strategy/`

## 2. 의존성 분석

### FetchResult 의존성
```
FetchResult
  ↑
  ├── CompensationCommand.save(FetchResult)
  ├── LikeSyncFailedEvent.fromFetchResult(FetchResult)
  └── LikeSyncService (사용)
```

### CompensationCommand 의존성
```
CompensationCommand (interface)
  ↑
  └── RedisCompensationCommand (구현체)
```

## 3. 컴파일 체크 포인트

### Phase 1: core 신규 생성 (기존 코드 유지)
```bash
./gradlew :module-core:compileKotlin
```
- 기존 Java 코드 그대로 유지
- Kotlin 버전 core에 신규 생성
- 중복 정의 없는지 확인

### Phase 2: import 경로 전환
```bash
./gradlew :module-app:compileJava
```
- module-app에서 core 새 경로 import
- 기존 service.v2.like.dto 제거

### Phase 3: infra 이관 및 전체 빌드
```bash
./gradlew build -x test
```

### Phase 4: 테스트 검증
```bash
./gradlew test
```

## 4. 롤백 전략

### 각 Phase별 롤백
- Phase 1: 신규 파일 삭제만으로 복구
- Phase 2: import 경로 원복
- Phase 3: git checkout -- 파일
- Phase 4: 커밋 전 취소

### 전체 롤백
```bash
git checkout -- module-core/ module-app/src/main/java/maple/expectation/service/v2/like/
```

## 5. 실행 순서

1. **core 신규 파일 생성** (현재)
   - FetchResult.kt 생성
   - CompensationCommand.kt 생성 (완료)
   - 컴파일 확인

2. **import 경로 전환**
   - module-app에서 새 경로 import
   - 기존 Java 파일 삭제

3. **infra 이관**
   - 나머지 15개 파일 이관

4. **테스트 검증**
   - 전체 테스트 통과 확인
