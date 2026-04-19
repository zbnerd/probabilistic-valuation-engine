package maple.expectation.infrastructure.pgmq

import maple.expectation.core.domain.model.character.GameCharacter

data class CalculationResult(
    val message: PgmqMessage<ExpectationCalcMessage>,
    val response: Any,
    val character: GameCharacter,
)
