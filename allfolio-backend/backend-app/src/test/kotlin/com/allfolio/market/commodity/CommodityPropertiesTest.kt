package com.allfolio.market.commodity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 설정 키가 어긋나면 목록이 조용히 비고, 수집은 "대상 0건"으로 끝난다.
 * `MarketRatePropertiesTest`(AF-102)와 같은 자리다 — [CommodityPropertiesYamlTest]가
 * 진짜 `application.yml`을 보는 동안, 여기서는 합성 값으로 바인딩과 파생 목록만 본다.
 *
 * **fsc를 반드시 채워서 본다.** [CommodityProperties.allItems]에서 `+ fsc` 항이 사라지면
 * 증상은 KDoc이 예고한 "수집은 되는데 화면에 없다"이고, 오류도 로그도 없다.
 *
 * 뒤쪽 절반은 `@PostConstruct` [CommodityProperties.validate]가 오타난 설정으로 기동을
 * 막는지 본다 — 그 검사는 **유효한 설정으로는 아무 일도 안 일어나서** 깨져도 조용하다.
 */
class CommodityPropertiesTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)
        .withPropertyValues(
            "market-commodity.fred-daily[0].code=WTI",
            "market-commodity.fred-daily[0].series-id=DCOILWTICO",
            "market-commodity.fred-daily[0].unit=USD/bbl",
            "market-commodity.fred-daily[0].frequency=D",
            "market-commodity.fred-monthly[0].code=COPPER",
            "market-commodity.fred-monthly[0].series-id=PCOPPUSDM",
            "market-commodity.fred-monthly[0].unit=USD/MT",
            "market-commodity.fred-monthly[0].frequency=M",
            // FSC의 series-id는 오퍼레이션 이름이 아니라 **종목 단축코드**다 (04020000 = 금 1kg)
            "market-commodity.fsc[0].code=GOLD_KRX",
            "market-commodity.fsc[0].series-id=04020000",
            "market-commodity.fsc[0].unit=KRW/g",
            "market-commodity.fsc[0].frequency=D",
        )

    @Test
    fun `세 목록을 바인딩한다`() {
        runner.run { context ->
            val properties = context.getBean(CommodityProperties::class.java)

            assertThat(properties.fredDaily).hasSize(1)
            assertThat(properties.fredMonthly).hasSize(1)
            assertThat(properties.fsc).hasSize(1)
            assertThat(properties.fredDaily[0].seriesId).isEqualTo("DCOILWTICO")
            assertThat(properties.fredDaily[0].unit).isEqualTo("USD/bbl")
            assertThat(properties.fredDaily[0].frequency).isEqualTo("D")
        }
    }

    /**
     * **allItems·allCodes가 세 목록을 다 더한다.** 한 항이라도 빠지면 그 소스는 수집만 되고
     * 조회에서 사라지는데(또는 반대), 목록이 짧아질 뿐이라 오류도 로그도 안 난다.
     * 지금 빠뜨리기 가장 쉬운 항이 fsc다 — 실제 yml에서 비어 있어 YAML 테스트가 못 본다.
     */
    @Test
    fun `allCodes가 fsc까지 포함한 전체다`() {
        runner.run { context ->
            val properties = context.getBean(CommodityProperties::class.java)

            assertThat(properties.allItems).hasSize(3)
            assertThat(properties.allCodes).containsExactly("WTI", "COPPER", "GOLD_KRX")
        }
    }

    @Test
    fun `설정이 없으면 빈 목록이다`() {
        ApplicationContextRunner()
            .withUserConfiguration(TestConfig::class.java)
            .run { context ->
                val properties = context.getBean(CommodityProperties::class.java)
                assertThat(properties.allItems).isEmpty()
                assertThat(properties.allCodes).isEmpty()
            }
    }

    /**
     * **`frequency`는 한 글자다.** DB 컬럼이 `VARCHAR(1)`이라 `Daily` 같은 값이 들어오면
     * 바인딩도 CI도 초록인 채 운영 insert에서 길이 초과로 터진다.
     * 이제 [CommodityProperties.validate]가 기동 시점에 막지만, 이 단언은 "막힌 뒤에 남는 값이
     * 실제로 한 글자인가"를 본다 — 검사와 결과는 다른 것이다.
     */
    @Test
    fun `모든 항목의 주기가 D 또는 M 한 글자다`() {
        runner.run { context ->
            val properties = context.getBean(CommodityProperties::class.java)

            assertThat(properties.allItems).allSatisfy {
                assertThat(it.frequency).describedAs("frequency of ${it.code}").isIn("D", "M")
            }
        }
    }

    // ── @PostConstruct validate() ────────────────────────────────────────────
    //
    // 오타난 설정으로는 기동하지 않는다. 런타임 실패로 흘리면 매일 실패 한 줄이 쌓일 뿐이고
    // 그 종목은 계속 비어 있다. `MarketRatePropertiesTest`가 같은 자리에 같은 그물을 친다.

    /** 검사 대상 하나만 바꿔 넣는다 — 나머지는 유효해야 "이 한 가지가 잡혔다"를 말할 수 있다 */
    private fun gold(vararg overrides: String) = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)
        .withPropertyValues(
            "market-commodity.fsc[0].code=GOLD_KRX",
            "market-commodity.fsc[0].series-id=04020000",
            "market-commodity.fsc[0].unit=KRW/g",
            "market-commodity.fsc[0].frequency=D",
            *overrides,
        )

    @Test
    fun `code가 비어 있으면 기동에 실패한다`() {
        gold("market-commodity.fsc[0].code=").run { context ->
            assertThat(context).hasFailed()
            // 스프링이 BeanCreationException으로 감싸서 표면 메시지엔 우리 문구가 없다.
            // require()가 던진 원인 예외까지 내려가서 확인한다.
            // code가 비면 라벨로 쓸 code 자체가 없다 — 위치로 짚는다
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining("code가 비어 있습니다")
        }
    }

    @Test
    fun `series-id가 비어 있으면 기동에 실패한다`() {
        gold("market-commodity.fsc[0].series-id=").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining("GOLD_KRX")
                .hasMessageContaining("series-id가 비어 있습니다")
        }
    }

    /** 단위가 비면 화면이 단위 없는 숫자를 그린다 — 값 정책(PRICE)은 상한이 없어 아무것도 안 막는다 */
    @Test
    fun `unit이 비어 있으면 기동에 실패한다`() {
        gold("market-commodity.fsc[0].unit=").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining("unit이 비어 있습니다")
        }
    }

    /**
     * **이 검사가 `validate()`를 넣은 이유다.** DB 컬럼이 `VARCHAR(1)`이라 `Daily`는
     * 바인딩도 CI도 통과하고 **운영 insert에서** 길이 초과로 터진다 — 배포가 끝난 뒤,
     * 그것도 첫 수집 시각에야 드러나는 실패다.
     */
    @Test
    fun `frequency가 Daily면 기동에 실패한다`() {
        gold("market-commodity.fsc[0].frequency=Daily").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining("지원하지 않는 주기입니다: Daily")
        }
    }

    /** 길이만 맞는 엉뚱한 한 글자도 막는다 — 화면이 섹션을 가르는 근거가 이 값이다 */
    @Test
    fun `frequency가 W면 기동에 실패한다`() {
        gold("market-commodity.fsc[0].frequency=W").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining("지원하지 않는 주기입니다: W")
        }
    }

    /**
     * code 중복은 항목별 검사로는 못 잡는다 — 두 항목 다 개별로는 멀쩡하다.
     * **목록을 넘나드는 중복이 더 나쁜 판본이다**: 소스가 둘이라 값도 `source` 열도
     * 매 실행 뒤에 도는 쪽으로 뒤집히고, 제약조건도 요약도 여전히 조용하다.
     */
    @Test
    fun `목록을 넘나드는 code 중복이면 기동에 실패한다`() {
        gold(
            "market-commodity.fred-daily[0].code=GOLD_KRX",
            "market-commodity.fred-daily[0].series-id=DCOILWTICO",
            "market-commodity.fred-daily[0].unit=USD/bbl",
            "market-commodity.fred-daily[0].frequency=D",
        ).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining("GOLD_KRX")
                .hasMessageContaining("중복")
        }
    }

    /** 빈 코드가 둘이면 "빈 문자열이 중복됩니다"라는 읽을 수 없는 두 번째 문제가 붙으면 안 된다 */
    @Test
    fun `빈 code가 둘이어도 중복으로 보고하지 않는다`() {
        gold(
            "market-commodity.fsc[0].code=",
            "market-commodity.fred-daily[0].code=",
            "market-commodity.fred-daily[0].series-id=DCOILWTICO",
            "market-commodity.fred-daily[0].unit=USD/bbl",
            "market-commodity.fred-daily[0].frequency=D",
        ).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining("code가 비어 있습니다")
            assertThat(context.startupFailure).rootCause()
                .hasMessageNotContaining("중복")
        }
    }

    /** 유효한 설정은 그대로 기동한다 — 검사가 멀쩡한 설정을 막으면 그게 더 나쁘다 */
    @Test
    fun `유효한 설정은 기동한다`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
        }
    }

    @EnableConfigurationProperties(CommodityProperties::class)
    class TestConfig
}
