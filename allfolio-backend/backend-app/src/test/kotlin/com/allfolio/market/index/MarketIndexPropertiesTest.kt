package com.allfolio.market.index

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalTime
import java.time.ZoneId

/**
 * application.yml의 `market-index` 블록이 실제로 바인딩되는지 확인한다 (AF-101).
 *
 * 이 테스트가 있어야 하는 이유: YAML 오타는 컴파일 오류가 아니다.
 * 키 하나가 틀리면 [MarketIndexProperties.domestic]이 조용히 빈 리스트가 되고,
 * 수집 배치는 "0건 성공"으로 끝나 아무도 눈치채지 못한다.
 *
 * 값을 여기서 주입하지 않고 진짜 application.yml을 읽게 두는 것도 같은 이유다 —
 * properties로 덮어쓰면 검증 대상이 사라진다.
 */
@SpringBootTest(
    classes = [
        MarketIndexPropertiesTest.TestApplication::class,
        MarketIndexProperties::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
class MarketIndexPropertiesTest {

    @Autowired
    private lateinit var properties: MarketIndexProperties

    @Test
    fun `application yml의 국내 지수 목록이 바인딩된다`() {
        assertThat(properties.domestic.map { it.code })
            .containsExactly("KOSPI", "KOSDAQ", "KOSPI200", "KOSDAQ150", "KRX300")
        // 코드의 출처는 KIS 지수코드 마스터(idxcode.mst)다 — application.yml 주석 참조.
        // 파생상품(레버리지·선물·인버스·TR)과 한 글자 차이라 눈으로 훑으면 놓친다
        assertThat(properties.domestic.map { it.kisIscd })
            .containsExactly("0001", "1001", "2001", "3003", "4300")
    }

    @Test
    fun `해외 지수 아홉 종이 설정에서 바인딩된다`() {
        assertThat(properties.overseas.map { it.code })
            .containsExactly("SPX", "NASDAQ", "DOW", "NASDAQ100", "VIX",
                             "STOXX50", "NIKKEI225", "HANGSENG", "SHANGHAI")
    }

    // 하이픈 표기(kis-iscd)가 카멜케이스(kisIscd)로 완화 바인딩되는지.
    // 여기가 비면 수집이 빈 문자열을 KIS에 보낸다.
    @Test
    fun `KIS 코드와 검증 이름이 비어 있지 않다`() {
        assertThat(properties.overseas).allSatisfy {
            assertThat(it.kisIscd).isNotBlank()
            assertThat(it.nameContains).isNotBlank()
            assertThat(it.zoneId).isNotBlank()
            assertThat(it.schedule).isNotBlank()
            assertThat(it.closeLocalTime).isNotBlank()
        }
    }

    // zoneId와 같은 이유다 — "16:00"이 "16.00"이나 "4:00 PM"으로 적히면 컴파일도 not-blank도
    // 통과하고, 수집이 그 지수만 조용히 실패시킨다. 아홉 종을 여기서 한 번에 판다.
    @Test
    fun `모든 마감 시각이 파싱된다`() {
        assertThat(properties.overseas).allSatisfy {
            assertThatCode { LocalTime.parse(it.closeLocalTime) }.doesNotThrowAnyException()
        }
    }

    // 마감 시각이 전부 같은 값이면 설정이 아니라 상수를 쓴 것과 같다 — 시장마다 다르다는 것이
    // 이 필드의 존재 이유이므로 대표 셋을 못 박는다(도쿄 15:30은 2024-11 개편 반영값이다)
    @Test
    fun `시장마다 마감 시각이 다르다`() {
        fun closeOf(code: String) = properties.overseas.single { it.code == code }.closeLocalTime

        assertThat(LocalTime.parse(closeOf("SPX"))).isEqualTo(LocalTime.of(16, 0))
        assertThat(LocalTime.parse(closeOf("NIKKEI225"))).isEqualTo(LocalTime.of(15, 30))
        assertThat(LocalTime.parse(closeOf("SHANGHAI"))).isEqualTo(LocalTime.of(15, 0))
        assertThat(LocalTime.parse(closeOf("STOXX50"))).isEqualTo(LocalTime.of(17, 30))
    }

    // schedule은 "US" | "ASIA" 둘 중 하나여야 한다. 오타("Us", "usa")는 컴파일도, 위의
    // not-blank 검사도 통과한다 — 후속 태스크의 `overseas.filter { it.schedule == schedule }`가
    // 조용히 0건이 되고서야 드러난다. 그 조용한 0건을 여기서 미리 잡는다.
    @Test
    fun `schedule은 US 또는 ASIA 둘 중 하나다`() {
        assertThat(properties.overseas.map { it.schedule })
            .allMatch { it == "US" || it == "ASIA" }
    }

    /**
     * yml의 `schedule` 문자열과 [OverseasSchedule] 상수가 **양방향으로** 맞물리는지 (AF-110).
     *
     * 컨트롤러는 이 enum으로 요청을 받고 `schedule.name`을 서비스에 넘기므로, 둘이 어긋나면
     * 어느 쪽으로든 조용히 망가진다:
     * - yml에만 있는 값(`EU` 같은 슬롯 신설)은 그 지수들을 **부를 방법이 없다** — enum에 없어
     *   URL로 요청하면 400이고, 아무 cron도 그 묶음에 닿지 않는다. 실패가 아니라 침묵이다.
     * - enum에만 있는 상수는 그 슬롯 cron이 매번 `requested == 0` → 500으로 잡을 빨갛게 만든다.
     *
     * 위 테스트가 문자열 리터럴로 한쪽만 보므로, 대응 관계는 여기서 못 박는다.
     */
    @Test
    fun `모든 schedule 값이 OverseasSchedule 상수와 일대일로 맞물린다`() {
        val configured = properties.overseas.map { it.schedule }.toSet()
        val constants = OverseasSchedule.entries.map { it.name }.toSet()

        assertThat(configured)
            .describedAs("yml에만 있는 슬롯은 어떤 cron도 부를 수 없다")
            .isEqualTo(constants)
    }

    // 유로스톡스는 유럽 타임존이지만 미국 슬롯에 실린다 — 마감(계절에 따라 15:30~16:30 UTC)이
    // 아시아 슬롯(08:30 UTC)보다 7~8시간 늦어서다. 자세한 근거는 application.yml의 주석에 있다.
    // 이걸 "고쳐서" ASIA로 옮기면 화면이 늘 하루 뒤처진다.
    @Test
    fun `유로스톡스는 미국 슬롯에 실린다`() {
        val stoxx = properties.overseas.single { it.code == "STOXX50" }

        assertThat(stoxx.schedule).isEqualTo("US")
        assertThat(stoxx.zoneId).isEqualTo("Europe/Berlin")
    }

    // zoneId가 실재하는 타임존인지. 오타는 런타임에야 터진다.
    @Test
    fun `모든 타임존이 실재한다`() {
        assertThat(properties.overseas).allSatisfy {
            assertThatCode { ZoneId.of(it.zoneId) }.doesNotThrowAnyException()
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication
}
