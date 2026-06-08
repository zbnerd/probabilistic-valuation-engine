package maple.restcontroller.like

import maple.expectation.core.domain.model.like.LikeToggleResult
import maple.expectation.core.domain.model.like.LikeToggleWithCount
import maple.expectation.core.port.inbound.LikeTogglePort
import maple.expectation.core.port.out.CharacterOcidPort
import maple.expectation.error.exception.CharacterNotFoundException
import maple.expectation.util.StringMaskingUtils.maskIgn
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JdbcLikeToggleService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val characterOcidPort: CharacterOcidPort,
) : LikeTogglePort {

    companion object {
        private val log = LoggerFactory.getLogger(JdbcLikeToggleService::class.java)
    }

    override fun toggleLike(targetUserIgn: String, likerAccountId: String, myOcids: Set<String>): LikeToggleResult =
        toggleLikeWithCount(targetUserIgn, likerAccountId, myOcids).result

    @Transactional("transactionManager")
    override fun toggleLikeWithCount(
        targetUserIgn: String,
        likerAccountId: String,
        myOcids: Set<String>,
    ): LikeToggleWithCount {
        val targetOcid = characterOcidPort.resolveOcid(targetUserIgn)
            ?: throw CharacterNotFoundException("Character not found: ${maskIgn(targetUserIgn)}")

        require(targetOcid !in myOcids) { "Self-like not allowed" }

        val existed = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM character_like WHERE target_ocid = :ocid AND liker_account_id = :accountId)",
            mapOf("ocid" to targetOcid, "accountId" to likerAccountId),
            Boolean::class.java,
        ) ?: false

        val result = if (existed) {
            jdbc.update(
                "DELETE FROM character_like WHERE target_ocid = :ocid AND liker_account_id = :accountId",
                mapOf("ocid" to targetOcid, "accountId" to likerAccountId),
            )
            LikeToggleResult.UNLIKED
        } else {
            jdbc.update(
                "INSERT INTO character_like (target_ocid, liker_account_id, created_at) VALUES (:ocid, :accountId, now()) ON CONFLICT (target_ocid, liker_account_id) DO NOTHING",
                mapOf("ocid" to targetOcid, "accountId" to likerAccountId),
            )
            LikeToggleResult.LIKED
        }

        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM character_like WHERE target_ocid = :ocid",
            mapOf("ocid" to targetOcid),
            Long::class.java,
        ) ?: 0L

        return LikeToggleWithCount(result, count)
    }

    @Transactional("transactionManager", readOnly = true)
    override fun isLiked(targetUserIgn: String, likerAccountId: String): Boolean {
        val targetOcid = characterOcidPort.resolveOcid(targetUserIgn) ?: return false
        return jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM character_like WHERE target_ocid = :ocid AND liker_account_id = :accountId)",
            mapOf("ocid" to targetOcid, "accountId" to likerAccountId),
            Boolean::class.java,
        ) ?: false
    }

    @Transactional("transactionManager", readOnly = true)
    override fun getLikeCount(targetUserIgn: String): Long {
        val targetOcid = characterOcidPort.resolveOcid(targetUserIgn) ?: return 0L
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM character_like WHERE target_ocid = :ocid",
            mapOf("ocid" to targetOcid),
            Long::class.java,
        ) ?: 0L
    }
}
