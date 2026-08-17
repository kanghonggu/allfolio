package com.allfolio.market.commodity

import com.allfolio.market.commodity.fred.FredCommoditySource
import com.allfolio.market.commodity.fsc.FscCommodityClient
import com.allfolio.market.commodity.fsc.FscCommoditySource
import com.allfolio.market.rate.fred.FredApiClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean

/**
 * [CommodityCollectService]가 `List<CommoditySource>`를 주입받는 구조라, 소스 빈이 하나도 없으면
 * **단위 테스트는 전부 초록인데 서버가 안 뜬다** — 스프링은 컬렉션 주입에 후보가 없을 때
 * 기동을 실패시킨다. 반대로 `@Component`가 빠져도 컴파일과 단위 테스트는 멀쩡하고,
 * 수집만 조용히 "대상 0건"으로 끝난다. 둘 다 실제 컨텍스트로만 보인다.
 * (`RateCollectSourceWiringTest`가 같은 이유로 존재한다.)
 *
 * **소스가 또 늘면 이 파일에 한 줄 더 적을 것.** 소스가 늘어도 수집 루프는 안 바뀌지만,
 * 새 소스가 컬렉션에 들어왔는지는 여기서만 보인다. (금/FSC가 그렇게 붙었다.)
 */
@SpringBootTest(
    classes = [
        CommodityCollectSourceWiringTest.TestApplication::class,
        FredCommoditySource::class,
        FscCommoditySource::class,
        CommodityCollectService::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "market-commodity.fred-daily[0].code=WTI",
        "market-commodity.fred-daily[0].series-id=DCOILWTICO",
        "market-commodity.fred-daily[0].unit=USD/bbl",
        "market-commodity.fred-daily[0].frequency=D",
        "market-commodity.fred-monthly[0].code=COPPER",
        "market-commodity.fred-monthly[0].series-id=PCOPPUSDM",
        "market-commodity.fred-monthly[0].unit=USD/MT",
        "market-commodity.fred-monthly[0].frequency=M",
        // series-id는 문자열이다 — @SpringBootTest properties는 이미 문자열이라 여기서는
        // 앞의 0이 살지만, 실제 yml의 따옴표는 CommodityPropertiesYamlTest가 따로 지킨다
        "market-commodity.fsc[0].code=GOLD_KRX",
        "market-commodity.fsc[0].series-id=04020000",
        "market-commodity.fsc[0].unit=KRW/g",
        "market-commodity.fsc[0].frequency=D",
    ],
)
class CommodityCollectSourceWiringTest {

    @MockBean private lateinit var fredClient: FredApiClient

    @MockBean private lateinit var fscClient: FscCommodityClient

    @MockBean private lateinit var store: CommodityCollectService.Store

    @Autowired private lateinit var service: CommodityCollectService

    @Autowired private lateinit var fredSource: FredCommoditySource

    @Autowired private lateinit var fscSource: FscCommoditySource

    @Autowired private lateinit var properties: CommodityProperties

    /**
     * **소스가 수집 루프까지 들어왔는지를 본다.** 빠지면 원자재가 조용히 안 쌓이고,
     * 요약은 "requested=0, failed=0" 초록으로 끝난다.
     *
     * **`@Component`가 지워진 경우는 여기서 안 잡힌다** — 아래 `classes`에 클래스를 직접 적어
     * 등록하기 때문이다. 이 파일이 보는 건 컬렉션 주입이 성립하는지까지다.
     */
    @Test
    fun `수집 서비스는 원자재 소스 둘을 모두 주입받는다`() {
        val sources = CommodityCollectService::class.java
            .getDeclaredField("sources")
            .apply { isAccessible = true }
            .get(service)

        // 순서는 보지 않는다 — 수집 루프가 소스 순서에 기대지 않는다
        assertThat(sources as List<*>).containsExactlyInAnyOrder(fredSource, fscSource)
    }

    /**
     * 코드 목록은 설정에서 온다 — 소스가 자기 대상을 모르면 수집 루프가 아무것도 안 돈다.
     * **일간·월간을 둘 다 담당하는지**까지 본다: 한쪽 목록만 읽는 소스는 나머지 층을 통째로 빠뜨리고,
     * 그건 실패가 아니라 "없는 데이터"로 보인다.
     */
    @Test
    fun `소스는 일간과 월간 설정을 모두 담당한다`() {
        assertThat(fredSource.codes).containsExactly("WTI", "COPPER")
        assertThat(fredSource.sourceName).isEqualTo("FRED")
    }

    /**
     * **담당이 겹치면 안 된다.** 두 소스가 같은 코드를 담당하면 수집 루프가 그 코드를 두 번 돌고,
     * 값도 `source` 열도 뒤에 도는 쪽으로 매 실행 뒤집힌다 — 제약조건도 요약도 조용하다.
     */
    @Test
    fun `금은 FSC가 담당하고 FRED와 겹치지 않는다`() {
        assertThat(fscSource.codes).containsExactly("GOLD_KRX")
        assertThat(fscSource.sourceName).isEqualTo("FSC")
        assertThat(fredSource.codes).doesNotContainAnyElementsOf(fscSource.codes)
    }

    /**
     * 서비스가 단위·주기를 되찾는 통로가 [CommodityProperties.allItems]다 —
     * 바인딩이 깨지면 전 종목이 "설정에 없는 코드"로 실패한다.
     */
    @Test
    fun `설정이 바인딩되어 단위와 주기를 되찾을 수 있다`() {
        assertThat(properties.allItems.map { Triple(it.code, it.unit, it.frequency) })
            .containsExactly(
                Triple("WTI", "USD/bbl", "D"),
                Triple("COPPER", "USD/MT", "M"),
                Triple("GOLD_KRX", "KRW/g", "D"),
            )
    }

    // 설정 바인딩은 여기서 켠다. `classes`에 같이 넣으면 @Component 빈과 바인딩 빈이 둘 다 생겨
    // FredCommoditySource 주입이 모호해진다
    @SpringBootConfiguration
    @EnableConfigurationProperties(CommodityProperties::class)
    class TestApplication
}
