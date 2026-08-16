package com.allfolio.api.market

import com.allfolio.market.query.MarketFlags
import com.allfolio.market.query.MarketQueryService
import com.allfolio.market.query.MarketSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * 컨트롤러는 서비스가 만든 스냅샷을 손대지 않고 그대로 내보낸다.
 *
 * 여기서 서비스를 목으로 세우는 건 `plugin.spring`이 `@Service`를 all-open으로 열어 주기 때문이다 —
 * `mockito-inline` 없이 평범한 Mockito로 잡힌다.
 */
class MarketQueryControllerTest {

    private val service: MarketQueryService = mock(MarketQueryService::class.java)
    private val controller = MarketQueryController(service)

    /**
     * **컨트롤러가 스냅샷을 다시 조립하지 않는다는 것**을 동일성(`isSameAs`)으로 못 박는다.
     * 여기서 필드를 골라 옮겨 담기 시작하면 플래그가 off일 때의 null이 조용히 `[]`로 바뀔 수 있고,
     * 그러면 서버가 안 싣기로 한 것(AF-108)이 응답 조립 단계에서 되살아난다.
     */
    @Test
    fun `스냅샷을 그대로 돌려준다`() {
        val snapshot = MarketSnapshot(
            domestic = emptyList(),
            overseas = emptyList(),
            fx = null,
            rates = emptyList(),
            commodities = emptyList(),
            flags = MarketFlags(indicesEnabled = true, commoditiesEnabled = true),
        )
        `when`(service.snapshot()).thenReturn(snapshot)

        val response = controller.market()

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).isSameAs(snapshot)
    }
}
