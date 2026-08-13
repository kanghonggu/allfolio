package com.allfolio.market.index

import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 국내 지수 수집 한 번의 결과.
 *
 * 지수 하나가 터져도 나머지는 저장하므로 "3건 요청, 2건 수집, 1건 실패"가 될 수 있다.
 * 예외로 끝내면 살아 있던 두 건까지 같이 잃는다.
 *
 * @param requested 설정에 있는 국내 지수 수
 * @param collected 저장까지 끝난 수 (inserted + updated)
 * @param failures  "KOSPI: <사유>" 형태. 운영자가 어느 지수가 왜 빠졌는지 한 번에 봐야 한다
 */
data class DomesticIndexCollectSummary(
    val tradeDate: LocalDate,
    val slot: String,
    val requested: Int,
    val collected: Int,
    val inserted: Int,
    val updated: Int,
    val failed: Int,
    val failures: List<String>,
)

/**
 * 국내 지수 수집 (AF-101).
 *
 * KIS 지수 응답에는 **기준시각도 시장상태도 없다**(2026-08-12 실측으로 확인). 그래서 세 가지를
 * 우리가 판정한다.
 *
 * 1. **거래일은 KST 날짜다.** `LocalDate.now()`를 쓰면 안 된다 — Render 컨테이너는 UTC로 돌아서
 *    KST 새벽·아침 실행이 하루 전 날짜로 박힌다. 시각은 파라미터로 받아 테스트가 못 박을 수 있게 한다.
 * 2. **시장상태는 시계로 판정한다.** 09:00 이전 개장전 / 09:00~15:30 장중 / 15:30 이후 장마감.
 *    경계는 포함이다.
 * 3. **휴장일을 장중이라고 우기지 않는다.** 공휴일 달력은 일부러 두지 않았다 —
 *    KR·US·아시아 휴일을 해마다 관리하는 부담이 이 기능이 감당할 무게를 넘는다. 대신 값이
 *    직전 저장 행과 **전부** 같으면 장마감으로 낮춘다. 휴장일엔 지수가 움직이지 않으므로 이걸로 걸린다.
 *    완벽하진 않지만 최악(문 닫은 날을 장중이라고 저장)은 막는다.
 *
 * `@Transactional`을 붙이지 않는다 — 지수마다 HTTP 호출이 하나씩 있어서 트랜잭션 안에 넣으면
 * 루프가 끝날 때까지 Neon 커넥션을 쥐고 앉아 있게 된다. AF-99 하나은행 수집기와 같은 이유다.
 * 별도 빈으로 쪼개면 AF-90에서 물린 자기호출 프록시 함정이 되살아난다.
 *
 * `prevCloseDate`는 KIS 경로에서 항상 null이다 — **빠뜨린 게 아니라 응답에 전일 기준일이 없다.**
 * 다른 소스가 생기면 그때 채운다.
 */
@Service
class IndexCollectService(
    private val client: KisIndexClient,
    private val parser: KisIndexParser,
    private val guards: IndexGuards,
    private val repository: MarketIndexQuoteJpaRepository,
    private val properties: MarketIndexProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 연속 전체 실패 횟수. 프로세스 메모리에만 둔다 —
     * 하는 일이 "로그 레벨을 올린다"뿐이라 테이블을 늘릴 값어치가 없고,
     * 재시작으로 0이 되는 것도 손해가 아니다(재시작 자체가 이미 조사할 사건이다).
     */
    private val consecutiveFailures = AtomicInteger(0)

    companion object {
        private const val SOURCE = "KIS"
        private const val FAILURE_ALERT_THRESHOLD = 3
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val MARKET_OPEN: LocalTime = LocalTime.of(9, 0)
        private val MARKET_CLOSE: LocalTime = LocalTime.of(15, 30)
    }

    /**
     * @param now 호출자 기준(운영에선 UTC) 현재 시각. 거래일·시장상태를 여기서만 뽑는다.
     *            서비스 안에서 시계를 읽지 않으므로 테스트가 시각을 못 박을 수 있다.
     */
    fun collect(slot: IndexSlot, now: LocalDateTime): DomesticIndexCollectSummary {
        val kstNow = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDateTime()
        val tradeDate = kstNow.toLocalDate()
        val clockStatus = marketStatus(kstNow.toLocalTime())

        var inserted = 0
        var updated = 0
        val failures = mutableListOf<String>()

        for (index in properties.domestic) {
            try {
                val quote = parser.parse(index.code, client.fetchRaw(index.kisIscd))

                val anomalies = guards.check(quote)
                if (anomalies.isNotEmpty()) {
                    throw IllegalStateException(
                        "안전장치에 걸려 저장하지 않았습니다: ${anomalies.joinToString("; ")}",
                    )
                }

                // 덮기 **전에** 읽는다. 같은 슬롯 재수집이면 findLatest가 지금 갱신할 그 행을
                // 돌려주는데, 그 시점의 값은 아직 "직전 실행이 저장한 값"이라 비교 대상으로 맞다
                val latest = repository.findLatest(index.code)
                val status = if (latest != null && latest.sameValuesAs(quote)) MarketStatus.CLOSED else clockStatus

                val existing = repository.findByIndexCodeAndTradeDateAndSlot(index.code, tradeDate, slot.name)
                if (existing == null) {
                    repository.save(newEntity(index.code, tradeDate, slot, quote, status, now))
                    inserted++
                } else {
                    repository.save(existing.apply { overwrite(quote, status, now) })
                    updated++
                }
            } catch (e: Exception) {
                // 한 지수의 실패가 나머지를 끌고 가지 않는다
                failures += "${index.code}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        val summary = DomesticIndexCollectSummary(
            tradeDate = tradeDate,
            slot = slot.name,
            requested = properties.domestic.size,
            collected = inserted + updated,
            inserted = inserted,
            updated = updated,
            failed = failures.size,
            failures = failures,
        )

        when {
            properties.domestic.isEmpty() ->
                log.warn("[지수] 설정된 국내 지수가 없습니다 — market-index.domestic 확인")
            summary.collected == 0 -> recordFailure(summary)
            else -> {
                consecutiveFailures.set(0)
                if (failures.isEmpty()) log.info("[지수] 수집 완료 {}", summary)
                else log.warn("[지수] 일부 실패 {}", summary)
            }
        }
        return summary
    }

    /** 경계는 포함이다 — 09:00 정각과 15:30 정각은 장중으로 본다 */
    private fun marketStatus(time: LocalTime): MarketStatus = when {
        time < MARKET_OPEN -> MarketStatus.PRE_OPEN
        time > MARKET_CLOSE -> MarketStatus.CLOSED
        else -> MarketStatus.OPEN
    }

    /**
     * 네 값을 모두 본다. 현재가만 비교하면 등락률만 어긋난 행을 "무변동"으로 보고
     * 멀쩡한 장중을 장마감으로 낮춘다.
     *
     * BigDecimal은 scale까지 따져 6579.04와 6579.0400이 equals로는 다르다 — compareTo로 본다.
     */
    private fun MarketIndexQuoteEntity.sameValuesAs(quote: IndexQuote): Boolean =
        price.compareTo(quote.price) == 0 &&
            prevClose.compareTo(quote.prevClose) == 0 &&
            changeValue.compareTo(quote.change) == 0 &&
            changeRate.compareTo(quote.changeRate) == 0

    private fun MarketIndexQuoteEntity.overwrite(
        quote: IndexQuote,
        status: MarketStatus,
        collectedAt: LocalDateTime,
    ) {
        price = quote.price
        prevClose = quote.prevClose
        changeValue = quote.change
        changeRate = quote.changeRate
        marketStatus = status.label
        this.collectedAt = collectedAt
    }

    private fun newEntity(
        indexCode: String,
        tradeDate: LocalDate,
        slot: IndexSlot,
        quote: IndexQuote,
        status: MarketStatus,
        collectedAt: LocalDateTime,
    ) = MarketIndexQuoteEntity(
        id = UUID.randomUUID(),
        indexCode = indexCode,
        tradeDate = tradeDate,
        slot = slot.name,
        price = quote.price,
        prevClose = quote.prevClose,
        changeValue = quote.change,
        changeRate = quote.changeRate,
        // KIS 지수 응답에 전일 기준일이 없다. 잊은 게 아니라 실을 값이 없는 것이다
        prevCloseDate = null,
        marketStatus = status.label,
        source = SOURCE,
        collectedAt = collectedAt,
    )

    /** 한 건도 못 건진 실행만 센다. 부분 성공은 데이터가 남았으므로 카운터를 올리지 않는다 */
    private fun recordFailure(summary: DomesticIndexCollectSummary) {
        val count = consecutiveFailures.incrementAndGet()
        val reason = summary.failures.joinToString("; ")
        if (count >= FAILURE_ALERT_THRESHOLD) {
            log.error(
                "[지수] 연속 {}회 전체 실패 tradeDate={} slot={} reason={}",
                count, summary.tradeDate, summary.slot, reason,
            )
        } else {
            log.warn(
                "[지수] 수집 실패({}회) tradeDate={} slot={} reason={}",
                count, summary.tradeDate, summary.slot, reason,
            )
        }
    }
}
