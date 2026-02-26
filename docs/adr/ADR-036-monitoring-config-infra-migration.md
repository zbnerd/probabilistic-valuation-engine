# ADR-036: monitoring/config 패키지 → module-infra 이관

## 상태 (Status)
**Proposed** - 2026-02-26

## 문맥 (Context)

### 현재 상황
`module-app`에 위치한 `monitoring` 패키지(37개 파일)와 `config` 패키지(12개 파일)가 인프라 관심사를 다루고 있습니다. Clean Architecture 원칙에 따라 인프라 관련 코드는 `module-infra`로 이관이 필요합니다.

**이관 대상:**
| 패키지 | 파일 수 | 비고 |
|--------|---------|------|
| monitoring/copilot/* | 28개 | AI 기반 모니터링 |
| monitoring/ai/* | 5개 | SRE AI 서비스 |
| monitoring/security/* | 1개 | PII 마스킹 |
| monitoring/context/* | 1개 | 시스템 컨텍스트 |
| monitoring/throttle/* | 1개 | 알림 스로틀링 |
| monitoring/collector/* | 8개 (Kotlin) | 메트릭 수집 |
| monitoring/*.kt | 1개 (Kotlin) | Alert Service |
| config/Buffer* | 2개 | 버퍼 설정 |
| config/Batch* | 2개 | 배치 설정 |
| config/Like* | 3개 | 좋아요 동기화 설정 |
| config/Equipment* | 1개 | Executor 설정 |
| config/Event* | 1개 | 이벤트 컨슈머 설정 |
| config/Maplestory* | 1개 | API 설정 |
| config/OpenTelemetry* | 1개 | OTel 설정 |
| config/Calculation* | 1개 | 계산 설정 |

**유지 대상 (8개):**
- CacheConfig.java (TieredCacheManager 사용처)
- TemporaryAdapterConfig.java (비즈니스 어댑터)
- AppProperties.java, CorsProperties.java, WebConfig.java
- OpenApiConfig.java, DataInitializer.java, LookupTableInitializer.java

### 최근 이관 사례 (0f2bd08)
BufferRecoveryScheduler 이관 시 양쪽 모듈에 동일 클래스 존재 이슈 발생

## 문제 (Problem)

### P0 리스크 (Critical)
1. **순환 의존성**: infra ↔ app 양방향 참조 시 컴파일 실패
2. **Bean 중복 등록**: 동일 Bean 양 모듈에서 등록 시 시작 실패
3. **@Transactional 경계 오류**: 트랜잭션이 잘못된 모듈에 위치
4. **핵심 설정 누락**: CacheConfig, BufferConfig 이관 실패

### P1 리스크 (High)
1. **AOP 동작 실패**: Aspect 패키지 변경 시 Pointcut 누락
2. **스케줄러 중복 실행**: @Scheduled 분산 환경 중복 실행
3. **캐시 무효화 호환성**: 캐시 키 불일치
4. **메트릭 수집 누락**: Micrometer 메트릭 누락

## 결정 (Decision)

### 단계적 이관 전략 채용

**원칙:**
1. **단방향 의존성**: module-app → module-infra 만 허용
2. **Interface 분리**: 순환 참조 방지를 위한 인터페이스 도입
3. **Bean 격리**: `@ConditionalOnMissingBean` 활용
4. **Java → Kotlin 변환**: 이관 시 Kotlin으로 변환

**이관 순서:**
```
Phase 0: ADR 작성 + 의존성 분석
Phase 1: 의존성 없는 파일 이관 (copilot/model, security, context, throttle, collector)
Phase 2: Properties 파일 이관 (BufferProperties, BatchProperties, CalculationProperties)
Phase 3: 독립적 인프라 설정 이관 (OpenTelemetryConfig, MaplestoryApiConfig, EventConsumerConfig)
Phase 4: 의존성 있는 설정 이관 (BufferConfig, BatchConfig, LikeSyncConfig 등)
Phase 5: Executor 설정 이관 (EquipmentProcessingExecutorConfig) - P0
Phase 6: Monitoring 의존성 체인 이관 (copilot/client, detector, pipeline, scheduler)
Phase 7: 정리 및 검증
```

## 구현 (Implementation)

### 디렉토리 구조
```
module-infra/src/main/kotlin/maple/expectation/infrastructure/
├── monitoring/
│   ├── copilot/
│   │   ├── model/      # 14개 (Phase 1)
│   │   ├── client/     # 2개 (Phase 6)
│   │   ├── detector/   # 1개 (Phase 6)
│   │   ├── dedup/      # 2개 (Phase 6)
│   │   ├── ingestor/   # 1개 (Phase 6)
│   │   ├── notifier/   # 1개 (Phase 6)
│   │   ├── config/     # 1개 (Phase 6)
│   │   ├── pipeline/   # 3개 (Phase 6)
│   │   └── scheduler/  # 1개 (Phase 6)
│   ├── ai/
│   │   ├── *.kt        # 5개 (Phase 6)
│   │   └── config/     # 2개 (Phase 6)
│   ├── security/       # 1개 (Phase 1)
│   ├── context/        # 1개 (Phase 1)
│   ├── throttle/       # 1개 (Phase 1)
│   └── collector/      # 8개 (Phase 1, 이미 Kotlin)
└── config/
    ├── BufferProperties.kt    # Phase 2
    ├── BatchProperties.kt     # Phase 2
    ├── CalculationProperties.kt # Phase 2
    ├── OpenTelemetryConfig.kt # Phase 3
    ├── MaplestoryApiConfig.kt # Phase 3
    ├── EventConsumerConfig.kt # Phase 3
    ├── BufferConfig.kt        # Phase 4
    ├── BatchConfig.kt         # Phase 4
    ├── LikeSyncConfig.kt      # Phase 4
    ├── LikeBufferConfig.kt    # Phase 4
    ├── LikeRealtimeSyncConfig.kt # Phase 4
    └── EquipmentProcessingExecutorConfig.kt # Phase 5
```

### Java → Kotlin 변환 규칙
| Lombok | Kotlin |
|--------|--------|
| @RequiredArgsConstructor | Primary Constructor (val/var) |
| @Data | data class |
| @Builder | @Builder 어노테이션 |
| @Slf4j | @Slf4j + companion object |
| @NoArgsConstructor | Secondary Constructor |

## 결과 (Consequences)

### 긍정적 효과
1. **Clean Architecture 준수**: 인프라 코드가 올바른 모듈에 위치
2. **의존성 명확화**: module-app → module-infra 단방향
3. **재사용성 향상**: 인프라 코드를 다른 모듈에서도 사용 가능
4. **테스트 격리**: 인프라 테스트를 독립적으로 수행 가능

### 부정적 효과
1. **대량 파일 이동**: 49개 파일 이관 필요
2. **import 경로 변경**: 모든 참조 파일 수정 필요
3. **일시적 빌드 불안정**: 이관 중 컴파일 오류 가능

### 완화 방안
1. **단계적 이관**: Phase별로 검증하며 진행
2. **자동화**: import 경로 일괄 수정 스크립트 활용
3. **CI/CD 검증**: 각 Phase마다 빌드 및 테스트 실행

## 의존성 분석 상세 (Dependency Analysis)

### 순환 의존성 확인 결과 (2026-02-26)
```bash
# module-app → module-infra 의존성 (정상)
./gradlew :module-app:dependencies --configuration compileClasspath
+--- project :module-infra  ✓

# module-infra → module-app 의존성 (순환 없음)
./gradlew :module-infra:dependencies --configuration compileClasspath
(의존성 없음) ✓
```
**결과: 순환 의존성 없음** - module-app → module-infra 단방향

### Bean 등록 현황
- **module-app monitoring/config**: 53개 Bean
- **module-infra**: 1개 Bean (BufferRecoveryScheduler)

### Monitoring → module-infra 의존성 (22건)
| 파일 | 의존 대상 |
|------|----------|
| SystemContextProvider | LogicExecutor, TaskContext |
| TimeBasedSlidingWindowStrategy | LogicExecutor, TaskContext |
| SignalDefinitionLoader | LogicExecutor, TaskContext |
| AnomalyDetectionOrchestrator | LogicExecutor, TaskContext |
| AlertNotificationService | LogicExecutor, TaskContext |
| MonitoringCopilotScheduler | LogicExecutor, TaskContext |
| AlertThrottler | LogicExecutor, TaskContext |
| GrafanaJsonIngestor | LogicExecutor, TaskContext |
| PrometheusClient | LogicExecutor, TaskContext |
| MonitoringCopilotConfig | DiscordTimeoutProperties, TimeoutProperties, LogicExecutor |
| AiSreService | LogicExecutor, TaskContext |
| AiResponseParser | LogicExecutor, TaskContext |
| DiscordNotifier | DiscordTimeoutProperties, LogicExecutor, TaskContext |

### Config → module-infra 의존성 (21건)
| 파일 | 의존 대상 |
|------|----------|
| WebConfig | MDCFilter |
| LikeSyncConfig | LogicExecutor |
| LikeBufferConfig | LogicExecutor, AtomicLikeToggleExecutor, LikeSyncExecutor, PartitionedFlushStrategy, RedisLikeBufferStorage |
| LookupTableInitializer | LogicExecutor, TaskContext, ExceptionTranslator |
| CacheConfig | RestrictedCacheManager, TieredCacheManager, CacheProperties, LogicExecutor |
| EquipmentProcessingExecutorConfig | ExecutorProperties |
| CorsProperties | ValidCorsOrigin |
| BufferConfig | DiscordTimeoutProperties, MonitoringThresholdProperties |
| MaplestoryApiConfig | NexonApiProperties |
| DataInitializer | LogicExecutor, TaskContext |
| LikeRealtimeSyncConfig | TieredCacheManager, LogicExecutor |

### 의존성 매트릭스 (이관 Phase별)

#### Monitoring 패키지
| 카테고리 | 파일 수 | executor 의존 | config 의존 | 이관 Phase |
|----------|--------|--------------|-------------|-----------|
| copilot/model | 13 | - | - | Phase 1 |
| security | 1 | - | - | Phase 1 |
| context | 1 | ✓ | - | Phase 6 |
| throttle | 1 | ✓ | - | Phase 6 |
| ai/* | 5 | ✓ | - | Phase 6 |
| ai/config | 2 | - | - | Phase 5 |
| copilot/pipeline | 4 | ✓ | - | Phase 6 |
| copilot/dedup | 2 | ✓ | - | Phase 6 |
| copilot/detector | 1 | - | - | Phase 6 |
| copilot/ingestor | 1 | ✓ | - | Phase 6 |
| copilot/notifier | 1 | ✓ | ✓ | Phase 6 |
| copilot/client | 2 | ✓ | - | Phase 6 |
| copilot/scheduler | 1 | ✓ | - | Phase 6 |
| copilot/config | 1 | ✓ | ✓ | Phase 6 |

#### Config 패키지
| 파일 | executor 의존 | config 의존 | cache 의존 | 이관 Phase |
|------|--------------|-------------|-----------|-----------|
| AppProperties | - | - | - | Phase 2 |
| BufferProperties | - | - | - | Phase 2 |
| BatchProperties | - | - | - | Phase 2 |
| CalculationProperties | - | - | - | Phase 2 |
| CorsProperties | - | ✓ | - | Phase 2 |
| BufferConfig | - | ✓ | - | Phase 4 |
| BatchConfig | - | - | - | Phase 4 |
| LikeBufferConfig | ✓ | - | - | ✓ | Phase 4 |
| LikeSyncConfig | ✓ | - | - | - | Phase 4 |
| LikeRealtimeSyncConfig | ✓ | - | ✓ | - | Phase 4 |
| EquipmentProcessingExecutorConfig | - | ✓ | - | - | Phase 5 |
| OpenTelemetryConfig | - | - | - | - | Phase 3 |
| OpenApiConfig | - | - | - | - | Phase 3 |
| MaplestoryApiConfig | - | ✓ | - | - | Phase 3 |
| WebConfig | - | - | ✓ | - | Phase 3 |
| CacheConfig | ✓ | ✓ | ✓ | - | Phase 3 |
| LookUpTableInitializer | ✓ | - | - | - | Phase 3 |
| DataInitializer | ✓ | - | - | - | Phase 3 |
| EventConsumerConfig | - | - | - | - | Phase 3 |

## 검증 (Validation)

### 각 Phase 완료 기준
- [ ] 컴파일 성공: `./gradlew clean build -x test`
- [ ] 테스트 통과: `./gradlew test`
- [ ] Bean 중복 없음: ApplicationContext 시작 로그 확인
- [ ] 순환 의존성 없음: `./gradlew dependencies` 확인

### 아키텍처 검증 스킬
```bash
/verify-module-structure      # 모듈 구조 검증
/verify-circular-dependencies # 순환 의존성 검증
/verify-clean-architecture    # 클린 아키텍처 준수
```

### 최종 검증
- [ ] 전체 빌드 성공
- [ ] 전체 테스트 통과
- [ ] 애플리케이션 정상 시작
- [ ] Actuator 헬스체크 정상

## 관련 의사결정
- **ADR-035**: Command Side JPA → JDBC 배치 전환
- **Issue #385**: monitoring → module-infra 이관
- **Issue #386**: config → module-infra 이관

## 참고 (References)
- **Clean Architecture**: https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
- **Spring Modulith**: https://spring.io/projects/spring-modulith

---

**작성일:** 2026-02-26
**작성자:** Claude Code (Team Lead)
**승인자:** TBD
**다음 리뷰:** 2026-03-26
