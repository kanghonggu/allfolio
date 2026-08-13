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
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * 해외 지수 수집 서비스 (AF-110).
 *
 * **Mockito 대신 손으로 만든 fake를 쓴다** — 국내 [IndexCollectServiceTest]와 같은 이유다.
 * mockito-kotlin이 없어 `any()`가 null을 돌려주는데 클라이언트·리포지토리의 파라미터가 전부
 * non-null이라 스터빙 시점에 터진다.
 *
 * 이 파일에서 **가장 중요한 테스트는 `KIS가 준 이름이 설정과 안 맞으면 저장하지 않는다`**이다.
 * 9종 중 실측으로 확인된 것은 셋뿐이고 나머지 여섯은 마스터 파일에서 읽은 코드라, 코드를 잘못
 * 고르면 `IndexGuards`는 통과한다(엉뚱한 지수의 응답도 그 지수 기준으로는 일관되기 때문).
 * 그때 유일하게 막아 주는 것이 이름 대조다.
 */
class OverseasIndexCollectServiceTest {

    /**
     * 2026-08-13 운영 실측 (HK#HS, 하락일). 검산: 25365.14 − (−75.03) = 25440.17 = 응답의
     * `ovrs_nmix_prdy_clpr`, −75.03/25440.17×100 = −0.2949 ≈ −0.29.
     *
     * 니케이·S&P 픽스처는 **이 실측 숫자를 그대로 재사용하고 이름만 바꾼다.** 그 테스트들이 보는 것은
     * 값이 아니라 스케줄 필터·이름 대조이고, 없는 실측을 지어내면 그 숫자가 나중에 근거처럼 읽힌다.
     */
    private fun response(
        name: String = "항셍지수",
        prpr: String = "25365.14",
        vrss: String = "-75.03",
        sign: String = "5",
        ctrt: String = "-0.29",
        clpr: String = "25440.17",
        bars: List<Pair<String, String>> = listOf("20260813" to "25365.14", "20260812" to "25440.17"),
    ) = mapOf<String, Any?>(
        "output1" to mapOf(
            "ovrs_nmix_prpr" to prpr,
            "ovrs_nmix_prdy_vrss" to vrss,
            "prdy_vrss_sign" to sign,
            "prdy_ctrt" to ctrt,
            "ovrs_nmix_prdy_clpr" to clpr,
            "hts_kor_isnm" to name,
        ),
        "output2" to bars.map { (d, p) -> mapOf("stck_bsop_date" to d, "ovrs_nmix_prpr" to p) },
        "rt_cd" to "0",
    )

    private val responses = mapOf(
        "HK#HS" to response(),
        "JP#NI225" to response(name = "니케이225"),
        "SPX" to response(name = "S&P500"),
    )

    private val tradeDate = LocalDate.of(2026, 8, 13)
    private val prevTradeDate = LocalDate.of(2026, 8, 12)

    private val kst = ZoneId.of("Asia/Seoul")
    private val hongKong = ZoneId.of("Asia/Hong_Kong")

    /** 아시아 슬롯의 실제 실행 시각(08:30 UTC = 홍콩 16:30, 마감 직후) */
    private val asiaRun: Instant = Instant.parse("2026-08-13T08:30:00Z")

    @Test
    fun `실측 응답을 저장한다`() {
        val repo = FakeRepo()

        val summary = service(repo).collect("ASIA", asiaRun)

        assertThat(summary.schedule).isEqualTo("ASIA")
        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.collected).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(2)
        assertThat(summary.updated).isZero()
        assertThat(summary.failed).isZero()
        assertThat(summary.failures).isEmpty()

        val row = repo.row("HANGSENG")
        // 거래일은 **응답의 봉**에서 온다. 시계로 유추하면 한국 시각 기준으로 하루가 밀린다
        assertThat(row.tradeDate).isEqualTo(tradeDate)
        // 해외는 일봉이라 하루 한 건이다 — 슬롯은 CLOSE 하나로 고정한다
        assertThat(row.slot).isEqualTo("CLOSE")
        assertThat(row.source).isEqualTo("KIS_OVERSEAS")
        assertThat(row.price).isEqualByComparingTo("25365.14")
        assertThat(row.prevClose).isEqualByComparingTo("25440.17")
        assertThat(row.changeValue).isEqualByComparingTo("-75.03")
        assertThat(row.changeRate).isEqualByComparingTo("-0.29")
        // 수집시각은 넘겨받은 Instant를 UTC로 편 값이다 — 국내가 UTC LocalDateTime을 저장하는 것과 맞춘다
        assertThat(row.collectedAt).isEqualTo(LocalDateTime.of(2026, 8, 13, 8, 30))
    }

    @Test
    fun `전일 종가 날짜를 채운다`() {
        // 국내 경로에서는 이 칸이 **항상 null**이었다(응답에 전일 기준일이 없다).
        // 해외는 output2[1]이 날짜를 들고 오므로 처음으로 채워진다. 거래일에서 하루를 빼면
        // 주말·현지 공휴일이 그대로 구멍이 된다 — 한국 월요일에 보는 S&P의 "전일"은 금요일이다.
        val repo = FakeRepo()

        service(repo).collect("ASIA", asiaRun)

        assertThat(repo.row("HANGSENG").prevCloseDate).isEqualTo(prevTradeDate)
    }

    @Test
    fun `최신 봉이 시장 현지 오늘이면 장중이다`() {
        // 15:30 UTC는 홍콩 08-13 23:30(= 봉의 날짜와 같은 날)이지만 **KST로는 이미 08-14**다.
        // 즉 시장 현지 타임존 대신 KST를 쓰면 이 테스트가 장마감으로 뒤집힌다.
        val now = Instant.parse("2026-08-13T15:30:00Z")
        assertThat(LocalDate.ofInstant(now, hongKong)).isEqualTo(tradeDate)
        assertThat(LocalDate.ofInstant(now, kst)).isNotEqualTo(tradeDate)

        val repo = FakeRepo()
        service(repo).collect("ASIA", now)

        assertThat(repo.row("HANGSENG").marketStatus).isEqualTo("장중")
    }

    @Test
    fun `최신 봉이 어제면 장마감이다`() {
        // 16:30 UTC는 홍콩 08-14 00:30이라 08-13 봉은 이미 확정된 어제 것이다.
        // 반면 **UTC로는 아직 08-13**이라, 시장 현지 타임존 대신 UTC를 쓰면 장중으로 뒤집힌다.
        // 위 테스트(KST가 걸리는 쪽)와 짝을 이뤄 zoneId가 실제로 일하는지를 양쪽에서 못 박는다.
        val now = Instant.parse("2026-08-13T16:30:00Z")
        assertThat(LocalDate.ofInstant(now, hongKong)).isNotEqualTo(tradeDate)
        assertThat(LocalDate.ofInstant(now, ZoneOffset.UTC)).isEqualTo(tradeDate)

        val repo = FakeRepo()
        service(repo).collect("ASIA", now)

        assertThat(repo.row("HANGSENG").marketStatus).isEqualTo("장마감")
    }

    @Test
    fun `KIS가 준 이름이 설정과 안 맞으면 저장하지 않는다`() {
        // **이 파일에서 가장 중요한 테스트다.** 마스터에는 항셍 옆에 HSCE(홍콩H)·HK#HSSI(소형주)가
        // 붙어 있어 코드를 한 글자 잘못 넣으면 **엉뚱한 지수의 응답이 내부적으로는 일관되게** 온다.
        // IndexGuards는 값끼리의 정합성만 보므로 그대로 통과시키고, 화면엔 "항셍"이라 쓰인 채
        // 홍콩H지수가 뜬다. 요약의 failed 숫자만 보면 "실패로 세면서 저장은 했다"를 놓치므로
        // **save 호출 자체가 없었는지**를 본다.
        val repo = FakeRepo()
        val client = FakeClient(responses + ("HK#HS" to response(name = "홍콩H지수")))

        val summary = service(repo, client).collect("ASIA", asiaRun)

        assertThat(repo.saves.map { it.indexCode }).containsExactly("NIKKEI225")
        assertThat(repo.rows.map { it.indexCode }).doesNotContain("HANGSENG")
        assertThat(summary.collected).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        // 어느 쪽이 틀렸는지 운영자가 보려면 양쪽 문자열이 다 있어야 한다
        assertThat(summary.failures.single())
            .startsWith("HANGSENG: ")
            .contains("홍콩H지수")
            .contains("항셍")
    }

    @Test
    fun `가드에 걸린 지수는 저장하지 않는다`() {
        // 실측 응답에서 `ovrs_nmix_prdy_clpr`만 한 자리 틀어 놓은 것이다. 역산값(25365.14 −
        // (−75.03) = 25440.17)과 어긋나므로 IndexGuards의 전일종가 교차검증에 걸린다.
        // **이 테스트가 `guards.check`의 두 번째 인자를 지킨다** — 인자를 빼면 그 검증이
        // 통째로 꺼지는데, 등락률 검사는 여전히 맞아서 아무 데서도 티가 나지 않는다.
        val repo = FakeRepo()
        val client = FakeClient(responses + ("HK#HS" to response(clpr = "25440.99")))

        val summary = service(repo, client).collect("ASIA", asiaRun)

        assertThat(repo.saves.map { it.indexCode }).containsExactly("NIKKEI225")
        assertThat(repo.rows.map { it.indexCode }).doesNotContain("HANGSENG")
        assertThat(summary.collected).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).startsWith("HANGSENG: ").contains("전일종가")
    }

    @Test
    fun `한 지수가 실패해도 나머지는 저장된다`() {
        // 하나가 터졌다고 나머지를 버리면, 살아 있던 건까지 같이 잃는다.
        // 지수가 3종이 아니라 9종이라 국내보다 더 크게 잃는다
        val repo = FakeRepo()
        val client = FakeClient(responses).apply { failing += "HK#HS" }

        val summary = service(repo, client).collect("ASIA", asiaRun)

        assertThat(summary.requested).isEqualTo(2)
        assertThat(summary.collected).isEqualTo(1)
        assertThat(summary.failed).isEqualTo(1)
        assertThat(summary.failures.single()).startsWith("HANGSENG: ").contains("점검 중")
        assertThat(repo.rows.map { it.indexCode }).containsExactly("NIKKEI225")
    }

    @Test
    fun `같은 거래일을 다시 수집하면 새 행이 아니라 값이 갱신된다`() {
        // GitHub cron은 5~30분씩 밀리고 수동 재실행도 있다. 두 번째 실행이 새 행을 만들면
        // 유니크 제약에 걸리거나 같은 거래일이 두 건 남는다
        val repo = FakeRepo()
        val client = FakeClient(responses)
        service(repo, client).collect("ASIA", asiaRun)
        val idsAfterFirst = repo.rows.map { it.id }.toSet()

        // 같은 08-13 봉을 조금 뒤에 다시 읽은 모습. 네 값이 서로 맞게 맞춰 뒀다:
        // 25400.14 + 40.03 = 25440.17, −40.03/25440.17×100 = −0.157 ≈ −0.16
        client.responses = responses + (
            "HK#HS" to response(
                prpr = "25400.14",
                vrss = "-40.03",
                ctrt = "-0.16",
                bars = listOf("20260813" to "25400.14", "20260812" to "25440.17"),
            )
            )
        val summary = service(repo, client).collect("ASIA", asiaRun)

        assertThat(summary.inserted).isZero()
        assertThat(summary.updated).isEqualTo(2)
        assertThat(repo.rows).hasSize(2)
        assertThat(repo.rows.map { it.id }).containsExactlyInAnyOrderElementsOf(idsAfterFirst)
        assertThat(repo.row("HANGSENG").price).isEqualByComparingTo("25400.14")
        assertThat(repo.row("HANGSENG").changeRate).isEqualByComparingTo("-0.16")
    }

    @Test
    fun `schedule이 US면 ASIA 지수는 부르지 않는다`() {
        // 슬롯을 안 거르면 아시아 슬롯(08:30 UTC)이 미국 지수를 부른다 — 그 시각 미국은 장중이라
        // 진행 중인 봉을 종가처럼 저장하게 된다. 호출 자체가 없어야 하므로 클라이언트 인자를 본다
        val usRepo = FakeRepo()
        val usClient = FakeClient(responses)
        service(usRepo, usClient).collect("US", asiaRun)

        assertThat(usClient.calls.map { it.iscd }).containsExactly("SPX")
        assertThat(usRepo.rows.map { it.indexCode }).containsExactly("SPX")

        val asiaRepo = FakeRepo()
        val asiaClient = FakeClient(responses)
        service(asiaRepo, asiaClient).collect("ASIA", asiaRun)

        assertThat(asiaClient.calls.map { it.iscd }).containsExactlyInAnyOrder("HK#HS", "JP#NI225")

        // 조회 구간은 시장 현지 오늘로부터 최근 7일이다. 좁히면 연휴 뒤에 output2[1]이 없어
        // prev_close_date가 빈다
        val hk = asiaClient.calls.single { it.iscd == "HK#HS" }
        assertThat(hk.to).isEqualTo(tradeDate)
        assertThat(hk.from).isEqualTo(tradeDate.minusDays(7))
    }

    @Test
    fun `요약에 KIS가 준 이름이 실린다`() {
        // 9종 중 실측으로 확인된 것은 SPX·.DJI·HK#HS 셋뿐이고 나머지 여섯은 마스터 파일에서
        // 읽었을 뿐이다. 요약에 이름을 실으면 **예약 실행 한 번의 JSON이 9종을 한꺼번에 답해 준다** —
        // 지수마다 raw 덤프를 찔러 볼 필요가 없다
        val repo = FakeRepo()

        val summary = service(repo).collect("ASIA", asiaRun)

        assertThat(summary.names)
            .containsEntry("HANGSENG", "항셍지수")
            .containsEntry("NIKKEI225", "니케이225")
    }

    @Test
    fun `이름이 어긋나 저장하지 않은 지수도 요약의 names에는 남는다`() {
        // names의 목적은 "무엇이 저장됐나"가 아니라 **"KIS가 이 코드로 무엇을 돌려주나"**다.
        // 저장된 것만 실으면, 정작 코드를 잘못 골라 대조가 깨진 지수의 이름이 요약에서 사라져
        // 확인하러 온 사람이 다시 raw 덤프를 찔러야 한다
        val repo = FakeRepo()
        val client = FakeClient(responses + ("HK#HS" to response(name = "홍콩H지수")))

        val summary = service(repo, client).collect("ASIA", asiaRun)

        assertThat(summary.names).containsEntry("HANGSENG", "홍콩H지수")
        assertThat(repo.rows.map { it.indexCode }).doesNotContain("HANGSENG")
    }

    @Test
    fun `삽입이 유니크 충돌을 내면 다시 읽어 갱신으로 끝낸다`() {
        // 국내에서 그대로 가져온 동작이라 **국내를 지키던 테스트도 같이 가져온다.**
        // 콜드 스타트에서 첫 요청의 --max-time이 만료돼도 서버는 collect()를 계속 돌고 있고,
        // curl은 20초 뒤 두 번째 요청을 보내 같은 인스턴스에서 같은 루프가 겹쳐 돈다.
        // 그걸 실패로 세면 전 지수가 부딪힌 순간 collected == 0 → 잡이 빨개진다 —
        // 첫 요청이 행을 멀쩡히 저장했는데도
        val repo = FakeRepo().apply { collideOnInsert += listOf("HANGSENG", "NIKKEI225") }

        val summary = service(repo).collect("ASIA", asiaRun)

        assertThat(summary.collected).isEqualTo(2)
        assertThat(summary.inserted).isZero()
        assertThat(summary.updated).isEqualTo(2)
        assertThat(summary.failed).isZero()
        assertThat(repo.rows).hasSize(2)
        // 먼저 도착한 요청이 넣어 둔 행 위에 이번 값이 얹힌다
        assertThat(repo.row("HANGSENG").price).isEqualByComparingTo("25365.14")
        assertThat(repo.row("HANGSENG").marketStatus).isEqualTo("장중")
    }

    @Test
    fun `연속 전체 실패는 세 번째부터 ERROR로 올리고 성공하면 리셋한다`() {
        // 이것도 국내에서 가져온 동작이라 그 회귀 테스트를 같이 가져온다
        val repo = FakeRepo()
        val client = FakeClient(responses)
        val service = service(repo, client)
        val logs = attachAppender()

        try {
            client.failing += setOf("HK#HS", "JP#NI225")   // 아시아 슬롯 전건 실패
            repeat(2) { service.collect("ASIA", asiaRun) }
            assertThat(levels(logs)).containsExactly(Level.WARN, Level.WARN)

            service.collect("ASIA", asiaRun)
            assertThat(levels(logs)).containsExactly(Level.WARN, Level.WARN, Level.ERROR)

            client.failing.clear()
            service.collect("ASIA", asiaRun)               // 성공 → 리셋

            client.failing += setOf("HK#HS", "JP#NI225")
            repeat(2) { service.collect("ASIA", asiaRun) }
            // 리셋되지 않았다면 4·5회째라 ERROR가 찍힌다
            assertThat(levels(logs).takeLast(2)).containsExactly(Level.WARN, Level.WARN)
        } finally {
            detachAppender(logs)
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun service(repo: FakeRepo, client: FakeClient = FakeClient(responses)) =
        OverseasIndexCollectService(
            client = client,
            parser = KisOverseasIndexParser(),
            guards = IndexGuards(),
            repository = repo,
            properties = properties(),
        )

    private fun properties() = MarketIndexProperties().apply {
        overseas = listOf(
            overseas("HANGSENG", "HK#HS", "Asia/Hong_Kong", "ASIA", "항셍"),
            overseas("NIKKEI225", "JP#NI225", "Asia/Tokyo", "ASIA", "니케이"),
            overseas("SPX", "SPX", "America/New_York", "US", "S&P500"),
        )
    }

    private fun overseas(
        code: String,
        iscd: String,
        zoneId: String,
        schedule: String,
        nameContains: String,
    ) = MarketIndexProperties.OverseasIndex().apply {
        this.code = code
        this.kisIscd = iscd
        this.zoneId = zoneId
        this.schedule = schedule
        this.nameContains = nameContains
    }

    private fun serviceLogger() =
        LoggerFactory.getLogger(OverseasIndexCollectService::class.java) as Logger

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

    private data class Call(val iscd: String, val from: LocalDate, val to: LocalDate)

    /**
     * KisIndexClient는 @Component라 kotlin-spring 플러그인이 열어 준다 — 상속으로 가짜를 만든다.
     * 생성자가 요구하는 KisApiClient는 이 경로에서 한 번도 호출되지 않아 mock으로 자리만 채운다.
     */
    private class FakeClient(
        var responses: Map<String, Map<String, Any?>>,
    ) : KisIndexClient(KisProperties(), mock(KisApiClient::class.java)) {

        val failing = mutableSetOf<String>()

        /** 조회 인자를 그대로 남긴다 — 스케줄 필터와 조회 구간은 "부르지 않았음"으로만 확인된다 */
        val calls = mutableListOf<Call>()

        override fun fetchOverseasRaw(iscd: String, from: LocalDate, to: LocalDate): Map<String, Any?> {
            calls += Call(iscd, from, to)
            if (iscd in failing) throw KisIndexException("KIS 해외 지수 조회 실패 iscd=$iscd: 점검 중")
            return responses[iscd] ?: throw KisIndexException("응답이 없습니다 iscd=$iscd")
        }
    }

    private class FakeRepo(
        vararg seed: MarketIndexQuoteEntity,
    ) : MarketIndexQuoteJpaRepository by mock(MarketIndexQuoteJpaRepository::class.java) {

        val rows = seed.toMutableList()

        /**
         * save 호출 기록. **rows만 보면 "실패로 세면서 저장은 했다"를 놓친다** —
         * 이름 대조·가드가 실제로 저장을 막는지 확인하려면 호출 자체가 없어야 한다
         */
        val saves = mutableListOf<MarketIndexQuoteEntity>()

        /** 여기 담긴 지수는 첫 삽입에서 유니크 충돌을 낸다(겹쳐 도는 요청의 재현) */
        val collideOnInsert = mutableSetOf<String>()

        fun row(indexCode: String) = rows.single { it.indexCode == indexCode }

        override fun findByIndexCodeAndTradeDateAndSlot(
            indexCode: String,
            tradeDate: LocalDate,
            slot: String,
        ): MarketIndexQuoteEntity? =
            rows.firstOrNull { it.indexCode == indexCode && it.tradeDate == tradeDate && it.slot == slot }

        // JPA와 같게: 이미 관리 중인 인스턴스를 다시 저장해도 행이 늘지 않는다
        override fun <S : MarketIndexQuoteEntity> save(entity: S): S {
            saves += entity
            if (rows.none { it === entity }) {
                if (collideOnInsert.remove(entity.indexCode)) {
                    // 먼저 도착한 요청이 넣어 둔 행. 값은 일부러 다르게 둬서 갱신이 실제로 얹히는지 본다
                    rows += MarketIndexQuoteEntity(
                        id = UUID.randomUUID(),
                        indexCode = entity.indexCode,
                        tradeDate = entity.tradeDate,
                        slot = entity.slot,
                        price = BigDecimal("1"),
                        prevClose = BigDecimal("1"),
                        changeValue = BigDecimal.ZERO,
                        changeRate = BigDecimal.ZERO,
                        prevCloseDate = null,
                        marketStatus = "장마감",
                        source = "KIS_OVERSEAS",
                        collectedAt = LocalDateTime.of(2026, 8, 13, 8, 29),
                    )
                    throw DataIntegrityViolationException("uk_market_index_quote 위반")
                }
                rows += entity
            }
            return entity
        }
    }
}
