package com.allfolio.market.rate

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest

/**
 * application.yml의 `market-rate` 블록이 실제로 바인딩되는지 확인한다 (AF-102).
 * `MarketIndexPropertiesTest`(AF-101)를 그대로 따른다 — 이유도 같다.
 *
 * [MarketRatePropertiesTest]는 합성 값으로 바인딩·검증 로직만 본다. 진짜 `application.yml`은
 * 아무도 안 건드린다 — 여기서 properties로 덮어써 버리면 검증 대상이 사라진다.
 *
 * **이 테스트가 YAML 오타를 잡는 유일한 그물이다.** 종목 하나를 빠뜨리거나 키를 잘못 쓰면
 * 목록이 조용히 줄어들 뿐 기동은 되고, 수집도 남은 종목만 돌면서 초록으로 끝난다.
 * 그래서 항목 코드까지 단언한다 — 코드 한 자리가 바뀌면 다른 만기가 들어오기 때문이다
 * (010200000 국고채 3년 vs 010200001 국고채 5년).
 */
@SpringBootTest(
    classes = [
        MarketRatePropertiesYamlTest.TestApplication::class,
        MarketRateProperties::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
class MarketRatePropertiesYamlTest {

    @Autowired
    private lateinit var properties: MarketRateProperties

    @Test
    fun `application yml에 금리 6종이 확인한 코드로 들어 있다`() {
        assertThat(properties.ecos.map { it.code })
            .containsExactly("BASE_RATE", "CALL_ON", "CD_91D", "KTB_3Y", "KTB_10Y", "CORP_AA3Y")
    }

    /**
     * 2026-08-13에 ECOS 목록 API로 확인한 값 그대로다. 여기서 단언이 깨지면
     * **누가 코드를 고쳤다는 뜻이지 테스트가 낡았다는 뜻이 아니다** — 고친 값이
     * 어느 항목인지 /ecos/items로 다시 확인하고 나서 이 표를 고칠 것.
     */
    @Test
    fun `통계표와 항목 코드가 확인한 값과 일치한다`() {
        assertThat(properties.ecos.map { "${it.code}=${it.statCode}/${it.itemCode}" })
            .containsExactly(
                "BASE_RATE=722Y001/0101000",     // 한국은행 기준금리
                "CALL_ON=817Y002/010101000",     // 콜금리(1일, 전체거래)
                "CD_91D=817Y002/010502000",      // CD(91일)
                "KTB_3Y=817Y002/010200000",      // 국고채(3년) — 010200001은 5년이다
                "KTB_10Y=817Y002/010210000",     // 국고채(10년)
                "CORP_AA3Y=817Y002/010300000",   // 회사채(3년, AA-)
            )
    }

    /** 클라이언트가 D만 받는다. 다른 주기가 섞이면 그 종목은 매 실행 실패로 남는다 */
    @Test
    fun `전 종목이 일별 주기다`() {
        assertThat(properties.ecos.map { it.cycle }).containsOnly("D")
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication
}
