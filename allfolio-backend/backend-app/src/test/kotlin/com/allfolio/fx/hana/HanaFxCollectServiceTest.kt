package com.allfolio.fx.hana

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class HanaFxCollectServiceTest {

    private val requested = LocalDate.of(2026, 8, 9)     // 토요일
    private val friday = LocalDate.of(2026, 8, 7)

    @Test
    fun `응답이 말하는 기준일로 저장한다 — 조회일자가 아니다`() {
        // 토요일에 조회하면 하나은행은 금요일 고시를 돌려준다.
        // 조회일자로 저장하면 연휴 사흘 동안 같은 고시가 세 번 들어간다
        val repo = FakeRepo()
        val summary = service(repo, snapshot(friday, 32, "USD" to "1390")).collect(requested, force = false)

        assertThat(summary.baseDate).isEqualTo(friday)
        assertThat(repo.saved.single().baseDate).isEqualTo(friday)
    }

    @Test
    fun `수집 결과를 저장하고 요약을 반환한다`() {
        val repo = FakeRepo()

        val summary = service(repo, snapshot(friday, 32, "USD" to "1390", "JPY" to "9.5"))
            .collect(requested, force = false)

        assertThat(summary.roundNo).isEqualTo(32)
        assertThat(summary.currencies).isEqualTo(2)
        assertThat(summary.inserted).isEqualTo(2)
        assertThat(summary.updated).isZero()
        assertThat(summary.anomalies).isEmpty()
    }

    @Test
    fun `같은 회차를 다시 수집하면 새 행을 만들지 않고 값을 덮는다`() {
        val existing = entity(friday, 32, "USD", "1385")
        val repo = FakeRepo(existing)

        val summary = service(repo, snapshot(friday, 32, "USD" to "1390")).collect(requested, force = false)

        assertThat(summary.inserted).isZero()
        assertThat(summary.updated).isEqualTo(1)
        // currencies는 이번 고시의 통화 수지 새로 넣은 행 수가 아니다 —
        // 전부 갱신인 회차에서 둘이 갈라진다
        assertThat(summary.currencies).isEqualTo(1)
        assertThat(repo.saved.single().id).isEqualTo(existing.id)
        assertThat(repo.saved.single().baseRate).isEqualByComparingTo("1390")
    }

    @Test
    fun `값이 같으면 무변화로 센다`() {
        val repo = FakeRepo(entity(friday, 32, "USD", "1390.0000"))

        val summary = service(repo, snapshot(friday, 32, "USD" to "1390")).collect(requested, force = false)

        assertThat(summary.unchanged).isEqualTo(1)
        assertThat(summary.updated).isZero()
    }

    @Test
    fun `안전장치에 걸리면 아무것도 쓰지 않고 실패한다`() {
        val repo = FakeRepo()

        assertThatThrownBy {
            service(repo, snapshot(friday, 32, "JPY" to "9.5")).collect(requested, force = false)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("USD")

        assertThat(repo.saved).isEmpty()
    }

    @Test
    fun `force는 변동 가드를 뚫는다`() {
        val repo = FakeRepo(entity(friday, 31, "USD", "1385"))
        val snapshot = snapshot(friday, 32, "USD" to "1420")   // +2.53%

        assertThatThrownBy { service(repo, snapshot).collect(requested, force = false) }
            .isInstanceOf(IllegalStateException::class.java)

        val summary = service(repo, snapshot).collect(requested, force = true)
        assertThat(summary.inserted).isEqualTo(1)
    }

    @Test
    fun `파서가 버린 행 수를 요약에 싣는다`() {
        val repo = FakeRepo()
        val withSkips = HanaFxSnapshot(friday, 32, listOf(row("USD", "1390")), skipped = 3)

        val summary = service(repo, withSkips).collect(requested, force = false)

        assertThat(summary.skipped).isEqualTo(3)
    }

    @Test
    fun `클라이언트 예외는 그대로 올려보낸다`() {
        val failing = object : HanaFxClient {
            override fun fetch(date: LocalDate): String = throw HanaFxParseException("점검 중")
        }
        val service = HanaFxCollectService(failing, FixedParser(snapshot(friday, 32, "USD" to "1390")),
            HanaFxGuards(), FakeRepo())

        assertThatThrownBy { service.collect(requested, force = false) }
            .isInstanceOf(HanaFxParseException::class.java)
    }

    @Test
    fun `응답 기준일이 요청일보다 미래면 거부한다`() {
        // pbldDvCd가 틀렸거나 응답이 뒤바뀐 경우다. 미래 고시는 존재할 수 없다.
        // 주말에 직전 영업일이 오는 건 정상이므로 같은지는 따지지 않는다 — 미래인지만 본다
        val repo = FakeRepo()
        val future = requested.plusDays(3)

        assertThatThrownBy {
            service(repo, snapshot(future, 32, "USD" to "1390")).collect(requested, force = false)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("기준일")

        assertThat(repo.saved).isEmpty()
        // 검사가 조회 뒤로 밀리면 버릴 응답 때문에 Neon 쿼리가 두 번 나간다
        assertThat(repo.queries).isEmpty()
    }

    @Test
    fun `행 수 비교 기준은 직전 회차 통째다 — 이번 스냅샷 통화로만 모으지 않는다`() {
        // 스냅샷에 있는 통화로만 직전 값을 모으면 previousRowCount가 스냅샷 크기를 넘지 못해
        // 비율이 항상 1.0이 되고 급감 가드가 영원히 안 걸린다
        val repo = FakeRepo(
            entity(friday, 31, "USD", "1390"),
            entity(friday, 31, "JPY", "9.5"),
            entity(friday, 31, "EUR", "1500"),
            entity(friday, 31, "CNY", "190"),
        )

        assertThatThrownBy {
            service(repo, snapshot(friday, 32, "USD" to "1390")).collect(requested, force = false)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("행 수")

        assertThat(repo.saved).isEmpty()
    }

    @Test
    fun `연속 실패는 세 번째부터 ERROR로 올리고 성공하면 리셋한다`() {
        val repo = FakeRepo()
        val client = ToggleClient()
        val parser = ToggleParser(snapshot(friday, 32, "USD" to "1390"))
        val service = HanaFxCollectService(client, parser, HanaFxGuards(), repo)
        val logs = attachAppender()

        try {
            // 실패 경로 세 가지가 모두 같은 카운터를 올려야 한다
            client.failing = true
            runCatching { service.collect(requested, force = false) }          // 클라이언트 실패
            client.failing = false
            parser.snapshot = snapshot(friday, 32, "JPY" to "9.5")
            runCatching { service.collect(requested, force = false) }          // 안전장치 실패
            assertThat(levels(logs)).containsExactly(Level.WARN, Level.WARN)

            parser.snapshot = snapshot(requested.plusDays(3), 32, "USD" to "1390")
            runCatching { service.collect(requested, force = false) }          // 미래 기준일
            assertThat(levels(logs)).containsExactly(Level.WARN, Level.WARN, Level.ERROR)

            parser.snapshot = snapshot(friday, 32, "USD" to "1390")
            service.collect(requested, force = false)                          // 성공 → 리셋

            client.failing = true
            repeat(2) { runCatching { service.collect(requested, force = false) } }
            // 리셋되지 않았다면 4·5회째라 ERROR가 찍힌다
            assertThat(levels(logs).takeLast(2)).containsExactly(Level.WARN, Level.WARN)
        } finally {
            detachAppender(logs)
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun serviceLogger() =
        LoggerFactory.getLogger(HanaFxCollectService::class.java) as Logger

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

    private fun service(repo: HanaFxQuoteJpaRepository, snapshot: HanaFxSnapshot) =
        HanaFxCollectService(
            client = object : HanaFxClient {
                override fun fetch(date: LocalDate): String = "<html/>"
            },
            parser = FixedParser(snapshot),
            guards = HanaFxGuards(),
            repository = repo,
        )

    private fun snapshot(date: LocalDate, round: Int, vararg pairs: Pair<String, String>) =
        HanaFxSnapshot(date, round, pairs.map { (c, r) -> row(c, r) }, skipped = 0)

    private fun row(currency: String, rate: String) =
        HanaFxRow(currency, BigDecimal(rate), null, null, null, null)

    private fun entity(date: LocalDate, round: Int, currency: String, rate: String) =
        HanaFxQuoteEntity(
            id = UUID.randomUUID(), baseDate = date, roundNo = round, currency = currency,
            baseRate = BigDecimal(rate), cashBuy = null, cashSell = null,
            remitSend = null, remitReceive = null, collectedAt = LocalDateTime.now(),
        )

    private class FixedParser(private val snapshot: HanaFxSnapshot) : HanaFxParser() {
        override fun parse(html: String): HanaFxSnapshot = snapshot
    }

    /** 한 인스턴스에서 실패와 성공을 번갈아 내야 연속 실패 카운터를 볼 수 있다 */
    private class ToggleParser(var snapshot: HanaFxSnapshot) : HanaFxParser() {
        override fun parse(html: String): HanaFxSnapshot = snapshot
    }

    private class ToggleClient : HanaFxClient {
        var failing = false
        override fun fetch(date: LocalDate): String =
            if (failing) throw HanaFxParseException("점검 중") else "<html/>"
    }

    private class FakeRepo(
        private vararg val existing: HanaFxQuoteEntity,
    ) : HanaFxQuoteJpaRepository by mock(HanaFxQuoteJpaRepository::class.java) {
        val saved = mutableListOf<HanaFxQuoteEntity>()
        val queries = mutableListOf<String>()

        override fun findAllByBaseDateAndRoundNo(baseDate: LocalDate, roundNo: Int) =
            existing.filter { it.baseDate == baseDate && it.roundNo == roundNo }
                .also { queries += "findAllByBaseDateAndRoundNo" }

        // Pair는 Comparable이 아니므로 maxByOrNull에 Pair를 넘기면 컴파일되지 않는다
        override fun findTopByCurrencyOrderByBaseDateDescRoundNoDesc(currency: String) =
            existing.filter { it.currency == currency }
                .maxWithOrNull(compareBy({ it.baseDate }, { it.roundNo }))
                .also { queries += "findTopByCurrency" }

        override fun <S : HanaFxQuoteEntity> saveAll(entities: MutableIterable<S>): MutableList<S> {
            entities.forEach { saved.add(it) }
            return entities.toMutableList()
        }
    }
}
