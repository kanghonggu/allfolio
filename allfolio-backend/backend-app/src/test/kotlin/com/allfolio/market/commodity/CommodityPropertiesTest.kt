package com.allfolio.market.commodity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 설정 키가 어긋나면 목록이 조용히 비고, 수집은 "대상 0건"으로 끝난다.
 * `MarketRatePropertiesTest`(AF-102)와 같은 자리다 — [CommodityPropertiesYamlTest]가
 * 진짜 `application.yml`을 보는 동안, 여기서는 합성 값으로 바인딩과 파생 목록만 본다.
 *
 * **fsc를 반드시 채워서 본다.** 실제 yml의 fsc는 Task 4까지 `[]`라, YAML 테스트만으로는
 * [CommodityProperties.allItems]에서 `+ fsc` 항이 통째로 사라져도 전부 초록이다.
 * 그 증상이 정확히 KDoc이 예고한 "수집은 되는데 화면에 없다"이고, 오류도 로그도 없다.
 */
class CommodityPropertiesTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)
        .withPropertyValues(
            "market-commodity.fred-daily[0].code=WTI",
            "market-commodity.fred-daily[0].series-id=DCOILWTICO",
            "market-commodity.fred-daily[0].unit=USD/bbl",
            "market-commodity.fred-daily[0].frequency=D",
            "market-commodity.fred-monthly[0].code=COPPER",
            "market-commodity.fred-monthly[0].series-id=PCOPPUSDM",
            "market-commodity.fred-monthly[0].unit=USD/MT",
            "market-commodity.fred-monthly[0].frequency=M",
            "market-commodity.fsc[0].code=GOLD_KRX",
            "market-commodity.fsc[0].series-id=getGoldPriceInfo",
            "market-commodity.fsc[0].unit=KRW/g",
            "market-commodity.fsc[0].frequency=D",
        )

    @Test
    fun `세 목록을 바인딩한다`() {
        runner.run { context ->
            val properties = context.getBean(CommodityProperties::class.java)

            assertThat(properties.fredDaily).hasSize(1)
            assertThat(properties.fredMonthly).hasSize(1)
            assertThat(properties.fsc).hasSize(1)
            assertThat(properties.fredDaily[0].seriesId).isEqualTo("DCOILWTICO")
            assertThat(properties.fredDaily[0].unit).isEqualTo("USD/bbl")
            assertThat(properties.fredDaily[0].frequency).isEqualTo("D")
        }
    }

    /**
     * **allItems·allCodes가 세 목록을 다 더한다.** 한 항이라도 빠지면 그 소스는 수집만 되고
     * 조회에서 사라지는데(또는 반대), 목록이 짧아질 뿐이라 오류도 로그도 안 난다.
     * 지금 빠뜨리기 가장 쉬운 항이 fsc다 — 실제 yml에서 비어 있어 YAML 테스트가 못 본다.
     */
    @Test
    fun `allCodes가 fsc까지 포함한 전체다`() {
        runner.run { context ->
            val properties = context.getBean(CommodityProperties::class.java)

            assertThat(properties.allItems).hasSize(3)
            assertThat(properties.allCodes).containsExactly("WTI", "COPPER", "GOLD_KRX")
        }
    }

    @Test
    fun `설정이 없으면 빈 목록이다`() {
        ApplicationContextRunner()
            .withUserConfiguration(TestConfig::class.java)
            .run { context ->
                val properties = context.getBean(CommodityProperties::class.java)
                assertThat(properties.allItems).isEmpty()
                assertThat(properties.allCodes).isEmpty()
            }
    }

    /**
     * **`frequency`는 한 글자다.** DB 컬럼이 `VARCHAR(1)`이라 `Daily` 같은 값이 들어오면
     * 바인딩도 CI도 초록인 채 운영 insert에서 길이 초과로 터진다. 지금은 검증 로직이 없어
     * (Task 4의 `@PostConstruct` 몫) 이 단언이 그 자리를 대신 지킨다 — fsc 항목까지 본다.
     */
    @Test
    fun `모든 항목의 주기가 D 또는 M 한 글자다`() {
        runner.run { context ->
            val properties = context.getBean(CommodityProperties::class.java)

            assertThat(properties.allItems).allSatisfy {
                assertThat(it.frequency).describedAs("frequency of ${it.code}").isIn("D", "M")
            }
        }
    }

    @EnableConfigurationProperties(CommodityProperties::class)
    class TestConfig
}
