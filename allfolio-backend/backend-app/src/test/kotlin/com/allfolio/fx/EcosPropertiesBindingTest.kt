package com.allfolio.fx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer

/**
 * 중첩 Map<String, Series> + BigDecimal 바인딩은 조용히 빈 맵으로 떨어지기 쉽다.
 * 빈 series는 백필 시 "설정이 없는 통화"로 보여 코드 버그를 설정 문제처럼 위장하므로 실제 YAML로 확인한다.
 */
class EcosPropertiesBindingTest {

    private val runner = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withUserConfiguration(TestConfig::class.java)

    @EnableConfigurationProperties(EcosProperties::class)
    class TestConfig

    @Test
    fun `실제 application_yml의 series 키가 바인딩된다`() {
        runner.run { context ->
            val properties = context.getBean(EcosProperties::class.java)

            assertThat(properties.baseUrl).isEqualTo("https://ecos.bok.or.kr")
            assertThat(properties.series).containsKey("USD")
            assertThat(properties.series.getValue("USD").unitDivisor).isEqualByComparingTo("1")
        }
    }

    @Test
    fun `unit-divisor가 0 이하면 기동이 실패한다`() {
        // Task 10은 rate.divide(unitDivisor)를 한다. 0이면 ArithmeticException, 음수면 더 나쁘다 —
        // 모든 환율의 부호가 뒤집힌 채 fx_rate_daily를 거쳐 cash_flow.amount_krw까지 흘러간다.
        // 파서의 rate <= 0 가드는 나눗셈 전에 돌아서 걸러 주지 못하므로 바인딩 시점에 막는다.
        listOf("0", "-100").forEach { bad ->
            runner.withPropertyValues("ecos.series.JPY.unit-divisor=$bad").run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("unit-divisor는 0보다 커야 합니다")
            }
        }
    }

    @Test
    fun `설정이 비어 있어도 컨텍스트가 뜬다`() {
        // 키·통계표 코드 없이 먼저 배포하는 계획이므로 기동 실패는 곧 배포 실패다.
        runner.run { context ->
            val properties = context.getBean(EcosProperties::class.java)

            assertThat(properties.apiKey).isEmpty()
            assertThat(properties.series.getValue("USD").statCode).isEmpty()
        }
    }

    @Test
    fun `통계표 코드와 제수가 주입되면 그대로 바인딩된다`() {
        runner.withPropertyValues(
            "ecos.api-key=test-key",
            "ecos.series.JPY.stat-code=TEST-STAT-CODE",
            "ecos.series.JPY.item-code=TEST-ITEM-CODE",
            "ecos.series.JPY.unit-divisor=100",
        ).run { context ->
            val jpy = context.getBean(EcosProperties::class.java).series.getValue("JPY")

            assertThat(jpy.statCode).isEqualTo("TEST-STAT-CODE")
            assertThat(jpy.itemCode).isEqualTo("TEST-ITEM-CODE")
            assertThat(jpy.unitDivisor).isEqualByComparingTo("100")
        }
    }
}
