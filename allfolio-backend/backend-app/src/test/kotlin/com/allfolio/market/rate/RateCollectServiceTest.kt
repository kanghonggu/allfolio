package com.allfolio.market.rate

import com.allfolio.fx.EcosApiClient
import com.allfolio.fx.EcosApiException
import com.allfolio.fx.EcosObservation
import com.allfolio.fx.EcosParseResult
import com.allfolio.fx.EcosQuery
import com.allfolio.fx.EcosValuePolicy
import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class RateCollectServiceTest {

    private val from = LocalDate.of(2026, 8, 10)
    private val to = LocalDate.of(2026, 8, 12)
    private val now = LocalDateTime.of(2026, 8, 12, 9, 10)

    @Test
    fun `종목별로 조회해 저장하고 건수를 보고한다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            mapOf(
                "S1" to listOf(obs("2026-08-11", "3.10"), obs("2026-08-12", "3.15")),
                "S2" to listOf(obs("2026-08-12", "3.40")),
            ),
        )

        val summary = service(client, repo, series("KTB_3Y", "S1"), series("KTB_10Y", "S2")).collect(from, to, now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(3)
        assertThat(summary.updated).isZero()
        assertThat(summary.failed).isZero()
        assertThat(repo.saved).hasSize(3)
    }

    /**
     * 종목 하나가 터져도 나머지를 저장한다. 예외로 끝내면 살아 있던 값까지 같이 잃는다.
     */
    @Test
    fun `한 종목이 실패해도 나머지는 저장한다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            mapOf("S2" to listOf(obs("2026-08-12", "3.40"))),
            failing = mapOf("S1" to EcosApiException("HTTP-500", "ECOS가 HTTP 500 을 반환했습니다")),
        )

        val summary = service(client, repo, series("KTB_3Y", "S1"), series("KTB_10Y", "S2")).collect(from, to, now)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.collected).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).contains("KTB_3Y").contains("HTTP 500")
        assertThat(repo.saved.single().rateCode).isEqualTo("KTB_10Y")
    }

    /**
     * 같은 구간을 다시 수집하면 행이 늘지 않고 값만 덮인다.
     * 수집 창이 매번 2주를 재조회하므로 이게 깨지면 매일 행이 불어난다.
     *
     * 갱신분이 `saveAll`까지 갔는지도 같이 못 박는다. 인메모리 레포는 엔티티를 그대로 들고 있어서
     * 값만 보면 서비스가 저장을 아예 안 걸어도 통과한다 — 운영에선 detached 엔티티라 조용히 유실된다.
     */
    @Test
    fun `같은 구간을 다시 수집하면 덮어쓴다`() {
        val repo = FakeRepo()
        repo.saved += entity("KTB_3Y", LocalDate.of(2026, 8, 12), "3.10")
        val client = FakeClient(mapOf("S1" to listOf(obs("2026-08-12", "3.15"))))

        val summary = service(client, repo, series("KTB_3Y", "S1")).collect(from, to, now)

        assertThat(summary.inserted).isZero()
        assertThat(summary.updated).isEqualTo(1)
        assertThat(repo.saved).hasSize(1)
        assertThat(repo.saved.single().rateValue).isEqualByComparingTo("3.15")
        assertThat(repo.saved.single().collectedAt).isEqualTo(now)
        assertThat(repo.submitted).containsExactly(repo.saved.single())
    }

    /**
     * 손대지 않은 기존 행까지 다시 저장하지 않는다. merge는 행마다 SELECT를 하나씩 내므로
     * 2주 창 x 6종목이면 헛도는 왕복이 그대로 Neon 커넥션 점유가 된다.
     */
    @Test
    fun `값이 안 온 날의 기존 행은 다시 저장하지 않는다`() {
        val repo = FakeRepo()
        repo.saved += entity("KTB_3Y", LocalDate.of(2026, 8, 11), "3.05")
        val client = FakeClient(mapOf("S1" to listOf(obs("2026-08-12", "3.15"))))

        service(client, repo, series("KTB_3Y", "S1")).collect(from, to, now)

        assertThat(repo.submitted).extracting<LocalDate> { it.quoteDate }
            .containsExactly(LocalDate.of(2026, 8, 12))
    }

    @Test
    fun `버려진 행 수를 보고한다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            mapOf("S1" to listOf(obs("2026-08-12", "3.15"))),
            skipped = mapOf("S1" to 2),
        )

        val summary = service(client, repo, series("KTB_3Y", "S1")).collect(from, to, now)

        assertThat(summary.skippedRows).isEqualTo(2)
    }

    /**
     * 소스가 구간 밖 날짜를 섞어 주면 걷어낸다. 안 걷어내면 그 행이 새 UUID로 INSERT되어
     * 유니크 제약이 배치 전체를 죽인다 — 재실행해도 똑같이 죽는다.
     */
    @Test
    fun `요청 구간 밖 날짜는 걷어내고 센다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            mapOf(
                "S1" to listOf(
                    obs("2026-08-11", "3.10"),
                    obs("2026-08-20", "3.30"), // to(8/12) 이후
                    obs("2026-08-01", "3.05"), // from(8/10) 이전
                ),
            ),
        )

        val summary = service(client, repo, series("KTB_3Y", "S1")).collect(from, to, now)

        assertThat(summary.outOfRange).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(1)
        assertThat(repo.saved.single().quoteDate).isEqualTo(LocalDate.of(2026, 8, 11))
    }

    /**
     * 같은 날짜가 두 번 오면 한 행으로 접는다. 그대로 저장하면 uk_market_rate가 배치를 죽인다.
     * 뒤에 온 값을 남기는 건 ECOS 정정본이 뒤에 오는 형태이기 때문이다.
     */
    @Test
    fun `같은 날짜가 중복으로 오면 마지막 값만 남긴다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            mapOf("S1" to listOf(obs("2026-08-12", "3.10"), obs("2026-08-12", "3.15"))),
        )

        val summary = service(client, repo, series("KTB_3Y", "S1")).collect(from, to, now)

        assertThat(summary.inserted).isEqualTo(1)
        assertThat(repo.saved.single().rateValue).isEqualByComparingTo("3.15")
    }

    /**
     * 0건은 실패가 아니다 — 기준금리처럼 변경 시에만 공표되는 계열은 2주 창이 빌 수 있다.
     * 다만 코드가 죽어도 똑같이 0건이라, 이름을 남겨 사람이 보게 한다.
     */
    @Test
    fun `0건으로 돌아온 종목은 실패가 아니라 이름으로 남는다`() {
        val repo = FakeRepo()
        val client = FakeClient(mapOf("S1" to emptyList(), "S2" to listOf(obs("2026-08-12", "3.40"))))

        val summary = service(client, repo, series("BASE_RATE", "S1"), series("KTB_10Y", "S2")).collect(from, to, now)

        assertThat(summary.emptySeries).containsExactly("BASE_RATE")
        assertThat(summary.failed).isZero()
        assertThat(summary.collected).isEqualTo(1)
    }

    @Test
    fun `대상이 없으면 요청 0건으로 끝난다`() {
        val summary = service(FakeClient(emptyMap()), FakeRepo()).collect(from, to, now)

        assertThat(summary.requested).isZero()
        assertThat(summary.collected).isZero()
    }

    @Test
    fun `금리 정책으로 조회한다`() {
        val client = FakeClient(mapOf("S1" to emptyList()))

        service(client, FakeRepo(), series("KTB_3Y", "S1")).collect(from, to, now)

        // 환율 정책으로 부르면 0.00% 공표일이 조용히 사라진다
        assertThat(client.queries.single().valuePolicy).isEqualTo(EcosValuePolicy.PERCENT)
        assertThat(client.queries.single().cycle).isEqualTo("D")
    }

    private fun service(
        client: EcosApiClient,
        repo: FakeRepo,
        vararg series: MarketRateProperties.RateSeries,
    ): RateCollectService {
        val properties = MarketRateProperties().apply { this.series = series.toList() }
        return RateCollectService(client, repo, properties)
    }

    private fun series(code: String, statCode: String) = MarketRateProperties.RateSeries().apply {
        this.code = code
        this.statCode = statCode
        this.itemCode = "ITEM"
        this.cycle = "D"
    }

    private fun obs(date: String, value: String) = EcosObservation(LocalDate.parse(date), BigDecimal(value))

    private fun entity(code: String, date: LocalDate, value: String) = MarketRateEntity(
        id = java.util.UUID.randomUUID(),
        rateCode = code,
        quoteDate = date,
        rateValue = BigDecimal(value),
        source = "ECOS",
        collectedAt = LocalDateTime.of(2026, 8, 11, 18, 10),
    )

    /** statCode로 응답을 가른다 — 종목마다 다른 결과를 주려면 그 축이 필요하다 */
    private class FakeClient(
        private val rows: Map<String, List<EcosObservation>>,
        private val failing: Map<String, RuntimeException> = emptyMap(),
        private val skipped: Map<String, Int> = emptyMap(),
    ) : EcosApiClient {
        val queries = mutableListOf<EcosQuery>()

        override fun fetch(query: EcosQuery, from: LocalDate, to: LocalDate): EcosParseResult {
            queries += query
            failing[query.statCode]?.let { throw it }
            return EcosParseResult(rows[query.statCode] ?: emptyList(), skipped[query.statCode] ?: 0)
        }
    }

    /**
     * 인메모리 레포. JPA 레포 인터페이스 전체를 구현하지 않으려고 서비스가 쓰는 두 메서드만
     * 가진 좁은 포트를 둔다 — 그 포트가 RateCollectService.Store 다.
     *
     * [submitted]는 `saveAll`에 실제로 건네진 것만 모은다. [saved]는 갱신을 제자리에서 받으므로
     * 저장을 안 걸어도 값이 맞아 보이기 때문이다 — 운영에선 detached 엔티티라 그러면 유실된다.
     */
    private class FakeRepo : RateCollectService.Store {
        val saved = mutableListOf<MarketRateEntity>()
        val submitted = mutableListOf<MarketRateEntity>()

        override fun findRange(rateCode: String, from: LocalDate, to: LocalDate): List<MarketRateEntity> =
            saved.filter { it.rateCode == rateCode && it.quoteDate >= from && it.quoteDate <= to }

        override fun saveAll(entities: List<MarketRateEntity>) {
            submitted += entities
            entities.forEach { entity ->
                if (saved.none { it.rateCode == entity.rateCode && it.quoteDate == entity.quoteDate }) {
                    saved += entity
                }
            }
        }
    }
}
