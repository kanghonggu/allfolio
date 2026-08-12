package com.allfolio.fx.exchange

import com.allfolio.fx.FxApiClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest

/**
 * 빈 조립을 실제 컨텍스트로 확인한다.
 *
 * @Bean 메서드는 파라미터를 못 찾아도 컴파일된다 — 컨텍스트가 뜰 때에야 터진다.
 * 그러면 단위 테스트는 전부 초록인데 서버가 안 뜬다
 * ([com.allfolio.fx.HanaFxRateServiceWiringTest]와 같은 이유).
 *
 * **소스 순서도 여기서 고정한다.** listOf의 두 줄을 바꿔도 컴파일되고, 테스트도 통과하고,
 * 로그도 안 남는다 — 그저 조용히 폴백이 주 소스가 된다. 순서가 곧 폴백 정책이라
 * 규약으로만 두면 안 된다.
 */
@SpringBootTest(
    classes = [
        ExchangeFxConfigWiringTest.TestApplication::class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration::class,
        ExchangeFxConfig::class,
        UpbitFxParser::class,
        BithumbFxParser::class,
    ],
    properties = [
        "fx.scheduler.enabled=true",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
    ],
)
@org.springframework.boot.context.properties.EnableConfigurationProperties(ExchangeFxProperties::class)
class ExchangeFxConfigWiringTest {

    @Autowired private lateinit var client: FxApiClient

    private fun sources(): List<FxQuoteSource> {
        @Suppress("UNCHECKED_CAST")
        return ExchangeFxApiClient::class.java
            .getDeclaredField("sources")
            .apply { isAccessible = true }
            .get(client) as List<FxQuoteSource>
    }

    @Test
    fun `FxApiClient 빈은 거래소 체인 구현이다`() {
        assertThat(client).isInstanceOf(ExchangeFxApiClient::class.java)
    }

    @Test
    fun `Upbit이 주 소스이고 Bithumb이 폴백이다 - 순서가 폴백 정책이다`() {
        assertThat(sources().map { it.sourceName }).containsExactly("UPBIT", "BITHUMB")
    }

    @SpringBootConfiguration
    class TestApplication
}
