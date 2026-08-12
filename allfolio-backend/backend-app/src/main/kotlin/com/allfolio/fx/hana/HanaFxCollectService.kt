package com.allfolio.fx.hana

import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 수집이 성공했을 때의 결과. 안전장치에 걸리면 요약이 아니라 예외가 나가므로
 * "이상 항목" 필드는 두지 않는다 — 항상 비어 있을 수밖에 없어 정보를 주는 척만 한다.
 *
 * @param baseDate 하나은행이 응답에 담아 준 기준일. 요청한 조회일자가 아니다
 * @param skipped  파싱 단계에서 버린 행 수. 안전장치에 걸려 저장이 막힌 것과는 다르다
 */
data class HanaCollectSummary(
    val requestedDate: LocalDate,
    val baseDate: LocalDate,
    val roundNo: Int,
    val currencies: Int,
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val skipped: Int,
)

/**
 * 하나은행 고시환율 수집 (AF-99).
 *
 * `@Transactional`을 붙이지 않는다 — 20초까지 걸리는 HTTP 호출이 트랜잭션 안에 들어가면
 * 그동안 Neon 커넥션을 쥐고 앉아 있게 된다. `saveAll`은 Spring Data 리포지토리 레벨에서
 * 이미 트랜잭션이라 배치 원자성은 확보된다. 별도 빈으로 쪼개면 AF-90에서 물린
 * 자기호출 프록시 함정이 되살아나므로, 그냥 붙이지 않는 쪽이 맞다.
 */
@Service
class HanaFxCollectService(
    private val client: HanaFxClient,
    private val parser: HanaFxParser,
    private val guards: HanaFxGuards,
    private val repository: HanaFxQuoteJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 연속 실패 횟수. 프로세스 메모리에만 둔다 —
     * 하는 일이 "로그 레벨을 올린다"뿐이라 테이블을 늘릴 값어치가 없고,
     * 재시작으로 0이 되는 것도 손해가 아니다(재시작 자체가 이미 조사할 사건이다).
     */
    private val consecutiveFailures = AtomicInteger(0)

    companion object {
        private const val FAILURE_ALERT_THRESHOLD = 3
    }

    fun collect(date: LocalDate, force: Boolean): HanaCollectSummary {
        val snapshot = try {
            parser.parse(client.fetch(date))
        } catch (e: Exception) {
            recordFailure(date, e.javaClass.simpleName)
            throw e
        }

        // 주말·공휴일에 직전 영업일 고시가 오는 건 정상이므로 같은지는 따지지 않는다.
        // 다만 미래 고시는 존재할 수 없다 — pbldDvCd가 틀렸거나 응답이 뒤바뀐 것이다.
        // 이건 우리 판단이 아니라 은행 응답이 틀린 것이라 HanaFxParseException(→502)으로 올린다.
        // IllegalStateException(→422)으로 던지면 운영자가 제 요청부터 의심하게 된다
        if (snapshot.baseDate.isAfter(date)) {
            val reason = "응답 기준일이 요청일보다 미래입니다 (${snapshot.baseDate} > $date)"
            recordFailure(date, reason)
            throw HanaFxParseException(reason)
        }

        // 조회일자가 아니라 응답이 말하는 기준일·회차로 저장한다
        val existing = repository.findAllByBaseDateAndRoundNo(snapshot.baseDate, snapshot.roundNo)
            .associateBy { it.currency }

        // 비교 기준은 "가장 최근에 저장된 회차 통째"다. 현재 스냅샷의 통화로만 직전 값을 모으면
        // previousRates 크기가 스냅샷 크기를 절대 넘지 못해 행 수 비율이 항상 1.0이 되고,
        // 급감 가드가 영원히 안 걸린다. USD는 안전장치가 강제하므로 어느 저장된 회차에나 있다
        val previousRound = repository.findTopByCurrencyOrderByBaseDateDescRoundNoDesc("USD")
        val previousRows = previousRound
            ?.let { repository.findAllByBaseDateAndRoundNo(it.baseDate, it.roundNo) }
            ?: emptyList()
        val previousRates = previousRows.associate { it.currency to it.baseRate }
        val previousRowCount = previousRows.size.takeIf { it > 0 }

        val anomalies = guards.check(snapshot.rows, previousRates, previousRowCount, force)
        if (anomalies.isNotEmpty()) {
            recordFailure(date, anomalies.joinToString("; "))
            throw IllegalStateException(
                "안전장치에 걸려 저장하지 않았습니다: ${anomalies.joinToString("; ")}",
            )
        }

        var inserted = 0
        var updated = 0
        var unchanged = 0
        val rows = snapshot.rows.map { row ->
            val prev = existing[row.currency]
            when {
                prev == null -> {
                    inserted++
                    toEntity(snapshot, row)
                }
                // 분류는 덮기 **전에** 끝낸다 — 덮은 뒤 비교하면 전부 무변화가 된다
                prev.sameAs(row) -> {
                    unchanged++
                    prev.apply { overwrite(row) }
                }
                else -> {
                    updated++
                    prev.apply { overwrite(row) }
                }
            }
        }
        repository.saveAll(rows)
        consecutiveFailures.set(0)

        val summary = HanaCollectSummary(
            requestedDate = date,
            baseDate = snapshot.baseDate,
            roundNo = snapshot.roundNo,
            currencies = snapshot.rows.size,
            inserted = inserted,
            updated = updated,
            unchanged = unchanged,
            skipped = snapshot.skipped,
        )
        log.info("[하나은행] 수집 완료 {}", summary)
        return summary
    }

    /**
     * overwrite가 쓰는 다섯 필드를 모두 본다. 매매기준율만 보면 현찰·송금 환율이 움직인 회차를
     * "무변화"로 세면서 overwrite는 실제로 값을 쓴다 — 요약이 운영자에게 거짓말을 한다.
     */
    private fun HanaFxQuoteEntity.sameAs(row: HanaFxRow): Boolean =
        baseRate.compareTo(row.baseRate) == 0 &&
            sameRate(cashBuy, row.cashBuy) &&
            sameRate(cashSell, row.cashSell) &&
            sameRate(remitSend, row.remitSend) &&
            sameRate(remitReceive, row.remitReceive)

    /**
     * BigDecimal은 scale까지 따지므로 1390과 1390.0000이 equals로는 다르다 — compareTo로 본다.
     * null은 "그 통화에 그 고시가 없다"는 뜻이라 값과 같을 수 없고, null끼리만 같다.
     */
    private fun sameRate(a: BigDecimal?, b: BigDecimal?): Boolean =
        if (a == null || b == null) a == null && b == null else a.compareTo(b) == 0

    private fun HanaFxQuoteEntity.overwrite(row: HanaFxRow) {
        baseRate = row.baseRate
        cashBuy = row.cashBuy
        cashSell = row.cashSell
        remitSend = row.remitSend
        remitReceive = row.remitReceive
    }

    private fun toEntity(snapshot: HanaFxSnapshot, row: HanaFxRow) = HanaFxQuoteEntity(
        id = UUID.randomUUID(),
        baseDate = snapshot.baseDate,
        roundNo = snapshot.roundNo,
        currency = row.currency,
        baseRate = row.baseRate,
        cashBuy = row.cashBuy,
        cashSell = row.cashSell,
        remitSend = row.remitSend,
        remitReceive = row.remitReceive,
        collectedAt = LocalDateTime.now(),
    )

    private fun recordFailure(date: LocalDate, reason: String) {
        val count = consecutiveFailures.incrementAndGet()
        if (count >= FAILURE_ALERT_THRESHOLD) {
            log.error("[하나은행] 연속 {}회 실패 date={} reason={}", count, date, reason)
        } else {
            log.warn("[하나은행] 수집 실패({}회) date={} reason={}", count, date, reason)
        }
    }
}
