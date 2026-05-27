package maple.restcontroller.auth

import maple.expectation.core.port.out.CharacterOcidPort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class JdbcOcidQueryAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : CharacterOcidPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun resolveOcid(userIgn: String): String? =
        jdbc.queryForList(
            "SELECT ocid FROM game_character WHERE user_ign = :userIgn LIMIT 1",
            mapOf("userIgn" to userIgn),
            String::class.java,
        ).firstOrNull()

    override fun resolveOcids(userIgns: Set<String>): Map<String, String> =
        jdbc.queryForList(
            "SELECT user_ign, ocid FROM game_character WHERE user_ign IN (:userIgns) AND ocid IS NOT NULL",
            mapOf("userIgns" to userIgns.toList()),
        ).associate { row ->
            row["user_ign"] as String to row["ocid"] as String
        }

    override fun resolveAllOcids(): Map<String, String> =
        jdbc.queryForList(
            "SELECT user_ign, ocid FROM game_character WHERE ocid IS NOT NULL",
            emptyMap<String, Any>(),
        ).associate { row ->
            row["user_ign"] as String to row["ocid"] as String
        }

    override fun resolveOcidsByFingerprint(fingerprint: String): Set<String> =
        jdbc.queryForList(
            "SELECT ocid FROM game_character WHERE fingerprint = :fingerprint AND ocid IS NOT NULL",
            mapOf("fingerprint" to fingerprint),
            String::class.java,
        ).toSet()

    override fun updateFingerprint(ocid: String, fingerprint: String, accountId: String): Int =
        jdbc.update(
            "UPDATE game_character SET fingerprint = :fingerprint, account_id = :accountId WHERE ocid = :ocid",
            mapOf("ocid" to ocid, "fingerprint" to fingerprint, "accountId" to accountId),
        )
}
