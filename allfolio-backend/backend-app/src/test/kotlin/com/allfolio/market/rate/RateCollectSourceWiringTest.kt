package com.allfolio.market.rate

import com.allfolio.fx.EcosApiClient
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
        RateCollectService::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "market-rate.ecos[0].code=KTB_3Y",
        "market-rate.ecos[0].stat-code=817Y002",
        "market-rate.ecos[0].item-code=010200000",
    ],
)
class RateCollectSourceWiringTest {

    @MockBean private lateinit var client: EcosApiClient

    @MockBean private lateinit var store: RateCollectService.Store

    @Autowired private lateinit var service: RateCollectService

    @Autowired private lateinit var ecosSource: EcosRateSource

    @Test
    fun `수집 서비스는 ECOS 소스를 주입받는다`() {
        val sources = RateCollectService::class.java
            .getDeclaredField("sources")
            .apply { isAccessible = true }
            .get(service)

        assertThat(sources as List<*>).containsExactly(ecosSource)
    }

    /** 코드 목록은 설정에서 온다 — 소스가 자기 대상을 모르면 수집 루프가 아무것도 안 돈다 */
    @Test
    fun `소스는 설정에 있는 코드를 담당한다`() {
        assertThat(ecosSource.codes).containsExactly("KTB_3Y")
        assertThat(ecosSource.sourceName).isEqualTo("ECOS")
    }

    // 설정 바인딩은 여기서 켠다. `classes`에 같이 넣으면 @Component 빈과 바인딩 빈이 둘 다 생겨
    // EcosRateSource 주입이 모호해진다
    @SpringBootConfiguration
    @EnableConfigurationProperties(MarketRateProperties::class)
    class TestApplication
}
