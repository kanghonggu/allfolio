package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 하나은행 스크래퍼와 같은 원칙 — 빈 응답으로 기존 값을 덮지 않는다.
 * 마크업이든 통계표 코드든, 틀리면 조용히 0건이 오기 때문이다.
 */
class FxRateBackfillServiceTest {

    private val from = LocalDate.of(2025, 8, 7)
    private val to = LocalDate.of(2025, 8, 11)

    private val properties = EcosProperties(
        apiKey = "key",
        series = mapOf("USD" to EcosProperties.Series("STAT", "ITEM", BigDecimal.ONE)),
    )

    @Test
    fun `가져온 환율을 저장하고 요약을 반환한다`() {
        val repo = FakeRepo()
        val client = FakeClient(
            EcosParseResult(
                listOf(
                    EcosRate(LocalDate.of(2025, 8, 7), BigDecimal("1380.0")),
                    EcosRate(LocalDate.of(2025, 8, 8), BigDecimal("1385.5")),
                ),
                skipped = 1,
            ),
        )

        val summary = service(client, repo).backfill("usd", from, to)

        assertThat(summary.currency).isEqualTo("USD")
        assertThat(summary.from).isEqualTo(from)
        assertThat(summary.to).isEqualTo(to)
        assertThat(summary.saved).isEqualTo(2)
        assertThat(summary.skipped).isEqualTo(1)
        assertThat(summary.firstDate).isEqualTo(LocalDate.of(2025, 8, 7))
        assertThat(summary.lastDate).isEqualTo(LocalDate.of(2025, 8, 8))
        assertThat(repo.saved).hasSize(2)
        assertThat(repo.saved.map { it.currency }).containsOnly("USD")
        assertThat(repo.saved.map { it.source }).containsOnly("ECOS")
        // 시계열 좌표는 설정에서 온 것을 그대로 넘긴다 — 통화별로 다른 통계표를 탄다
        assertThat(client.lastStatCode).isEqualTo("STAT")
        assertThat(client.lastItemCode).isEqualTo("ITEM")
    }

    @Test
    fun `응답이 0건이면 아무것도 쓰지 않고 실패한다`() {
        val repo = FakeRepo()
        val client = FakeClient(EcosParseResult(emptyList(), skipped = 0))

        assertThatThrownBy { service(client, repo).backfill("USD", from, to) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("0건")

        assertThat(repo.saved).isEmpty()
    }

    @Test
    fun `이미 있는 날짜는 새 행을 만들지 않고 값을 덮는다`() {
        val existing = row(LocalDate.of(2025, 8, 7), "1111.0")
        val repo = FakeRepo(existing)
        val client = FakeClient(
            EcosParseResult(listOf(EcosRate(LocalDate.of(2025, 8, 7), BigDecimal("1380.0"))), 0),
        )

        service(client, repo).backfill("USD", from, to)

        assertThat(repo.saved).hasSize(1)
        assertThat(repo.saved.single().id).isEqualTo(existing.id)
        assertThat(repo.saved.single().rateKrw).isEqualByComparingTo("1380.0")
    }

    @Test
    fun `고시 단위를 1단위로 정규화해 저장한다`() {
        val repo = FakeRepo()
        val jpyProperties = EcosProperties(
            apiKey = "key",
            series = mapOf("JPY" to EcosProperties.Series("STAT", "ITEM", BigDecimal("100"))),
        )
        val client = FakeClient(
            EcosParseResult(listOf(EcosRate(LocalDate.of(2025, 8, 7), BigDecimal("950.0"))), 0),
        )

        service(client, repo, jpyProperties).backfill("JPY", from, to)

        // 100엔당 950원 → 1엔당 9.5원
        assertThat(repo.saved.single().rateKrw).isEqualByComparingTo("9.5")
    }

    @Test
    fun `설정에 없는 통화는 거부한다`() {
        val repo = FakeRepo()

        assertThatThrownBy {
            service(FakeClient(EcosParseResult(emptyList(), 0)), repo).backfill("EUR", from, to)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("EUR")

        assertThat(repo.saved).isEmpty()
    }

    @Test
    fun `from이 to보다 뒤면 거부한다`() {
        val repo = FakeRepo()

        assertThatThrownBy {
            service(FakeClient(EcosParseResult(emptyList(), 0)), repo).backfill("USD", to, from)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThat(repo.saved).isEmpty()
    }

    @Test
    fun `소문자 YAML 키로 설정된 통화도 찾는다`() {
        // 대문자 맵 키는 환경변수 relaxed binding으로 표현할 수 없어(ECOS_SERIES_JPY_STAT_CODE →
        // ecos.series.jpy.*) YAML 키가 소문자로 들어올 수 있다. 대소문자를 따지면
        // "설정이 없는 통화"로 오진하고, 그건 설정 문제로 위장한 코드 문제다.
        val repo = FakeRepo()
        val lowerCaseProperties = EcosProperties(
            apiKey = "key",
            series = mapOf("usd" to EcosProperties.Series("LOWER-STAT", "LOWER-ITEM", BigDecimal.ONE)),
        )
        val client = FakeClient(
            EcosParseResult(listOf(EcosRate(LocalDate.of(2025, 8, 7), BigDecimal("1380.0"))), 0),
        )

        val summary = service(client, repo, lowerCaseProperties).backfill("USD", from, to)

        assertThat(client.lastStatCode).isEqualTo("LOWER-STAT")
        assertThat(summary.currency).isEqualTo("USD")
        // 맵 키 대소문자와 무관하게 저장 통화는 항상 대문자다 — 조회 쪽이 대문자로만 찾는다
        assertThat(repo.saved.single().currency).isEqualTo("USD")
    }

    @Test
    fun `같은 날짜가 중복으로 오면 한 행만 저장한다`() {
        // 파서는 중복을 걸러내지 않는다(EcosResponseParserTest가 그 동작을 고정해 뒀다).
        // 그대로 넘기면 (base_date, currency) UNIQUE 제약 위반으로 배치 전체가 깨진다.
        val repo = FakeRepo()
        val client = FakeClient(
            EcosParseResult(
                listOf(
                    EcosRate(LocalDate.of(2025, 8, 7), BigDecimal("1380.0")),
                    EcosRate(LocalDate.of(2025, 8, 7), BigDecimal("1390.0")),
                    EcosRate(LocalDate.of(2025, 8, 8), BigDecimal("1385.5")),
                ),
                skipped = 0,
            ),
        )

        val summary = service(client, repo).backfill("USD", from, to)

        assertThat(repo.saved).hasSize(2)
        assertThat(summary.saved).isEqualTo(2)
        // 뒤에 온 값이 이긴다 — ECOS가 같은 날짜를 두 번 주면 나중 행이 정정치다
        assertThat(repo.saved.single { it.baseDate == LocalDate.of(2025, 8, 7) }.rateKrw)
            .isEqualByComparingTo("1390.0")
    }

    @Test
    fun `백필이 성공하면 어댑터 캐시를 비운다`() {
        // 어댑터는 확정 과거 환율을 무기한 캐싱한다. 백필이 같은 날짜 값을 정정해도
        // 캐시를 비우지 않으면 살아있는 프로세스가 낡은 값을 계속 내놓는다 —
        // 키 없이 먼저 배포하고 나중에 API로 백필하는 계획이라 실제로 벌어지는 경로다.
        val repo = FakeRepo(row(from, "1111.0"))
        val converter = adapter(repo)
        val client = FakeClient(EcosParseResult(listOf(EcosRate(from, BigDecimal("1380.0"))), 0))

        val beforeBackfill = converter.toKrwOn(BigDecimal("1"), "USD", from)
        service(client, repo, converter = converter).backfill("USD", from, to)
        val afterBackfill = converter.toKrwOn(BigDecimal("1"), "USD", from)

        assertThat(beforeBackfill.amountKrw).isEqualByComparingTo("1111")
        assertThat(afterBackfill.amountKrw).isEqualByComparingTo("1380")
    }

    @Test
    fun `백필이 실패하면 캐시를 비우지 않는다`() {
        // 실패 경로가 캐시를 날리면 ECOS가 죽어 있는 동안 sync가 DB를 반복해서 두드린다.
        val repo = FakeRepo(row(from, "1111.0"))
        val converter = adapter(repo)
        val client = FakeClient(EcosParseResult(emptyList(), 0))

        converter.toKrwOn(BigDecimal("1"), "USD", from)
        // 백필과 무관하게 DB 값이 바뀌어도 캐시가 살아 있으면 옛 값이 그대로 나온다
        repo.rows[0] = row(from, "2222.0")

        assertThatThrownBy { service(client, repo, converter = converter).backfill("USD", from, to) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(converter.toKrwOn(BigDecimal("1"), "USD", from).amountKrw).isEqualByComparingTo("1111")
    }

    @Test
    fun `클라이언트 예외는 그대로 올려보낸다`() {
        // 호출자(어드민 엔드포인트)가 code로 상태를 가른다 — 여기서 삼키면 그 정보가 사라진다.
        val repo = FakeRepo()
        val client = ExplodingClient(EcosApiException("INFO-200", "해당하는 데이터가 없습니다"))

        assertThatThrownBy { service(client, repo).backfill("USD", from, to) }
            .isInstanceOf(EcosApiException::class.java)
            .extracting { (it as EcosApiException).code }
            .isEqualTo("INFO-200")

        assertThat(repo.saved).isEmpty()
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun service(
        client: EcosApiClient,
        repo: HistoricalFxRateJpaRepository,
        properties: EcosProperties = this.properties,
        converter: UnifiedAssetFxConverterAdapter = adapter(repo),
    ) = FxRateBackfillService(client, repo, properties, converter)

    private fun adapter(repo: HistoricalFxRateJpaRepository) =
        UnifiedAssetFxConverterAdapter(CurrencyConverter(StubFxRateService()), repo)

    private fun row(date: LocalDate, rate: String) = HistoricalFxRateEntity(
        id = UUID.randomUUID(), baseDate = date, currency = "USD",
        rateKrw = BigDecimal(rate), source = "ECOS", createdAt = LocalDateTime.now(),
    )

    private class StubFxRateService : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1350")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("90000000")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
    }

    private open class FakeClient(private val result: EcosParseResult) : EcosApiClient {
        var lastStatCode: String? = null
        var lastItemCode: String? = null

        override fun fetchDailyRates(
            statCode: String,
            itemCode: String,
            from: LocalDate,
            to: LocalDate,
        ): EcosParseResult {
            lastStatCode = statCode
            lastItemCode = itemCode
            return result
        }
    }

    private class ExplodingClient(private val failure: RuntimeException) :
        FakeClient(EcosParseResult(emptyList(), 0)) {
        override fun fetchDailyRates(
            statCode: String,
            itemCode: String,
            from: LocalDate,
            to: LocalDate,
        ): EcosParseResult = throw failure
    }

    /**
     * 저장과 조회를 같은 인스턴스가 처리한다 — 운영에서도 백필과 어댑터가 같은 리포지토리 빈을 쓰므로
     * 캐시 무효화 테스트가 "백필한 값을 어댑터가 다시 읽는" 실제 경로를 그대로 탄다.
     */
    private class FakeRepo(
        vararg existing: HistoricalFxRateEntity,
    ) : HistoricalFxRateJpaRepository by mock(HistoricalFxRateJpaRepository::class.java) {
        val rows = existing.toMutableList()
        val saved = mutableListOf<HistoricalFxRateEntity>()

        override fun findAllByCurrencyAndBaseDateBetween(
            currency: String,
            from: LocalDate,
            to: LocalDate,
        ): List<HistoricalFxRateEntity> = rows.filter {
            it.currency == currency && !it.baseDate.isBefore(from) && !it.baseDate.isAfter(to)
        }

        override fun findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            currency: String,
            baseDate: LocalDate,
        ): HistoricalFxRateEntity? = rows
            .filter { it.currency == currency && !it.baseDate.isAfter(baseDate) }
            .maxByOrNull { it.baseDate }

        override fun <S : HistoricalFxRateEntity> saveAll(entities: MutableIterable<S>): MutableList<S> {
            entities.forEach { entity ->
                saved.add(entity)
                val at = rows.indexOfFirst { it.id == entity.id }
                if (at >= 0) rows[at] = entity else rows.add(entity)
            }
            return entities.toMutableList()
        }
    }
}
