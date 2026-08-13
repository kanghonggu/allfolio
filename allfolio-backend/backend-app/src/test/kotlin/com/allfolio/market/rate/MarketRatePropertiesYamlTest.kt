package com.allfolio.market.rate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest

/**
 * application.yml의 `market-rate` 블록이 실제로 바인딩되는지 확인한다 (AF-102).
 * `MarketIndexPropertiesTest`(AF-101)를 그대로 따른다 — 이유도 같다.
 *
 * [MarketRatePropertiesTest]는 합성 값으로 바인딩·검증 로직만 본다. 진짜 `application.yml`은
 * 아무도 안 건드린다 — 여기서 properties로 덮어써 버리면 검증 대상이 사라진다.
 *
 * **지금은 series가 빈 게 맞다.** ECOS 통계표·항목 코드는 Task 9에서 탐색 엔드포인트로
 * 확인한 뒤 채운다 — 그때 이 테스트의 단언도 `containsExactly(...)`로 바꿔야 한다
 * (AF-101의 `MarketIndexPropertiesTest`처럼). 지금 이 테스트가 지키는 것은 "빈 채로도
 * 기동은 된다"는 사실 하나뿐이다.
 */
@SpringBootTest(
    classes = [
        MarketRatePropertiesYamlTest.TestApplication::class,
        MarketRateProperties::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
class MarketRatePropertiesYamlTest {

    @Autowired
    private lateinit var properties: MarketRateProperties

    @Test
    fun `application yml의 금리 수집 대상은 아직 비어 있다`() {
        assertThat(properties.series).isEmpty()
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication
}
