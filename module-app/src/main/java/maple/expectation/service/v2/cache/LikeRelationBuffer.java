package maple.expectation.service.v2.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 좋아요 관계 버퍼 (L1 Caffeine + L2 Redis)
 *
 * <p>기존 LikeBufferStorage와 동일한 패턴 적용:
 *
 * <ul>
 *   <li>L1 (Caffeine): 로컬 버퍼 - 빠른 중복 체크
 *   <li>L2 (Redis): 글로벌 버퍼 - 분산 환경 중복 체크
 *   <li>L3 (DB): 영구 저장 - 배치 동기화
 * </ul>
 *
 * <p>흐름:
 *
 * <ol>
 *   <li>요청 → L1 체크 → L2 체크 + 추가 (원자적)
 *   <li>스케줄러 → L1 → L2 동기화
 *   <li>스케줄러 → L2 → DB 배치 동기화
 * </ol>
 *
 * <p>CLAUDE.md 섹션 17 준수: L1 TTL ≤ L2 TTL, Graceful Degradation
 *
 * @see LikeRelationBufferStrategy 전략 인터페이스
 */
@Slf4j
@ConditionalOnProperty(name = "app.buffer.redis.enabled", havingValue = "false")
@Component
public class LikeRelationBuffer
    implements maple.expectation.service.v2.cache.LikeRelationBufferStrategy {

  private static final String REDIS_SET_KEY = "buffer:like:relations";
  private static final String REDIS_PENDING_SET_KEY = "buffer:like:relations:pending";

  /** L1 캐시: 로컬 중복 체크용 Key: relationKey (accountId:targetOcid) Value: Boolean.TRUE (존재 여부만 확인) */
  @Getter private final Cache<String, Boolean> localCache;

  /** L1 Pending Set: 로컬에서 추가되어 L2 동기화 대기 중인 관계 */
  @Getter private final ConcurrentHashMap<String, Boolean> localPendingSet;

  private final RedissonClient redissonClient;
  private final LogicExecutor executor;

  public LikeRelationBuffer(
      RedissonClient redissonClient, LogicExecutor executor, MeterRegistry registry) {
    this.redissonClient = redissonClient;
    this.executor = executor;
    this.localPendingSet = new ConcurrentHashMap<>();

    // L1 Caffeine 캐시 (LikeBufferStorage와 동일 설정)
    this.localCache =
        Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .maximumSize(10_000) // 좋아요 관계는 더 많을 수 있음
            .build();

    // 메트릭: L1 버퍼 크기
    Gauge.builder("like.relation.buffer.l1.size", this, buffer -> localCache.estimatedSize())
        .description("L1 버퍼링된 좋아요 관계 수 (Caffeine)")
        .register(registry);

    // 메트릭: L1 Pending 크기
    Gauge.builder("like.relation.buffer.l1.pending", this, buffer -> localPendingSet.size())
        .description("L2 동기화 대기 중인 좋아요 관계 수")
        .register(registry);
  }

  @Override
  public StrategyType getType() {
    return StrategyType.IN_MEMORY;
  }

  /**
   * 좋아요 관계 추가 (L1 + L2 계층)
   *
   * <p>흐름:
   *
   * <ol>
   *   <li>L1 (Caffeine) 빠른 체크 - 있으면 즉시 false
   *   <li>L2 (Redis) RSet.add() - 원자적 중복 검사
   *   <li>L1에 추가 + Pending Set에 등록
   * </ol>
   *
   * @param accountId 좋아요를 누른 계정의 캐릭터명
   * @param targetOcid 대상 캐릭터의 OCID
   * @return true if 신규 좋아요, false if 중복, null if Redis 장애
   */
  @Override
  public Boolean addRelation(String accountId, String targetOcid) {
    String relationKey = buildRelationKey(accountId, targetOcid);

    // 1. L1 빠른 체크 (로컬)
    if (localCache.getIfPresent(relationKey) != null) {
      log.debug("🔄 [LikeRelation] L1 중복 감지: {}", relationKey);
      return false;
    }

    // 2. L2 원자적 중복 검사 + 추가
    Boolean isNew =
        executor.executeOrDefault(
            () -> getRelationSet().add(relationKey),
            null, // Redis 장애 시 null (Fallback 필요)
            TaskContext.of("LikeRelation", "L2Add", relationKey));

    if (isNew == null) {
      // Redis 장애 → Fallback 필요 (호출자가 처리)
      return null;
    }

    if (isNew) {
      // 3. L1에 추가 + Pending Set에 등록
      localCache.put(relationKey, Boolean.TRUE);
      localPendingSet.put(relationKey, Boolean.TRUE);

      // Pending Set (DB 동기화용)
      executor.executeVoidJava(
          () -> getPendingSet().add(relationKey),
          TaskContext.of("LikeRelation", "AddPending", relationKey));

      log.debug("✅ [LikeRelation] 새 관계 추가: {}", relationKey);
    } else {
      // L2에서 중복 감지 → L1에도 추가 (다음 요청 빠른 체크용)
      localCache.put(relationKey, Boolean.TRUE);
      log.debug("🔄 [LikeRelation] L2 중복 감지: {}", relationKey);
    }

    return isNew;
  }

  /**
   * 중복 좋아요 여부 확인 (L1 → L2 계층)
   *
   * @return true if 이미 좋아요함, null if Redis 장애
   */
  @Override
  public Boolean exists(String accountId, String targetOcid) {
    String relationKey = buildRelationKey(accountId, targetOcid);

    // L1 체크
    if (localCache.getIfPresent(relationKey) != null) {
      return true;
    }

    // L2 체크
    return executor.executeOrDefault(
        () -> {
          boolean exists = getRelationSet().contains(relationKey);
          if (exists) {
            // L1 backfill
            localCache.put(relationKey, Boolean.TRUE);
          }
          return exists;
        },
        null,
        TaskContext.of("LikeRelation", "Exists", relationKey));
  }

  /** L1 Pending → L2 동기화 (스케줄러 호출용) 기존 LikeSyncService.flushLocalToRedis()와 동일 패턴 */
  public void flushLocalToRedis() {
    if (localPendingSet.isEmpty()) {
      return;
    }

    localPendingSet.forEach(
        (relationKey, value) -> {
          executor.executeOrCatch(
              () -> {
                getRelationSet().add(relationKey);
                getPendingSet().add(relationKey);
                localPendingSet.remove(relationKey);
                return null;
              },
              e -> {
                log.warn("⚠️ [LikeRelation] L1→L2 동기화 실패: {}", relationKey);
                return null;
              },
              TaskContext.of("LikeRelation", "L1toL2", relationKey));
        });
  }

  /** 동기화 대기 중인 관계 목록 (DB 동기화용) */
  public RSet<String> getPendingSet() {
    return redissonClient.getSet(REDIS_PENDING_SET_KEY);
  }

  /** 전체 관계 Set (중복 검사용) */
  public RSet<String> getRelationSet() {
    return redissonClient.getSet(REDIS_SET_KEY);
  }

  /** 관계 키 생성 Format: {accountId}:{targetOcid} */
  @Override
  public String buildRelationKey(String accountId, String targetOcid) {
    return accountId + ":" + targetOcid;
  }

  /**
   * 관계 키 파싱
   *
   * @return [accountId, targetOcid]
   */
  @Override
  public String[] parseRelationKey(String relationKey) {
    return relationKey.split(":", 2);
  }

  /** 좋아요 관계 삭제 */
  @Override
  public Boolean removeRelation(String accountId, String targetOcid) {
    String relationKey = buildRelationKey(accountId, targetOcid);

    // L1 제거
    localCache.invalidate(relationKey);
    localPendingSet.remove(relationKey);

    // L2 제거
    return executor.executeOrDefault(
        () -> {
          boolean removed = getRelationSet().remove(relationKey);
          getPendingSet().remove(relationKey);
          return removed;
        },
        false,
        TaskContext.of("LikeRelation", "Remove", relationKey));
  }

  /** DB 동기화 대기 중인 관계 조회 + 제거 (원자적) */
  @Override
  public Set<String> fetchAndRemovePending(int limit) {
    return executor.executeOrDefault(
        () -> {
          Set<String> result = new HashSet<>();
          RSet<String> pendingSet = getPendingSet();

          for (int i = 0; i < limit; i++) {
            String relationKey = pendingSet.removeRandom();
            if (relationKey == null) {
              break;
            }
            result.add(relationKey);
          }

          return result;
        },
        Set.of(),
        TaskContext.of("LikeRelation", "FetchPending"));
  }

  /** 전체 관계 수 조회 */
  @Override
  public int getRelationsSize() {
    return executor.executeOrDefault(
        () -> getRelationSet().size(), 0, TaskContext.of("LikeRelation", "GetSize"));
  }

  /** 대기 중인 관계 수 조회 */
  @Override
  public int getPendingSize() {
    return executor.executeOrDefault(
        () -> getPendingSet().size(), 0, TaskContext.of("LikeRelation", "GetPendingSize"));
  }

  /**
   * 명시적 좋아요 취소 여부 확인 (In-Memory 모드)
   *
   * <p>In-Memory 모드는 명시적 좋아요 취소를 추적하지 않음
   *
   * @return 항상 false (명시적 취소 없음)
   */
  @Override
  public Boolean existsInUnliked(String accountId, String targetOcid) {
    // In-Memory mode doesn't track explicit unlikes
    return false;
  }
}
