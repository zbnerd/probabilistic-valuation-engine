package maple.expectation.infrastructure.mongodb

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface CharacterValuationRepository : MongoRepository<CharacterValuationView, String> {

    fun findByUserIgn(userIgn: String?): CharacterValuationView?

    fun deleteByUserIgn(userIgn: String?)
}
