package com.allfolio.metrics

import com.allfolio.common.metrics.PortalConsumer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 포트 → 실제 카운터를 잇는 자리(AF-210).
 *
 * `unified-asset` 쪽 테스트(`FscStockClientMeteringTest`)는 Micrometer가 그 모듈의 클래스패스에
 * 없어 기록 대역으로만 문다. **이 파일이 그 대역과 진짜 레지스트리를 잇는다** — 둘을 합쳐야
 * "카운터가 오른다"가 증명되고, 한쪽만으로는 아니다.
 */
class MicrometerPortalCallMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = MicrometerPortalCallMetrics(registry)

    private fun count(consumer: PortalConsumer, result: String): Double =
        registry.find(MicrometerPortalCallMetrics.CALL_COUNT)
            .tag("consumer", consumer.tag).tag("result", result)
            .counter()?.count() ?: 0.0

    @Test
    fun `성공 한 번이 성공 태그를 1 올린다`() {
        metrics.callSucceeded(PortalConsumer.INDEX)

        assertThat(count(PortalConsumer.INDEX, "success")).isEqualTo(1.0)
        assertThat(count(PortalConsumer.INDEX, "failure")).isEqualTo(0.0)
    }

    @Test
    fun `실패 한 번이 실패 태그를 1 올린다`() {
        metrics.callFailed(PortalConsumer.INDEX)

        assertThat(count(PortalConsumer.INDEX, "failure")).isEqualTo(1.0)
        assertThat(count(PortalConsumer.INDEX, "success")).isEqualTo(0.0)
    }

    /**
     * **소비자가 안 갈리면 이 계측은 아무 소용이 없다.** AF-203이 묻는 것이 "어느 소비자가
     * 한도를 먹었나"이므로, 태그가 뭉치는 순간 질문 자체가 사라진다.
     */
    @Test
    fun `소비자별로 따로 쌓인다`() {
        metrics.callSucceeded(PortalConsumer.INDEX)
        metrics.callSucceeded(PortalConsumer.COMMODITY)
        metrics.callSucceeded(PortalConsumer.COMMODITY)

        assertThat(count(PortalConsumer.INDEX, "success")).isEqualTo(1.0)
        assertThat(count(PortalConsumer.COMMODITY, "success")).isEqualTo(2.0)
    }

    /**
     * 태그 값은 대시보드가 무는 문자열이다 — 바뀌면 기존 시계열이 조용히 끊긴다.
     * 그래서 열거형 이름이 아니라 [PortalConsumer.tag]를 여기 못 박는다.
     */
    @Test
    fun `소비자 태그 값이 고정돼 있다`() {
        assertThat(PortalConsumer.entries.map { it.tag })
            .containsExactly("rtms", "index", "commodity", "stock_price", "stock_etf_price", "stock_list")
    }
}
