package com.allfolio.market.rate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 설정 키가 어긋나면 목록이 조용히 비고, 수집은 "대상 0건"으로 끝난다.
 * 그 실패는 로그에만 남아서, 바인딩 자체를 테스트로 못 박는다.
 */
class MarketRatePropertiesTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)

    @Test
    fun `series 목록을 바인딩한다`() {
        runner.withPropertyValues(
            "market-rate.series[0].code=KTB_3Y",
            "market-rate.series[0].stat-code=721Y001",
            "market-rate.series[0].item-code=5030000",
            "market-rate.series[0].cycle=D",
        ).run { context ->
            val series = context.getBean(MarketRateProperties::class.java).series
            assertThat(series).hasSize(1)
            assertThat(series[0].code).isEqualTo("KTB_3Y")
            assertThat(series[0].statCode).isEqualTo("721Y001")
            assertThat(series[0].itemCode).isEqualTo("5030000")
            assertThat(series[0].cycle).isEqualTo("D")
        }
    }

    @Test
    fun `설정이 없으면 빈 목록이다`() {
        runner.run { context ->
            assertThat(context.getBean(MarketRateProperties::class.java).series).isEmpty()
        }
    }

    /**
     * 오타난 설정은 기동을 실패시킨다. 런타임에 종목별 실패로 흘리면 매일 실패 한 줄이
     * 쌓일 뿐이고, 그 사이 그 종목은 비어 있다. `EcosProperties.Series`가 unit-divisor에
     * 같은 판단을 한다 — 바인딩 시점에 막는다.
     */
    @Test
    fun `코드가 비어 있으면 기동에 실패한다`() {
        runner.withPropertyValues(
            "market-rate.series[0].code=KTB_3Y",
            "market-rate.series[0].stat-code=",
            "market-rate.series[0].item-code=5030000",
        ).run { context ->
            assertThat(context).hasFailed()
            // 스프링이 BeanCreationException으로 감싸서 표면 메시지엔 우리 문구가 없다.
            // require()가 던진 원인 예외까지 내려가서 확인한다.
            assertThat(context.startupFailure).rootCause().hasMessageContaining("KTB_3Y")
        }
    }

    @Test
    fun `지원하지 않는 주기는 기동에 실패한다`() {
        runner.withPropertyValues(
            "market-rate.series[0].code=BASE_RATE",
            "market-rate.series[0].stat-code=722Y001",
            "market-rate.series[0].item-code=0101000",
            "market-rate.series[0].cycle=M",
        ).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("주기")
        }
    }

    @EnableConfigurationProperties(MarketRateProperties::class)
    class TestConfig
}
