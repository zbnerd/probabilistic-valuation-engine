package maple.expectation.test

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import maple.expectation.core.domain.model.ItemPrice
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * CoreUnitTestTemplate 사용 예제
 *
 * ItemPrice Value Object를 사용하여 템플릿의 다양한 기능을 보여줍니다.
 *
 * 예제 내용:
 * 1. Given-When-Then 패턴
 * 2. Assertion Helpers 사용
 * 3. 예외 검증
 * 4. Business Logic 테스트
 * 5. Edge Case 테스트
 *
 * @see CoreUnitTestTemplate
 */
@DisplayName("Core 단위 테스트 템플릿 예제")
class CoreUnitTestTemplateExample : CoreUnitTestTemplate() {

    // ========================================
    // Example 1: Basic Given-When-Then Pattern
    // ========================================

    @Test
    @DisplayName("Given-When-Then 패턴으로 ItemPrice 생성 검증")
    fun basic_given_when_then_pattern() {
        // Given: 테스트 데이터 준비
        val itemPrice = given {
            ItemPrice.of(
                itemId = 12345L,
                itemName = "아케인 심볼",
                price = 10_000_000L,
            )
        }

        // When: 테스트 대상 로직 실행
        val isFresh = `when` {
            itemPrice.isFreshWithinHours(24)
        }

        // Then: 결과 검증
        then(isFresh) { result ->
            assertTrue(result, "생성 직후의 가격은 24시간 이내로 신선해야 함")
        }
    }

    // ========================================
    // Example 2: Assertion Helpers
    // ========================================

    @Test
    @DisplayName("Assertion Helper 사용 예시")
    fun assertion_helpers_example() {
        // Given
        val price = given { ItemPrice.of(1L, "테스트 아이템", 5000L) }

        // When & Then: itemId 검증
        val actualId = `when` { price.itemId }
        assertEqual(expected = 1L, actual = actualId)

        // When & Then: itemName 검증
        val hasName = `when` { price.itemName.isNotBlank() }
        assertTrue(hasName, "아이템 이름이 있어야 함")

        // When & Then: price가 음수가 아님을 검증
        val isNotNegative = `when` { price.price >= 0 }
        assertTrue(isNotNegative, "가격은 음수일 수 없음")
    }

    // ========================================
    // Example 3: Exception Testing
    // ========================================

    @Test
    @DisplayName("예외 발생 검증 - itemId가 양수가 아닌 경우")
    fun exception_validation_negative_itemId() {
        // When & Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ItemPrice.of(
                itemId = -1L,
                itemName = "잘못된 아이템",
                price = 1000L,
            )
        }

        // Then: 예외 메시지 검증
        assertTrue(
            exception.message?.contains("must be positive") == true,
            "예외 메시지에 'must be positive'가 포함되어야 함",
        )
    }

    @Test
    @DisplayName("예외 발생 검증 - itemName이 blank인 경우")
    fun exception_validation_blank_itemName() {
        // When & Then
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ItemPrice.of(
                itemId = 1L,
                itemName = "   ",
                price = 1000L,
            )
        }

        // Then
        assertTrue(
            exception.message?.contains("cannot be null or blank") == true,
            "예외 메시지에 'cannot be null or blank'가 포함되어야 함",
        )
    }

    // ========================================
    // Example 4: Business Logic Testing
    // ========================================

    @Test
    @DisplayName("가격 신선도 확인 - 시간 경과에 따른 상태 변화")
    fun freshness_business_logic() {
        // Given: 고정 시계 사용 (테스트 결정성 보장)
        val fixedClock = Clock.fixed(
            LocalDateTime.of(2024, 3, 15, 10, 0).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC,
        )
        val pastTime = LocalDateTime.now(fixedClock).minusHours(25)

        // When: 25시간 전의 가격 생성
        val oldPrice = given {
            ItemPrice(
                itemId = 1L,
                itemName = "오래된 가격",
                price = 1000L,
                updatedAt = pastTime,
            )
        }

        // Then: 24시간 기준으로는 신선하지 않음
        val isWithin24Hours = `when` { oldPrice.isFreshWithinHours(24) }
        assertFalse(isWithin24Hours, "25시간 전의 가격은 24시간 신선도 기준을 충족하지 않음")

        // Then: 48시간 기준으로는 신선함
        val isWithin48Hours = `when` { oldPrice.isFreshWithinHours(48) }
        assertTrue(isWithin48Hours, "25시간 전의 가격은 48시간 신선도 기준 충족")
    }

    // ========================================
    // Example 5: Edge Case Testing
    // ========================================

    @Test
    @DisplayName("Edge Case: 0원 가격 허용")
    fun edge_case_zero_price() {
        // Given & When
        val freeItem = given {
            ItemPrice.of(1L, "무료 아이템", 0L)
        }

        // Then
        then(freeItem.price) { price ->
            assertEqual(0L, price)
            assertTrue(price >= 0, "0원 가격은 허용되어야 함")
        }
    }

    @Test
    @DisplayName("Edge Case: 매우 큰 가격 처리")
    fun edge_case_very_large_price() {
        // Given & When
        val expensiveItem = given {
            ItemPrice.of(
                itemId = 1L,
                itemName = "전설 아이템",
                price = Long.MAX_VALUE,
            )
        }

        // Then
        then(expensiveItem.price) { price ->
            assertEqual(Long.MAX_VALUE, price)
        }
    }

    // ========================================
    // Example 6: Companion Object Factory Test
    // ========================================

    @Test
    @DisplayName("Companion Object Factory 메서드 테스트")
    fun companion_object_factory() {
        // Given & When
        val price = given {
            ItemPrice.of(
                itemId = 777L,
                itemName = "레어 아이템",
                price = 99_999_999L,
            )
        }

        // Then: 현재 시간이 설정되어야 함
        then(price.updatedAt) { updatedAt ->
            assertNotNull(updatedAt, "생성 시 현재 시간이 설정되어야 함")

            // 시간 차이가 1초 이내여야 함 (테스트 실행 시간 고려)
            val now = LocalDateTime.now()
            val diff = java.time.Duration.between(updatedAt, now).abs()
            assertTrue(
                diff.seconds < 1,
                "생성된 시간은 현재 시간과 1초 이내 차이여야 함",
            )
        }
    }
}
