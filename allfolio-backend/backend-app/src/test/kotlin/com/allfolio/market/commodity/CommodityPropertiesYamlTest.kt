package com.allfolio.market.commodity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest

/**
 * application.yml의 `market-commodity` 블록이 실제로 바인딩되는지 확인한다.
 * `MarketRatePropertiesYamlTest`(AF-102)를 그대로 따른다 — 이유도 같다.
 *
 * **이 테스트가 YAML 오타를 잡는 유일한 그물이다.** 시리즈 ID 한 글자가 틀리면 그 종목만
 * 조용히 0건이 되고, 종목 하나를 빠뜨리면 목록이 조용히 짧아질 뿐 기동도 수집도 초록이다.
 * 그래서 16종의 시리즈 ID를 전부 단언한다.
 */
@SpringBootTest(
    classes = [
        CommodityPropertiesYamlTest.TestApplication::class,
        CommodityProperties::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
class CommodityPropertiesYamlTest {

    @Autowired
    private lateinit var properties: CommodityProperties

    @Test
    fun `일간 3종과 월간 13종이 바인딩된다`() {
        assertThat(properties.fredDaily).hasSize(3)
        assertThat(properties.fredMonthly).hasSize(13)
    }

    /**
     * Task 1에서 FRED API로 실재를 확인한 값 그대로다. 여기서 단언이 깨지면
     * **누가 시리즈 ID를 고쳤다는 뜻이지 테스트가 낡았다는 뜻이 아니다** — FRED에서
     * 다시 확인하고 나서 이 표를 고칠 것. 오타 하나가 조용히 0건 수집이 된다.
     */
    @Test
    fun `일간 시리즈 ID가 확인한 값과 일치한다`() {
        assertThat(properties.fredDaily.map { "${it.code}=${it.seriesId}" })
            .containsExactly(
                "WTI=DCOILWTICO",       // WTI 원유(EIA)
                "BRENT=DCOILBRENTEU",   // 브렌트유(EIA)
                "NATGAS=DHHNGSP",       // 천연가스 헨리허브(EIA)
            )
    }

    @Test
    fun `월간 시리즈 ID가 확인한 값과 일치한다`() {
        assertThat(properties.fredMonthly.map { "${it.code}=${it.seriesId}" })
            .containsExactly(
                "COPPER=PCOPPUSDM",         // 구리
                "NICKEL=PNICKUSDM",         // 니켈
                "ZINC=PZINCUSDM",           // 아연
                "ALUMINUM=PALUMUSDM",       // 알루미늄
                "IRON_ORE=PIORECRUSDM",     // 철광석
                "COAL_AU=PCOALAUUSDM",      // 호주 석탄
                "URANIUM=PURANUSDM",        // 우라늄
                "WHEAT=PWHEAMTUSDM",        // 밀
                "CORN=PMAIZMTUSDM",         // 옥수수 — MAIZ다. CORN이 아니다
                "SOYBEANS=PSOYBUSDM",       // 대두
                "SUGAR=PSUGAISAUSDM",       // 설탕(ISA)
                "COFFEE=PCOFFOTMUSDM",      // 커피(other mild arabica)
                "ALL_INDEX=PALLFNFINDEXM",  // 전체 원자재 지수
            )
    }

    /**
     * 코드 중복은 목록 전체를 봐야 알 수 있다. 저장 키가 (code, quoteDate)라 같은 배치에서
     * 뒤 항목이 앞 항목을 덮어쓸 뿐 제약조건은 안 걸리고, 요약은 초록인 채 종목 하나가 사라진다.
     */
    @Test
    fun `allCodes가 16종이고 중복이 없다`() {
        assertThat(properties.allCodes).hasSize(16)
        assertThat(properties.allCodes).doesNotHaveDuplicates()
    }

    /**
     * 단위·주기는 응답이 아니라 설정에서만 온다 — 비면 화면이 단위 없는 숫자를 그린다.
     *
     * **주기는 비어 있지 않은 것으로 부족하고 한 글자여야 한다.** DB 컬럼이 `VARCHAR(1)`이라
     * `Daily`가 들어오면 CI는 초록이고 운영 insert에서 길이 초과로 터진다. `allItems`로 도는
     * 이유는 Task 4에서 채울 fsc까지 같은 그물에 들어오게 하려는 것이다.
     */
    @Test
    fun `모든 항목에 단위와 한 글자 주기가 있다`() {
        assertThat(properties.allItems).allSatisfy {
            assertThat(it.unit).describedAs("unit of ${it.code}").isNotBlank()
            assertThat(it.frequency).describedAs("frequency of ${it.code}").isIn("D", "M")
        }
        assertThat(properties.fredDaily.map { it.frequency }).containsOnly("D")
        assertThat(properties.fredMonthly.map { it.frequency }).containsOnly("M")
    }

    /**
     * **`USc/lb`(센트)와 `USD/lb`(달러)는 100배 차이다.** 커피 307.83 USc/lb = 3.0783 USD/lb.
     * 보기 싫다고 통일하면 설탕·커피가 100배로 그려지고, 값 정책(PRICE)은 상한이 없어
     * 아무것도 막지 않는다. 우라늄만 USD/lb인 것이 정상이다.
     */
    @Test
    fun `센트 단위가 달러로 정규화되지 않았다`() {
        val units = properties.allItems.associate { it.code to it.unit }

        assertThat(units["SUGAR"]).isEqualTo("USc/lb")
        assertThat(units["COFFEE"]).isEqualTo("USc/lb")
        assertThat(units["URANIUM"]).isEqualTo("USD/lb")
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication
}
