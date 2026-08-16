package com.allfolio.market.commodity

import com.allfolio.unifiedasset.infrastructure.entity.MarketCommodityQuoteEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TreeMap
import java.util.UUID

/**
 * 원자재 수집 한 번의 결과. 필드 구성은 [com.allfolio.market.rate.RateCollectSummary]와 같다 —
 * 어드민 판정과 워크플로 요약이 같은 축을 읽는다.
 *
 * @param requested 수집 대상 수 = 소스별 코드 수의 합. **0이면 설정이 빈 것이지 상류 문제가 아니다**
 * @param collected 실제로 저장한 행 수 = [inserted] + [updated] + [unchanged].
 *                  **저장이 끝난 뒤에 센 값이다** — 어드민이 이 값으로 "한 건도 안 들어간 실행"을 가른다
 * @param inserted 그 날짜에 행이 없어 새로 만든 수
 * @param updated 기존 행의 **가격이 실제로 바뀐** 수 (소스의 정정)
 * @param unchanged 기존 행을 같은 가격으로 다시 쓴 수. `collectedAt`·`source`·단위만 갱신된 경우도 여기 든다
 * @param skippedRows 값·날짜가 이상해 파서가 버린 행 수. 0이 아니면 형식이 바뀐 신호다
 * @param outOfRange 요청 구간 밖 날짜라 걷어낸 행 수
 * @param emptySeries 저장할 행이 한 건도 남지 않은 종목. **그 자체로 실패는 아니다** —
 *                    다만 시리즈 ID가 죽어도 똑같이 0건이라 어느 쪽인지는 사람이 봐야 한다.
 *                    그래서 세지 말고 이름을 남긴다.
 *                    **월간 계열을 "정상적으로 빈" 예로 들지 말 것** — 기본 수집 창이 90일이라
 *                    IMF의 두 달 지연을 감안해도 관측이 최소 한 건은 들어온다(그 창 길이의 근거가 그것이다).
 * @param failures "WTI: <사유>" 형태. 어느 종목이 왜 빠졌는지 한 번에 보여야 한다
 */
data class CommodityCollectSummary(
    val from: LocalDate,
    val to: LocalDate,
    val requested: Int,
    val collected: Int,
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val skippedRows: Int,
    val outOfRange: Int,
    val emptySeries: List<String>,
    val failed: Int,
    val failures: List<String>,
)

/**
 * 원자재 수집 (AF-108).
 *
 * **[com.allfolio.market.rate.RateCollectService]를 옮겨 온 것이다.** 아래 방어들
 * (구간 밖 날짜 필터·0건 명명·중복 접기·저장 뒤 계수·정정과 무변동 분리·종목별 실패 격리·
 * 인터럽트 확인)은 AF-102가 ECOS를 겪으며 네 차례에 걸쳐 붙인 것이고 **소스와 무관하게 옳다.**
 * 한쪽만 고쳐진 판본이 생기지 않도록, 고칠 일이 생기면 두 파일을 같이 볼 것.
 *
 * 일일 수집과 백필이 같은 경로를 쓴다 — 둘 다 "이 구간을 소스가 준 값으로 맞춘다"이고 멱등하다.
 *
 * **`@Transactional`을 붙이지 않는다** — 종목마다 HTTP 호출이 하나씩 있어서 트랜잭션에 넣으면
 * 루프가 끝날 때까지 Neon 커넥션을 쥐고 앉아 있게 된다. 금리·지수 수집과 같은 이유다.
 *
 * **다년 구간은 나눠 호출해야 한다.** `MarketCommodityQuoteEntity.id`가 할당식이고 `@Version`도
 * 없어서 Spring Data가 기존 행을 전부 `em.merge`로 보내고, merge는 행마다 SELECT를 하나씩 낸다.
 * 어드민 엔드포인트가 최대 구간을 막아 두는 이유가 그것이다.
 *
 * **금리와 다른 것 둘.**
 *  1. `unit`·`frequency`가 관측이 아니라 **설정**([CommodityProperties])에서 온다 — 소스가 그것을
 *     응답에 싣지 않는다. 그래서 소스가 준 코드를 설정에서 되찾지 못하면 그 종목은 실패다
 *     (설정과 소스가 어긋난 상태를 조용히 저장하면 화면 단위가 틀린다).
 *  2. `prevClose`·`changeValue`·`changeRate`를 **여기서 계산한다.** KIS 지수와 달리 소스가
 *     전일 종가를 주지 않는다. 없으면 `null`이다 — `0`이 아니다.
 */
@Service
class CommodityCollectService(
    private val sources: List<CommoditySource>,
    private val properties: CommodityProperties,
    private val store: Store,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 저장에 필요한 것만 추린 좁은 포트. 서비스가 `JpaRepository`를 통째로 받으면 테스트가
     * 스무 개 넘는 상속 메서드를 흉내 내야 한다 — 실제로 쓰는 건 셋뿐이다.
     */
    interface Store {
        fun findRange(code: String, from: LocalDate, to: LocalDate): List<MarketCommodityQuoteEntity>

        /**
         * `tradeDate < before` 중 가장 최근 한 행. 수집 창 **바깥**의 전일대비 출발점이다.
         *
         * **"어제"를 날짜 산술로 구하지 않는 이유가 이 메서드다** — 월간 계열의 직전 관측은
         * 한 달 전 1일이고, 일간 계열도 연휴 뒤엔 나흘 전이다.
         */
        fun findLatestBefore(code: String, before: LocalDate): MarketCommodityQuoteEntity?

        fun saveAll(entities: List<MarketCommodityQuoteEntity>)
    }

    companion object {
        /**
         * 실패 사유 길이 상한. `DataIntegrityViolationException` 메시지는 SQL과 파라미터가
         * 통째로 실린 여러 줄 덤프인데, 이 문자열은 어드민 JSON 응답과 GitHub Actions 주석에
         * 그대로 나간다. `RateCollectService`가 같은 이유로 같은 상한을 둔다.
         */
        private const val FAILURE_DETAIL_LENGTH = 200

        /** `change_rate`는 NUMERIC(9,4) — % 소수 4자리다 */
        private const val CHANGE_RATE_SCALE = 4

        private val HUNDRED = BigDecimal(100)
    }

    fun collect(from: LocalDate, to: LocalDate, now: LocalDateTime): CommodityCollectSummary {
        require(!from.isAfter(to)) { "from이 to보다 늦습니다: $from ~ $to" }

        var inserted = 0
        var updated = 0
        var unchanged = 0
        var skippedRows = 0
        var outOfRange = 0
        val emptySeries = mutableListOf<String>()
        val failures = mutableListOf<String>()

        // 소스 x 코드로 편다. 어느 소스가 어느 코드를 갖는지는 소스가 안다 —
        // 서비스는 설정 모양을 알 필요가 없고, 그래서 소스가 늘어도(FSC가 붙어도) 이 루프는 안 바뀐다
        val targets = sources.flatMap { source -> source.codes.map { source to it } }

        for ((source, code) in targets) {
            try {
                // 단위·주기는 설정에만 있다. 못 찾으면 저장하지 않고 실패로 남긴다 —
                // 빈 문자열로 채우면 화면이 단위 없는 숫자를 그럴듯하게 보여준다
                val item = properties.allItems.firstOrNull { it.code == code }
                    ?: throw IllegalStateException("설정(market-commodity)에 없는 코드입니다")

                val result = source.fetch(code, from, to)
                skippedRows += result.skipped

                // 요청 구간 밖 날짜를 먼저 걷어낸다. 파서는 날짜만 파싱되면 통과시키므로
                // 소스가 구간 밖 날짜를 섞어 줄 수 있는데, 아래 existing 조회는 from..to로 한정된다 —
                // 그 날짜 행이 이미 테이블에 있으면 existing에서 안 잡혀 새 UUID로 INSERT가 나가고
                // uk_market_commodity_quote가 배치 전체를 죽인다. 재실행해도 똑같이 죽는다.
                val inRange = result.rows.filter { it.quoteDate in from..to }
                outOfRange += result.rows.size - inRange.size

                // **0건을 실패로 만들지 않는다.** 다만 시리즈 ID가 틀려도 똑같이 0건이라
                // 자동으로는 못 가른다 — 이름을 남겨 사람이 보게 한다
                if (inRange.isEmpty()) emptySeries += code

                // 같은 응답에 같은 날짜가 두 번 오면 유니크 제약에 걸린다. 마지막 값을 남긴다 —
                // 정정본이 뒤에 오는 형태이기 때문이다
                val deduped = LinkedHashMap<LocalDate, BigDecimal>()
                inRange.forEach { deduped[it.quoteDate] = it.value }

                val existing = store.findRange(code, from, to).associateBy { it.tradeDate }

                // 전일대비의 사다리: 날짜 -> 가격. 창 안의 기존 행을 깔고 이번에 온 값으로 덮은 뒤,
                // 창 바깥의 직전 한 행을 출발점으로 얹는다.
                //  · 기존 행을 깔아야 소스가 일부 날짜만 준 실행에서도 직전 값을 찾는다
                //  · 이번 값으로 덮어야 같은 실행에서 정정된 직전 값이 반영된다
                //  · 창 바깥 한 행이 있어야 창의 첫 관측과 월간 계열이 null로 남지 않는다
                //    (월간의 "직전"은 한 달 전 1일이라 창 안에 없을 수 있다)
                val ladder = TreeMap<LocalDate, BigDecimal>()
                existing.forEach { (date, row) -> ladder[date] = row.price }
                deduped.forEach { (date, value) -> ladder[date] = value }
                store.findLatestBefore(code, from)?.let { ladder[it.tradeDate] = it.price }

                val toInsert = mutableListOf<MarketCommodityQuoteEntity>()

                // 갱신 대상. 값은 "덮기 전 가격"이다 — 정정과 무변동을 가르려면 원본이 필요한데
                // 덮고 나면 사라진다. 키가 엔티티 인스턴스이고 equals를 정의하지 않으므로 동일성으로 접힌다
                val toUpdate = LinkedHashMap<MarketCommodityQuoteEntity, BigDecimal>()

                // 날짜 오름차순으로 돈다 — 같은 실행 안에서 앞 날짜가 뒤 날짜의 직전 값이 된다
                for ((date, value) in deduped.entries.sortedBy { it.key }) {
                    // **날짜 산술이 아니라 "가장 최근 이전 행"이다.** headMap(date)는 date 미만이고
                    // lastKey()가 그중 가장 최근이다. 하루를 빼서 찾으면 월간은 영원히 null이 된다
                    val prevClose = ladder.headMap(date).takeIf { it.isNotEmpty() }?.let { it[it.lastKey()] }
                    val changeValue = prevClose?.let { value.subtract(it) }
                    // 직전 값이 0이면 변화율을 계산할 수 없다(0으로 나눈다). **그때도 0을 넣지 않는다** —
                    // 0은 "안 움직였다"는 뜻이고 여기서 필요한 뜻은 "모른다"다
                    val changeRate = if (prevClose == null || prevClose.signum() == 0) {
                        null
                    } else {
                        changeValue!!.multiply(HUNDRED).divide(prevClose, CHANGE_RATE_SCALE, RoundingMode.HALF_UP)
                    }

                    val prior = existing[date]
                    if (prior == null) {
                        toInsert += MarketCommodityQuoteEntity(
                            id = UUID.randomUUID(),
                            code = code,
                            tradeDate = date,
                            price = value,
                            unit = item.unit,
                            frequency = item.frequency,
                            prevClose = prevClose,
                            changeValue = changeValue,
                            changeRate = changeRate,
                            source = source.sourceName,
                            collectedAt = now,
                        )
                    } else {
                        toUpdate.putIfAbsent(prior, prior.price)
                        // 값이 같아도 collectedAt은 갱신한다 — "언제 확인한 값인가"가 화면에 나간다.
                        // unit·frequency·source도 다시 쓴다: 설정을 고쳐 단위 표기를 정정하거나
                        // 같은 코드를 다른 소스로 재수집하는 날, 첫 수집 당시 값이 그대로 굳으면
                        // 숫자를 설명하려고 들여다볼 바로 그 필드가 거짓말을 한다
                        prior.price = value
                        prior.unit = item.unit
                        prior.frequency = item.frequency
                        prior.prevClose = prevClose
                        prior.changeValue = changeValue
                        prior.changeRate = changeRate
                        prior.source = source.sourceName
                        prior.collectedAt = now
                    }
                }

                // 갱신분은 detached 상태라(open-in-view: false + 트랜잭션 없음) 필드만 바꿔서는
                // 아무 일도 일어나지 않는다. saveAll을 거쳐야 merge가 나간다.
                val rows = toInsert + toUpdate.keys
                // 빈 배치도 리포지토리 레벨 트랜잭션을 연다. 상류가 0건을 주는 실행은 실제로 있다
                if (rows.isNotEmpty()) store.saveAll(rows)

                // **반드시 저장한 뒤에 센다.** 세고 나서 저장하면 saveAll이 통째로 터진 실행에서도
                // collected가 채워지고, 어드민이 collected == 0으로 잡아내려던 "한 건도 안 들어간 잡"이
                // 초록으로 지나간다. 커넥션이 끊겨 전 종목이 실패해도 요약은 수백 건 수집이라고 말한다.
                inserted += toInsert.size
                // 스케일이 달라도 같은 값이므로 compareTo로 본다 (3.10과 3.1000은 equals로는 다르다).
                // **가격만 본다** — 직전 값이 바뀌어 파생 필드만 달라진 것은 소스의 정정이 아니다
                toUpdate.forEach { (row, before) ->
                    if (row.price.compareTo(before) == 0) unchanged++ else updated++
                }
            } catch (e: Exception) {
                // 한 종목의 실패가 나머지를 끌고 가지 않는다
                failures += "$code: ${detail(e)}"
            }

            // 종료 신호는 예외로 위장해서 온다 — FredApiClient는 InterruptedException을 만나면
            // 플래그를 되살리고 FredApiException으로 바꿔 던지므로 위 catch가 그대로 삼킨다.
            // 플래그를 안 보면 셧다운 중에 남은 종목을 끝까지 돌며 가짜 실패만 쌓는다.
            if (Thread.currentThread().isInterrupted) break
        }

        val summary = CommodityCollectSummary(
            from = from,
            to = to,
            // 설정 항목 수가 아니라 소스 x 코드 대상 수다 — FSC가 붙는 날 둘이 갈린다.
            // 어드민이 requested == 0으로 "설정이 빈 실행"을 가르므로 실제로 돈 대상을 세야 한다
            requested = targets.size,
            collected = inserted + updated + unchanged,
            inserted = inserted,
            updated = updated,
            unchanged = unchanged,
            skippedRows = skippedRows,
            outOfRange = outOfRange,
            emptySeries = emptySeries,
            failed = failures.size,
            failures = failures,
        )

        when {
            targets.isEmpty() ->
                log.warn("[원자재] 설정된 수집 대상이 없습니다 — market-commodity 설정 확인")
            failures.isEmpty() -> log.info("[원자재] 수집 완료 {}", summary)
            else -> log.warn("[원자재] 일부 실패 {}", summary)
        }
        return summary
    }

    private fun detail(e: Exception): String {
        val message = e.message ?: return e.javaClass.simpleName
        return if (message.length <= FAILURE_DETAIL_LENGTH) message else message.take(FAILURE_DETAIL_LENGTH) + "…"
    }
}
