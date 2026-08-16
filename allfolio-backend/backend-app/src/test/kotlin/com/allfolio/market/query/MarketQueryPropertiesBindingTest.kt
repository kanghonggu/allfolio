package com.allfolio.market.query

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * `market.indices-enabled`의 배선을 **실제 application.yml에 대고** 못 박는다 (AF-104).
 *
 * 이 플래그는 AF-108 재배포 검토가 KIS 개인용 오픈API에 대해 미결이라 존재한다. 답이 "불가"로
 * 오면 이 값을 false로 뒤집는 것이 대응의 전부다. 그런데 **배선이 고장 나는 방식은 전부
 * "그래도 지수가 나간다"로 수렴한다** — `@ConfigurationProperties`는 기본이
 * `ignoreUnknownFields = true`고 [MarketQueryProperties.indicesEnabled]의 기본값도 `true`라,
 * 접두사 오타·yml 키 개명·`market:` 블록 이동·플레이스홀더 편집 중 무엇이 일어나도 기동은
 * 성공하고 값은 켜진 채로 남는다. 운영자는 대시보드를 뒤집고 재시작한 뒤 응답에서 KOSPI를 본다.
 *
 * 그래서 직접 생성한 객체가 아니라 스프링 컨텍스트로, 테스트용 yml이 아니라 실제 yml로 확인한다
 * (관례: `EcosPropertiesBindingTest`). 같은 부류의 실패를 이 저장소는 이미 일급으로 다룬다 —
 * `MarketIndexAdminController`가 `requested == 0`에 500을 내는 것도 접두사 드리프트를 잡으려는 것이다.
 */
class MarketQueryPropertiesBindingTest {

    private val runner = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withUserConfiguration(TestConfig::class.java)

    @EnableConfigurationProperties(MarketQueryProperties::class)
    class TestConfig

    /**
     * 아무것도 주입하지 않았을 때 실제 yml이 주는 값.
     *
     * **이 테스트 하나로는 접두사 드리프트를 못 잡는다** — 바인딩이 통째로 실패해도 필드 기본값이
     * `true`라 결과가 같다. 그건 아래 환경변수 테스트가 잡는다. 여기서 지키는 건 "지금은 켜져 있다"
     * 쪽이다: 누가 yml 기본값을 false로 바꾸면 지수 두 탭이 조용히 사라지는데,
     * 그 증상은 "수집이 안 됐나?"로 오진하기 딱 좋다.
     */
    @Test
    fun `실제 application_yml에서 지수 노출은 기본으로 켜져 있다`() {
        runner.run { context ->
            assertThat(context.getBean(MarketQueryProperties::class.java).indicesEnabled).isTrue()
        }
    }

    /**
     * **프로퍼티 이름이 아니라 환경변수 이름으로 단언한다.** 운영자가 Render 대시보드에 실제로
     * 입력하는 것은 `MARKET_INDICES_ENABLED`이고, 그 이름이 먹는 이유는 오로지 yml에
     * `indices-enabled:` 뒤에 `MARKET_INDICES_ENABLED`를 읽는 플레이스홀더(기본값 true)가 있기 때문이다.
     * relaxed binding에만 기대면 스프링이 찾는 이름은 하이픈이 지워진 `MARKET_INDICESENABLED`라
     * 운영자가 넣은 변수는 아무 일도 하지 않는다. 그래서 누가 플레이스홀더를 지우고 `true`를
     * 박아 넣는 순간 이 테스트가 깨져야 한다 — 그러지 않으면 그 사실을 사고 당일에 안다.
     *
     * `withPropertyValues("MARKET_INDICES_ENABLED=false")`로 대신하면 안 된다. 그건 평범한
     * MapPropertySource라 진짜 환경변수와 이름 해석 규칙이 다르다. 환경변수의 특별 취급은
     * [SystemEnvironmentPropertySource]라는 **타입**에 붙어 있으므로 그 타입으로 갈아 끼운다.
     */
    @Test
    fun `환경변수 MARKET_INDICES_ENABLED가 false면 지수 노출이 꺼진다`() {
        runner.withInitializer { context: ConfigurableApplicationContext ->
            context.environment.propertySources.replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    mapOf<String, Any>("MARKET_INDICES_ENABLED" to "false"),
                ),
            )
        }.run { context ->
            assertThat(context.getBean(MarketQueryProperties::class.java).indicesEnabled).isFalse()
        }
    }

    /** 원자재도 같은 블록·같은 관례다. 기본은 켜져 있다 */
    @Test
    fun `실제 application_yml에서 원자재 노출은 기본으로 켜져 있다`() {
        runner.run { context ->
            assertThat(context.getBean(MarketQueryProperties::class.java).commoditiesEnabled).isTrue()
        }
    }

    /**
     * **원자재 스위치도 환경변수 이름으로 단언한다.** 이유는 위 지수 테스트와 같다 —
     * relaxed binding만으로는 스프링이 `MARKET_COMMODITIESENABLED`를 찾으므로, 운영자가 Render
     * 대시보드에 넣는 `MARKET_COMMODITIES_ENABLED`가 먹는 이유는 오로지 yml의 플레이스홀더다.
     *
     * **지수를 끄는 값이 원자재까지 끄지 않는 것도 함께 못 박는다.** 두 스위치를 한 필드로
     * 합치거나 플레이스홀더를 복사하다 이름을 안 바꾸면, 지수 하나를 끄려던 조작이
     * 멀쩡한 탭까지 지운다 — 그 증상은 "수집이 안 됐나?"로 오진하기 딱 좋다.
     */
    @Test
    fun `환경변수 MARKET_COMMODITIES_ENABLED가 false면 원자재 노출만 꺼진다`() {
        runner.withInitializer { context: ConfigurableApplicationContext ->
            context.environment.propertySources.replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    mapOf<String, Any>("MARKET_COMMODITIES_ENABLED" to "false"),
                ),
            )
        }.run { context ->
            val properties = context.getBean(MarketQueryProperties::class.java)
            assertThat(properties.commoditiesEnabled).isFalse()
            assertThat(properties.indicesEnabled).isTrue()
        }
    }
}
