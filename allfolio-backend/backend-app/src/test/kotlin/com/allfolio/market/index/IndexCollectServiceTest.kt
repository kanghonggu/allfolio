package com.allfolio.market.index

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.allfolio.broker.kis.KisApiClient
import com.allfolio.broker.kis.KisProperties
import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 국내 지수 수집 서비스 (AF-101).
 *
 * **Mockito 대신 손으로 만든 fake를 쓴다.** mockito-kotlin이 없어 `any()`가 null을 돌려주는데,
 * KisIndexClient·리포지토리의 파라미터는 전부 non-null이라 스터빙 시점에 터진다
 * (이 프로젝트에서 이미 두 번 물린 함정). 리포지토리만 인터페이스 위임용으로 mock을 쓰고,
 * 실제로 호출되는 메서드는 전부 직접 구현한다.
 */
class IndexCollectServiceTest {

    /** 2026-08-12 운영 실측 (KOSPI). 상승일이라 부호는 "2" */
    private val kospi = output("6579.04", "233.51", "3.68")
    private val kosdaq = output("900.00", "30.00", "3.45")
    private val kospi200 = output("880.00", "30.00", "3.53")

    private val responses = mapOf("0001" to kospi, "1001" to kosdaq, "2001" to kospi200)

    private val tradeDate = LocalDate.of(2026, 8, 12)

    // 넘기는 시각은 UTC(Render 컨테이너 기준)다. KST로 옮긴 값이 괄호 안
    private val utcPreOpen = LocalDateTime.of(2026, 8, 11, 23, 50)   // KST 08-12 08:50
    private val utcOpenSharp = LocalDateTime.of(2026, 8, 12, 0, 0)   // KST 08-12 09:00
    private val utcMidday = LocalDateTime.of(2026, 8, 12, 3, 30)     // KST 08-12 12:30
    private val utcCloseSharp = LocalDateTime.of(2026, 8, 12, 6, 30) // KST 08-12 15:30
    private val utcAfterClose = LocalDateTime.of(2026, 8, 12, 6, 40) // KST 08-12 15:40

    @Test
    fun `실측 응답으로 세 지수를 수집하면 3건이 저장된다`() {
        val repo = FakeRepo()

        val summary = service(repo).collect(IndexSlot.MID, utcMidday)

        assertThat(summary.requested).isEqualTo(3)
        assertThat(summary.collected).isEqualTo(3)
        assertThat(summary.inserted).isEqualTo(3)
        assertThat(summary.updated).isZero()
        assertThat(summary.failed).isZero()
        assertThat(summary.failures).isEmpty()
        assertThat(summary.slot).isEqualTo("MID")
        assertThat(repo.rows).hasSize(3)

        val row = repo.row("KOSPI")
        assertThat(row.price).isEqualByComparingTo("6579.04")
        assertThat(row.prevClose).isEqualByComparingTo("6345.53")
        assertThat(row.changeValue).isEqualByComparingTo("233.51")
        assertThat(row.changeRate).isEqualByComparingTo("3.68")
        assertThat(row.slot).isEqualTo("MID")
        assertThat(row.source).isEqualTo("KIS")
        assertThat(row.marketStatus).isEqualTo("장중")
        // KIS 지수 응답에 전일 기준일이 없다 — 잊은 게 아니라 실을 값이 없는 것이다
        assertThat(row.prevCloseDate).isNull()
        // 수집시각은 넘겨받은 now 그대로다. KST로 옮겨 담으면 다른 수집기와 기준이 갈린다
        assertThat(row.collectedAt).isEqualTo(utcMidday)
    }

    @Test
    fun `같은 슬롯을 다시 수집하면 새 행이 아니라 값이 갱신된다`() {
        // GitHub cron은 5~30분씩 밀리고 수동 재실행도 있다.
        // 두 번째 실행이 새 행을 만들면 유니크 제약에 걸리거나 같은 슬롯이 두 건 남는다
        val repo = FakeRepo()
        val client = FakeClient(responses)
        service(repo, client).collect(IndexSlot.MID, utcMidday)
        val idsAfterFirst = repo.rows.map { it.id }.toSet()

        client.responses = mapOf(
            "0001" to output("6600.00", "254.47", "4.01"),
            "1001" to kosdaq,
            "2001" to kospi200,
        )
        val summary = service(repo, client).collect(IndexSlot.MID, utcMidday)

        assertThat(summary.inserted).isZero()
        assertThat(summary.updated).isEqualTo(3)
        assertThat(summary.collected).isEqualTo(3)
        assertThat(repo.rows).hasSize(3)
        assertThat(repo.rows.map { it.id }).containsExactlyInAnyOrderElementsOf(idsAfterFirst)
        assertThat(repo.row("KOSPI").price).isEqualByComparingTo("6600.00")
    }

    @Test
    fun `tradeDate는 주입된 now의 KST 날짜다`() {
        // UTC 자정 직전(08-11 23:50)은 KST로 08-12 08:50이다.
        // UTC 컨테이너에서 LocalDate.now()를 쓰면 하루 전 날짜로 박힌다
        val repo = FakeRepo()

        val summary = service(repo).collect(IndexSlot.OPEN, utcPreOpen)

        assertThat(summary.tradeDate).isEqualTo(tradeDate)
        assertThat(summary.tradeDate).isNotEqualTo(utcPreOpen.toLocalDate())
        assertThat(repo.rows.map { it.tradeDate }).containsOnly(tradeDate)
    }

    @Test
    fun `09시 이전이면 개장전, 장중이면 장중, 15시30분 이후면 장마감`() {
        // 경계는 포함이다 — 09:00과 15:30은 장중이다.
        // KIS 응답에 시장 상태가 없어 시계로 판정하므로, 경계를 잘못 잡으면 아무도 못 잡아준다
        assertThat(statusAt(utcPreOpen)).isEqualTo("개장전")
        assertThat(statusAt(utcOpenSharp)).isEqualTo("장중")
        assertThat(statusAt(utcMidday)).isEqualTo("장중")
        assertThat(statusAt(utcCloseSharp)).isEqualTo("장중")
        assertThat(statusAt(utcAfterClose)).isEqualTo("장마감")
    }

    @Test
    fun `직전 저장 행과 값이 전부 같으면 장중 시각이어도 장마감으로 낮춘다`() {
        // 공휴일 달력을 두지 않는 대신 쓰는 판정이다. 휴장일엔 지수가 움직이지 않으므로
        // 직전 행과 값이 완전히 같다 — 문 닫은 날을 "장중"이라고 우기는 것만은 막는다.
        val repo = FakeRepo(
            // KOSPI: 전 영업일 종가가 이번 응답과 완전히 동일 → 낮춘다
            entity("KOSPI", tradeDate.minusDays(1), "CLOSE", "6579.04", "6345.53", "233.51", "3.68"),
            // KOSDAQ: 등락률 하나만 다르다 → 낮추지 않는다.
            // 현재가만 비교하는 구현이면 여기서 갈린다
            entity("KOSDAQ", tradeDate.minusDays(1), "CLOSE", "900.00", "870.00", "30.00", "3.99"),
        )

        service(repo).collect(IndexSlot.MID, utcMidday)

        assertThat(repo.row("KOSPI").marketStatus).isEqualTo("장마감")
        assertThat(repo.row("KOSDAQ").marketStatus).isEqualTo("장중")
        assertThat(repo.row("KOSPI200").marketStatus).isEqualTo("장중")
    }

    @Test
    fun `한 지수가 실패해도 나머지는 저장된다`() {
        // 하나가 터졌다고 나머지를 버리면, 살아 있던 두 건까지 같이 잃는다
        val repo = FakeRepo()
        val client = FakeClient(responses).apply { failing += "1001" }

        val summary = service(repo, client).collect(IndexSlot.MID, utcMidday)

        assertThat(summary.requested).isEqualTo(3)
        assertThat(summary.collected).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(2)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures).hasSize(1)
        assertThat(summary.failures.single()).startsWith("KOSDAQ: ").contains("점검 중")
        assertThat(repo.rows.map { it.indexCode }).containsExactlyInAnyOrder("KOSPI", "KOSPI200")
    }

    @Test
    fun `안전장치에 걸린 지수는 저장하지 않고 failed로 센다`() {
        // 등락률이 값과 어긋나는 응답 — 파싱은 멀쩡히 성공하고 틀린 숫자만 남는 경우다
        val repo = FakeRepo()
        val client = FakeClient(responses + ("2001" to output("880.00", "30.00", "9.99")))

        val summary = service(repo, client).collect(IndexSlot.MID, utcMidday)

        assertThat(summary.collected).isEqualTo(2)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).startsWith("KOSPI200: ").contains("등락률")
        assertThat(repo.rows.map { it.indexCode }).doesNotContain("KOSPI200")
        assertThat(repo.rows).hasSize(2)
    }

    @Test
    fun `연속 전체 실패는 세 번째부터 ERROR로 올리고 성공하면 리셋한다`() {
        val repo = FakeRepo()
        val client = FakeClient(responses)
        val service = service(repo, client)
        val logs = attachAppender()

        try {
            client.failing += responses.keys           // 전건 실패
            repeat(2) { service.collect(IndexSlot.MID, utcMidday) }
            assertThat(levels(logs)).containsExactly(Level.WARN, Level.WARN)

            service.collect(IndexSlot.MID, utcMidday)
            assertThat(levels(logs)).containsExactly(Level.WARN, Level.WARN, Level.ERROR)

            client.failing.clear()
            service.collect(IndexSlot.MID, utcMidday)  // 성공 → 리셋

            client.failing += responses.keys
            repeat(2) { service.collect(IndexSlot.MID, utcMidday) }
            // 리셋되지 않았다면 4·5회째라 ERROR가 찍힌다
            assertThat(levels(logs).takeLast(2)).containsExactly(Level.WARN, Level.WARN)
        } finally {
            detachAppender(logs)
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun statusAt(now: LocalDateTime): String {
        val repo = FakeRepo()
        service(repo).collect(IndexSlot.MID, now)
        return repo.row("KOSPI").marketStatus
    }

    private fun service(repo: FakeRepo, client: FakeClient = FakeClient(responses)) =
        IndexCollectService(
            client = client,
            parser = KisIndexParser(),
            guards = IndexGuards(),
            repository = repo,
            properties = properties(),
        )

    private fun properties() = MarketIndexProperties().apply {
        domestic = listOf(
            domestic("KOSPI", "0001"),
            domestic("KOSDAQ", "1001"),
            domestic("KOSPI200", "2001"),
        )
    }

    private fun domestic(code: String, iscd: String) = MarketIndexProperties.DomesticIndex().apply {
        this.code = code
        this.kisIscd = iscd
    }

    /** KIS 응답은 값이 전부 문자열이다 */
    private fun output(price: String, change: String, rate: String, sign: String = "2") = mapOf<String, Any?>(
        "bstp_nmix_prpr" to price,
        "bstp_nmix_prdy_vrss" to change,
        "prdy_vrss_sign" to sign,
        "bstp_nmix_prdy_ctrt" to rate,
    )

    private fun entity(
        code: String,
        date: LocalDate,
        slot: String,
        price: String,
        prevClose: String,
        change: String,
        rate: String,
    ) = MarketIndexQuoteEntity(
        id = UUID.randomUUID(),
        indexCode = code,
        tradeDate = date,
        slot = slot,
        price = BigDecimal(price),
        prevClose = BigDecimal(prevClose),
        changeValue = BigDecimal(change),
        changeRate = BigDecimal(rate),
        prevCloseDate = null,
        marketStatus = "장마감",
        source = "KIS",
        collectedAt = LocalDateTime.of(2026, 8, 11, 6, 40),
    )

    private fun serviceLogger() =
        LoggerFactory.getLogger(IndexCollectService::class.java) as Logger

    private fun attachAppender(): ListAppender<ILoggingEvent> =
        ListAppender<ILoggingEvent>().apply {
            start()
            serviceLogger().addAppender(this)
        }

    private fun detachAppender(appender: ListAppender<ILoggingEvent>) {
        serviceLogger().detachAppender(appender)
        appender.stop()
    }

    /** 성공 로그(INFO)는 카운터와 무관하므로 경고 이상만 본다 */
    private fun levels(appender: ListAppender<ILoggingEvent>) =
        appender.list.map { it.level }.filter { it.isGreaterOrEqual(Level.WARN) }

    /**
     * KisIndexClient는 @Component라 kotlin-spring 플러그인이 열어 준다 — 상속으로 가짜를 만든다.
     * 생성자가 요구하는 KisApiClient는 이 경로에서 한 번도 호출되지 않아 mock으로 자리만 채운다.
     */
    private class FakeClient(
        var responses: Map<String, Map<String, Any?>>,
    ) : KisIndexClient(KisProperties(), mock(KisApiClient::class.java)) {

        val failing = mutableSetOf<String>()

        override fun fetchRaw(kisIscd: String): Map<String, Any?> {
            if (kisIscd in failing) throw KisIndexException("KIS 지수 조회 실패 iscd=$kisIscd: 점검 중")
            return responses[kisIscd] ?: throw KisIndexException("응답이 없습니다 iscd=$kisIscd")
        }
    }

    private class FakeRepo(
        vararg seed: MarketIndexQuoteEntity,
    ) : MarketIndexQuoteJpaRepository by mock(MarketIndexQuoteJpaRepository::class.java) {

        val rows = seed.toMutableList()

        /** 이번 실행이 쓴 행. 직전 영업일을 미리 심어 둔 테스트가 있어 슬롯까지 좁힌다 */
        fun row(indexCode: String, slot: String = "MID") =
            rows.single { it.indexCode == indexCode && it.slot == slot }

        // 사전순은 CLOSE < MID < OPEN이라 문자열로 정렬하면 OPEN이 종가보다 최신이 된다
        override fun findLatest(indexCode: String): MarketIndexQuoteEntity? =
            rows.filter { it.indexCode == indexCode }
                .maxWithOrNull(compareBy({ it.tradeDate }, { slotRank(it.slot) }))

        override fun findByIndexCodeAndTradeDateAndSlot(
            indexCode: String,
            tradeDate: LocalDate,
            slot: String,
        ): MarketIndexQuoteEntity? =
            rows.firstOrNull { it.indexCode == indexCode && it.tradeDate == tradeDate && it.slot == slot }

        // JPA와 같게: 이미 관리 중인 인스턴스를 다시 저장해도 행이 늘지 않는다
        override fun <S : MarketIndexQuoteEntity> save(entity: S): S {
            if (rows.none { it === entity }) rows += entity
            return entity
        }

        private fun slotRank(slot: String) = when (slot) {
            "CLOSE" -> 3
            "MID" -> 2
            else -> 1
        }
    }
}
