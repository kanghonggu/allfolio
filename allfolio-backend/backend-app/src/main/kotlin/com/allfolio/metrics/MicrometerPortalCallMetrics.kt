package com.allfolio.metrics

import com.allfolio.common.metrics.PortalCallMetrics
import com.allfolio.common.metrics.PortalConsumer
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * [PortalCallMetrics]의 Micrometer 구현.
 *
 * `MarketMetrics`·`BrokerMetrics`의 관례를 그대로 따른다 — `MeterRegistry`를 주입받고,
 * 동작마다 메서드 하나를 노출하고, 태그를 문자열로 넘긴다. `/actuator/prometheus`로 나간다.
 *
 * 메트릭 카탈로그:
 *   portal.api.call.count{consumer, result}  — 공공데이터포털 호출 수 (result=success|failure)
 *
 * **소비자별 합이 곧 일일 사용량 배분이다.** `sum by (consumer)`가 AF-203("일일 한도 2만 건
 * 초과")이 묻는 질문에 답하는 자리이고, `result`는 그중 헛돈 호출을 갈라 준다.
 *
 * 구현이 `common`이 아니라 여기 있는 이유는 [PortalCallMetrics] KDoc에 있다 —
 * `unified-asset`에 Micrometer가 없고, 그것 때문에 모듈 의존성을 새로 들이지 않는다.
 *
 * **빈이 사라지면 부팅이 실패한다.** 네 클라이언트가 이 포트를 생성자 필수 인자로 받기
 * 때문이다 — 기본값을 no-op으로 두면 배선이 끊긴 채 조용히 0을 세게 된다.
 */
@Component
class MicrometerPortalCallMetrics(
    private val meterRegistry: MeterRegistry,
) : PortalCallMetrics {

    override fun callSucceeded(consumer: PortalConsumer) = count(consumer, "success")

    override fun callFailed(consumer: PortalConsumer) = count(consumer, "failure")

    private fun count(consumer: PortalConsumer, result: String) =
        meterRegistry.counter(CALL_COUNT, "consumer", consumer.tag, "result", result).increment()

    companion object {
        /** 메트릭 이름. **바꾸면 대시보드가 끊긴다** */
        const val CALL_COUNT = "portal.api.call.count"
    }
}
