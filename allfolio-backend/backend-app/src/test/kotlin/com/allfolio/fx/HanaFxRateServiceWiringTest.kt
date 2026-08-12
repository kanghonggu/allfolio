package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * [HanaFxRateService]가 `@Primary`이면서 같은 인터페이스([FxRateService])를 주입받는 구조라,
 * 순환 참조나 자기 자신 주입으로 컨텍스트가 죽을 수 있다. 그게 나면 단위 테스트는 전부 초록인데
 * 서버가 안 뜬다 — 그래서 실제 컨텍스트로 확인한다.
 *
 * 동시에 이 태스크의 본질(`@Primary`로 평가 경로를 갈아끼운다)을 고정한다.
 * `@Primary`가 빠지면 [CurrencyConverter]가 [RedisFxRateService]를 받아 USD가 조용히
 * 예전 동작으로 돌아간다 — 오류도 로그도 없이.
 */
@SpringBootTest(
    classes = [
        HanaFxRateServiceWiringTest.TestApplication::class,
        RedisFxRateService::class,
        HanaFxRateService::class,
        CurrencyConverter::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
    ],
)
class HanaFxRateServiceWiringTest {

    @MockBean private lateinit var redisTemplate: StringRedisTemplate

    @MockBean private lateinit var quotes: HanaFxQuoteJpaRepository

    @Autowired private lateinit var hana: HanaFxRateService

    @Autowired private lateinit var byInterface: FxRateService

    @Autowired private lateinit var currencyConverter: CurrencyConverter

    @Test
    fun `FxRateService를 요구하는 쪽은 하나은행 구현을 받는다`() {
        assertThat(byInterface).isSameAs(hana)
    }

    @Test
    fun `자기 자신이 아니라 Redis 구현을 위임 대상으로 받는다`() {
        val delegate = HanaFxRateService::class.java
            .getDeclaredField("delegate")
            .apply { isAccessible = true }
            .get(hana)

        assertThat(delegate).isInstanceOf(RedisFxRateService::class.java)
    }

    @Test
    fun `평가 경로의 CurrencyConverter도 하나은행 구현을 탄다`() {
        val injected = CurrencyConverter::class.java
            .getDeclaredField("fxRateService")
            .apply { isAccessible = true }
            .get(currencyConverter)

        assertThat(injected).isSameAs(hana)
    }

    @SpringBootConfiguration
    class TestApplication
}
