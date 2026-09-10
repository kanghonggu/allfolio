package com.allfolio.pnl

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.util.UUID

/**
 * `CostBasisMethod` 분기 (AF-200).
 *
 * ## 왜 여기인가
 *
 * 변이 기준선(AF-198)이 `CostBasisMethod.kt`를 **"변이 지점 없음"**으로 기록했다 — 14줄
 * enum이라 분기가 그 파일에 없다. 그러면서 **분기가 어디 있는지**를 후속 입력으로 남겼다:
 * `PositionCacheService.costBasis`다. 참조 테스트는 0건이었다.
 *
 * 이 함수는 Redis를 안 탄다 — `PositionDataMapper.toLotPosition`만 부르는 순수 투영이라
 * 목 두 개로 생성해 바로 부를 수 있다.
 *
 * ## 🔴 무는 것 — 조용한 폴백
 *
 * `FIFO -> position.fifoCostBasis ?: position.averageCost`.
 *
 * **lot이 없으면 아무 말 없이 평단으로 떨어진다.** 두 방식이 같은 값을 내는 상황에서는
 * 분기가 통째로 바뀌어도 아무도 모른다. 그래서 여기서는 **두 값이 다른 데이터**로 잰다 —
 * 그래야 분기가 실제로 갈리는지 보인다.
 */
class PositionCacheServiceCostBasisTest {

    private val service = PositionCacheService(
        mock(StringRedisTemplate::class.java),
        ObjectMapper(),
    )

    private val portfolio = UUID.randomUUID()
    private val asset = UUID.randomUUID()

    private fun data(vararg lots: Pair<String, String>, quantity: String, avgCost: String) = PositionData(
        portfolioId = portfolio,
        assetId = asset,
        quantity = BigDecimal(quantity),
        avgCost = BigDecimal(avgCost),
        lots = lots.map { (p, q) -> PositionLot(BigDecimal(p), BigDecimal(q)) },
    )

    /**
     * 🔴 **두 방식이 다른 값을 내야 이 테스트가 뭔가를 잰다.**
     *
     * lot이 100원 10주 + 200원 10주면 평단은 150, FIFO 원가는 **가장 오래된 lot의 100**이다.
     * 값이 같은 데이터로 재면 분기를 바꿔도 통과한다.
     */
    @Test
    fun `AVG_COST는 가중평균, FIFO는 가장 오래된 lot이다`() {
        val d = data("100" to "10", "200" to "10", quantity = "20", avgCost = "150")

        assertThat(service.costBasis(d, CostBasisMethod.AVG_COST)).isEqualByComparingTo(BigDecimal("150"))
        assertThat(service.costBasis(d, CostBasisMethod.FIFO)).isEqualByComparingTo(BigDecimal("100"))
    }

    /** lot이 셋이어도 FIFO는 맨 앞 하나만 본다 — 평균이 아니다 */
    @Test
    fun `FIFO는 뒤쪽 lot 가격에 영향받지 않는다`() {
        val d = data("100" to "10", "500" to "10", "900" to "10", quantity = "30", avgCost = "500")

        assertThat(service.costBasis(d, CostBasisMethod.FIFO)).isEqualByComparingTo(BigDecimal("100"))
        assertThat(service.costBasis(d, CostBasisMethod.AVG_COST)).isEqualByComparingTo(BigDecimal("500"))
    }

    /**
     * 🔴 **lot이 없으면 FIFO가 조용히 평단으로 떨어진다.**
     *
     * 레거시 캐시(수량만 있고 lots가 빈 데이터)는 매퍼가 `(avgCost, quantity)` 단일 lot으로
     * 합성한다. 그래서 이 경우 FIFO와 AVG_COST가 **같은 값**이 되고, 그건 폴백이 아니라
     * 합성의 결과다 — 아래 빈 포지션과 구분해서 적어 둔다.
     */
    @Test
    fun `레거시 캐시는 avgCost로 단일 lot을 합성해 두 방식이 같아진다`() {
        val d = data(quantity = "10", avgCost = "123")

        assertThat(service.costBasis(d, CostBasisMethod.AVG_COST)).isEqualByComparingTo(BigDecimal("123"))
        assertThat(service.costBasis(d, CostBasisMethod.FIFO)).isEqualByComparingTo(BigDecimal("123"))
    }

    /**
     * 보유가 없는 포지션. `fifoCostBasis`가 null이라 **폴백이 실제로 걸리는 유일한 자리**다.
     * 예외로 터지지 않고 0을 준다.
     */
    @Test
    fun `보유가 없으면 두 방식 모두 0이다`() {
        val d = data(quantity = "0", avgCost = "0")

        assertThat(service.costBasis(d, CostBasisMethod.AVG_COST)).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(service.costBasis(d, CostBasisMethod.FIFO)).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
