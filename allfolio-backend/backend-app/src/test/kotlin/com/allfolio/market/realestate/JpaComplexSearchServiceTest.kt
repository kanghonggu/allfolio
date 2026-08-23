package com.allfolio.market.realestate

import com.allfolio.unifiedasset.infrastructure.jpa.ComplexRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal

/**
 * 단지·평형 검색.
 *
 * 이 서비스가 지키는 것은 둘이다 — **면적 정밀도를 그대로 넘긴다**(정확 일치 매칭의 전제)
 * · **표본 수를 함께 준다**(왜 평가가 안 나오는지 화면이 설명할 수 있어야 한다).
 */
class JpaComplexSearchServiceTest {

    private val repo = mock(com.allfolio.unifiedasset.infrastructure.jpa.RtmsDealCacheJpaRepository::class.java)
    private val service = JpaComplexSearchService(repo)

    private fun row(
        seq: String, name: String, area: String, count: Int,
        umd: String = "역삼동", year: Int? = 2002,
    ) = object : ComplexRow {
        override val aptSeq = seq
        override val aptName = name
        override val umdName = umd
        override val buildYear = year
        override val exclusiveAreaM2: BigDecimal = BigDecimal(area)
        override val dealCount = count
    }

    private fun stub(vararg rows: ComplexRow) {
        `when`(repo.findComplexRows(anyString(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(rows.toList())
    }

    /**
     * **면적을 그대로 넘긴다.** 반올림하면 정확 일치 매칭이 깨진다 — 같은 단지 안에
     * `84.83`과 `84.86`이 함께 있는 상황이 실측이다.
     */
    @Test
    fun `전용면적을 반올림하지 않고 그대로 준다`() {
        stub(row("11680-1", "개포래미안포레스트", "84.83", 3),
             row("11680-1", "개포래미안포레스트", "84.86", 2))

        val areas = service.search("11680", null).single().areas

        assertThat(areas.map { it.exclusiveAreaM2.toPlainString() })
            .containsExactly("84.83", "84.86")
    }

    /** 평은 참고값이라 소수 첫째 자리까지만 — 정밀해 보이면 매칭에 쓰인다고 오해한다 */
    @Test
    fun `평은 참고로 소수 한 자리까지만 준다`() {
        stub(row("11680-1", "단지", "84.93", 5))

        val a = service.search("11680", null).single().areas.single()

        assertThat(a.approxPyeong.toPlainString()).isEqualTo("25.7")
    }

    /**
     * **표본 수가 화면에 필요하다.** `(단지, 면적)`당 거래가 실측 분기 2건꼴이라,
     * 사용자가 고른 평형에 몇 건인지 알아야 "왜 평가가 안 나오는지"를 설명할 수 있다.
     */
    @Test
    fun `평형별 거래 수를 함께 준다`() {
        stub(row("11680-1", "단지", "59.92", 7), row("11680-1", "단지", "84.83", 2))

        val areas = service.search("11680", null).single().areas

        assertThat(areas.map { it.dealCount }).containsExactly(7, 2)
    }

    /** 사용자가 평형을 크기 순으로 찾는다 */
    @Test
    fun `면적은 작은 것부터 준다`() {
        stub(row("11680-1", "단지", "114.81", 1),
             row("11680-1", "단지", "59.92", 4),
             row("11680-1", "단지", "84.83", 2))

        val areas = service.search("11680", null).single().areas

        assertThat(areas.map { it.exclusiveAreaM2.toPlainString() })
            .containsExactly("59.92", "84.83", "114.81")
    }

    /** 찾는 것은 대개 거래가 있는 큰 단지다 */
    @Test
    fun `거래가 많은 단지를 먼저 준다`() {
        stub(row("11680-1", "작은단지", "84.0", 1),
             row("11680-2", "큰단지", "84.0", 10),
             row("11680-2", "큰단지", "59.0", 5))

        val names = service.search("11680", null).map { it.aptName }

        assertThat(names).containsExactly("큰단지", "작은단지")
    }

    @Test
    fun `단지별로 묶는다`() {
        stub(row("11680-1", "A", "84.0", 1), row("11680-1", "A", "59.0", 2),
             row("11680-2", "B", "84.0", 3))

        val result = service.search("11680", null)

        assertThat(result).hasSize(2)
        assertThat(result.first { it.aptSeq == "11680-1" }.areas).hasSize(2)
    }

    @Test
    fun `상한을 넘으면 자른다`() {
        stub(*(1..30).map { row("11680-$it", "단지$it", "84.0", it) }.toTypedArray())

        assertThat(service.search("11680", null, limit = 5)).hasSize(5)
    }

    @Test
    fun `단지 식별자와 이름을 함께 준다`() {
        stub(row("11680-4929", "개포래미안포레스트", "84.83", 3, umd = "개포동", year = 2020))

        val c = service.search("11680", null).single()

        assertThat(c.aptSeq).isEqualTo("11680-4929")
        assertThat(c.aptName).isEqualTo("개포래미안포레스트")
        assertThat(c.umdName).isEqualTo("개포동")
        assertThat(c.buildYear).isEqualTo(2020)
    }

    @Test
    fun `결과가 없으면 빈 목록이다`() {
        stub()

        assertThat(service.search("11680", "없는단지")).isEmpty()
    }
}
