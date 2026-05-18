package maple.expectation.infrastructure.like;

import lombok.RequiredArgsConstructor;
import maple.expectation.core.port.out.LikeBufferStrategy;
import org.springframework.stereotype.Component;

/**
 * 좋아요 처리 구현체 (Direct Buffer Pattern)
 *
 * <p>버퍼에 직접 delta를 추가하고 새 값을 반환합니다. 스케줄러가 배치로 DB에 반영합니다.
 */
@Component
@RequiredArgsConstructor
public class DatabaseLikeProcessor implements LikeProcessor {

  private final LikeBufferStrategy likeBufferStrategy;

  @Override
  public Long processLike(String userIgn) {
    return likeBufferStrategy.increment(userIgn, 1);
  }

  @Override
  public Long processUnlike(String userIgn) {
    return likeBufferStrategy.increment(userIgn, -1);
  }
}
