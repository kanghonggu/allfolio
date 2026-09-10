package com.allfolio.metrics

import com.allfolio.common.metrics.PortalCallMetrics
import com.allfolio.dlq.DlqService
import com.allfolio.market.benchmark.FscIndexClient
import com.allfolio.market.commodity.fsc.FscCommodityClient
import com.allfolio.market.realestate.RtmsClient
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.unifiedasset.infrastructure.adapter.FscStockClient
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan

/**
 * **계측이 붙었다는 사실이 계측이 돈다는 뜻은 아니다.**
 *
 * 네 클라이언트가 [PortalCallMetrics]를 받아 카운터를 올리는 것은 단위 테스트가 문다.
 * 여기서 보는 것은 그 위 한 칸이다 — **운영 컨텍스트에서 진짜 구현이 주입되는가.**
 * 이게 끊기면 단위 테스트는 전부 초록인 채 운영 카운터만 영원히 0이고, 그 0은
 * "포털을 안 불렀다"와 구별되지 않는다(`ValuationSourceWiringTest`가 같은 이유로 존재한다).
 *
 * **`@ComponentScan`을 쓰는 것이 요점이다.** `classes`에 [MicrometerPortalCallMetrics]를 나열하면
 * `@Component`가 지워져도 빈으로 등록돼, 이 테스트가 검사하려는 바로 그것을 건너뛴다.
 *
 * 클라이언트는 `classes`에 직접 적는다 — 각자의 패키지에는 JPA·설정에 얽힌 빈이 함께 있어
 * 통째로 스캔하면 이 테스트가 배선과 무관한 이유로 깨진다.
 */
@SpringBootTest(
    classes = [
        PortalCallMetricsWiringTest.TestApplication::class,
        RtmsClient::class,
        FscIndexClient::class,
        FscCommodityClient::class,
        FscStockClient::class,
    ],
    properties = ["fsc.api-key=test-key"],
)
class PortalCallMetricsWiringTest {

    // BrokerMetrics가 같은 패키지에 있어 스캔에 딸려 온다 — 이 테스트의 관심사가 아니라 세워만 준다
    @MockBean private lateinit var dlqService: DlqService

    @MockBean private lateinit var outboxRepository: OutboxRepository

    @Autowired private lateinit var metrics: PortalCallMetrics

    @Autowired private lateinit var rtms: RtmsClient

    @Autowired private lateinit var index: FscIndexClient

    @Autowired private lateinit var commodity: FscCommodityClient

    @Autowired private lateinit var stock: FscStockClient

    /** `@Component`가 지워지면 여기서 컨텍스트가 뜨지 않는다 — 네 클라이언트의 필수 인자다 */
    @Test
    fun `포털 계측 빈은 Micrometer 구현이다`() {
        assertThat(metrics).isInstanceOf(MicrometerPortalCallMetrics::class.java)
    }

    /**
     * **no-op이 주입되면 조용히 0을 센다.** 생성자에 기본값을 두지 않은 이유가 이것이고,
     * 그 결정이 유지되는지를 여기서 본다 — 기본값이 생기는 순간 이 단언이 깨진다.
     */
    @Test
    fun `네 클라이언트 모두 그 구현을 주입받는다`() {
        assertThat(injectedMetrics(rtms)).isSameAs(metrics)
        assertThat(injectedMetrics(index)).isSameAs(metrics)
        assertThat(injectedMetrics(commodity)).isSameAs(metrics)
        assertThat(injectedMetrics(stock)).isSameAs(metrics)
    }

    private fun injectedMetrics(client: Any): Any? =
        client.javaClass.getDeclaredField("portalMetrics")
            .apply { isAccessible = true }
            .get(client)

    @SpringBootConfiguration
    @ComponentScan("com.allfolio.metrics")
    class TestApplication {
        @Bean fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

        @Bean fun objectMapper(): ObjectMapper = ObjectMapper()
    }
}
