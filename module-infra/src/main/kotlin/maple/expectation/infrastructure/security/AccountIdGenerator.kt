package maple.expectation.infrastructure.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.stereotype.Component

/**
 * 넥슨 계정 식별자 생성기
 *
 * <p>동일 넥슨 계정은 API Key나 로그인 캐릭터가 달라도 항상 동일한 myOcids(소유 캐릭터 목록)를 반환합니다. 이를 정렬 후 SHA-256 해싱하여 안정적인 계정
 * 식별자를 생성합니다.
 *
 * <p>용도: 좋아요 중복 방지 키 (동일 계정 = 동일 accountId)
 */
@Component
class AccountIdGenerator(
    private val executor: LogicExecutor,
) {
    /**
     * myOcids로부터 계정 식별자를 생성합니다.
     *
     * @param myOcids 계정이 소유한 전체 캐릭터 OCID 목록
     * @return Base64 URL-safe 인코딩된 accountId
     */
    fun generate(myOcids: Set<String>): String {
        val canonical = myOcids.sorted().joinToString(",")

        return executor.execute(
            {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(canonical.toByteArray(StandardCharsets.UTF_8))
                Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
            },
            TaskContext.of("AccountId", "Generate"),
        )
    }
}
