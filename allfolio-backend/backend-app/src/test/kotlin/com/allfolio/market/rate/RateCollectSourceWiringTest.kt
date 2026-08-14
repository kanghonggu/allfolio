package com.allfolio.market.rate

import com.allfolio.fx.EcosApiClient
import com.allfolio.market.rate.fred.FredApiClient
import com.allfolio.market.rate.fred.FredRateSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean

/**
 * [RateCollectService]가 `List<RateSource>`를 주입받는 구조라, 소스 빈이 하나도 없으면
 * **단위 테스트는 전부 초록인데 서버가 안 뜬다** — 스프링은 컬렉션 주입에 후보가 없을 때
 * 기동을 실패시킨다. 반대로 `@Component`가 빠져도 컴파일과 단위 테스트는 멀쩡하고,
 * 수집만 조용히 "대상 0건"으로 끝난다. 둘 다 실제 컨텍스트로만 보인다.
 */
@SpringBootTest(
    classes = [
        RateCollectSourceWiringTest.TestApplication::class,
        EcosRateSource::class,
        FredRateSource::class,
        RateCollectService::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "market-rate.ecos[0].code=KTB_3Y",
        "market-rate.ecos[0].stat-code=817Y002",
        "market-rate.ecos[0].item-code=010200000",
        "market-rate.fred[0].code=UST_10Y",
        "market-rate.fred[0].series-id=DGS10",
    ],
)
class RateCollectSourceWiringTest {

    @MockBean private lateinit var client: EcosApiClient

    @MockBean private lateinit var fredClient: FredApiClient

    @MockBean private lateinit var store: RateCollectService.Store

    @Autowired private lateinit var service: RateCollectService

    @Autowired private lateinit var ecosSource: EcosRateSource

    @Autowired private lateinit var fredSource: FredRateSource

    /**
     * **소스가 둘 다 수집 루프까지 들어왔는지를 본다.** 하나가 빠지면 그 나라 금리만 조용히
     * 안 쌓이고, 요약은 남은 소스만으로 "failed=0" 초록으로 끝난다.
     *
     * **다만 `@Component`가 지워진 경우는 여기서 안 잡힌다** — 아래 `classes`에 클래스를 직접
     * 적어 등록하기 때문이다(클래스 KDoc이 말하는 "실제 컨텍스트"는 컬렉션 주입이 성립하는지까지다).
     * 그 변이를 잡는 건 운영 컨텍스트를 통째로 띄우는 테스트뿐인데, 여기서는 그 값어치가 없다고 봤다.
     *
     * 순서는 안 본다. 컬렉션 주입 순서는 스프링이 정하고, 수집 루프에는 순서가 아무 뜻이 없다
     * (소스마다 담당 코드가 갈려 있어 서로 덮어쓰지 않는다). 뜻 없는 것에 테스트를 걸지 않는다.
     */
    @Test
    fun `수집 서비스는 ECOS와 FRED 소스를 주입받는다`() {
        val sources = RateCollectService::class.java
            .getDeclaredField("sources")
            .apply { isAccessible = true }
            .get(service)

        assertThat(sources as List<*>).containsExactlyInAnyOrder(ecosSource, fredSource)
    }

    /** 코드 목록은 설정에서 온다 — 소스가 자기 대상을 모르면 수집 루프가 아무것도 안 돈다 */
    @Test
    fun `소스는 설정에 있는 코드를 담당한다`() {
        assertThat(ecosSource.codes).containsExactly("KTB_3Y")
        assertThat(ecosSource.sourceName).isEqualTo("ECOS")
        assertThat(fredSource.codes).containsExactly("UST_10Y")
        assertThat(fredSource.sourceName).isEqualTo("FRED")
    }

    // 설정 바인딩은 여기서 켠다. `classes`에 같이 넣으면 @Component 빈과 바인딩 빈이 둘 다 생겨
    // EcosRateSource 주입이 모호해진다
    @SpringBootConfiguration
    @EnableConfigurationProperties(MarketRateProperties::class)
    class TestApplication
}
