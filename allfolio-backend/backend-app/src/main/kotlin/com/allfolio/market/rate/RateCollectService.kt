package com.allfolio.market.rate

import com.allfolio.fx.EcosApiClient
import com.allfolio.fx.EcosQuery
import com.allfolio.fx.EcosValuePolicy
import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 금리 수집 한 번의 결과.
 *
 * 저장된 행을 셋으로 가르는 이유: 신규 삽입과, 같은 값으로 다시 쓴 것과, 값이 실제로 바뀐 정정은
 * 운영자에게 전혀 다른 사건이다. 이 잡은 매번 최근 2주를 다시 조회하므로 매 실행이
 * `updated + unchanged ≈ 영업일 10 x 종목 6`을 만들어 낸다 — 뭉쳐 놓으면 그중 하나뿐인
 * ECOS 정정이 59건의 동일값 재기록에 묻혀 안 보인다. 2주 창을 둔 이유가 정정을 잡는 것인데
 * 요약이 정정을 못 보여주면 창이 무의미해진다.
 *
 * @param requested 설정에 있는 종목 수. **0이면 설정이 빈 것이지 ECOS 문제가 아니다**
 * @param collected 실제로 저장한 행 수 = [inserted] + [updated] + [unchanged].
 *                  **저장이 끝난 뒤에 센 값이다** — 어드민이 이 값으로 "한 건도 안 들어간 실행"을 가른다
 * @param inserted 그 날짜에 행이 없어 새로 만든 수
 * @param updated 기존 행의 값이 실제로 바뀐 수 (ECOS 정정)
 * @param unchanged 기존 행을 같은 값으로 다시 쓴 수. `collectedAt`·`source`만 갱신된 경우도 여기 든다
 * @param skippedRows 값·날짜가 이상해 파서가 버린 행 수. 0이 아니면 형식이 바뀐 신호다
 * @param outOfRange 요청 구간 밖 날짜라 걷어낸 행 수. 아래 필터 주석 참조
 * @param emptySeries 저장할 행이 한 건도 남지 않은 종목 (0건 응답이거나 전부 구간 밖이라 걷힌 경우).
 *                    **실패가 아니다** — 기준금리처럼 변경 시에만 공표되는 계열은 2주 창에 값이 없는 게
 *                    정상이다. 다만 코드가 죽어도 똑같이 0건이라, 어느 쪽인지는 사람이 봐야 한다.
 *                    그래서 세지 말고 이름을 남긴다
 * @param failures "KTB_3Y: <사유>" 형태. 어느 종목이 왜 빠졌는지 한 번에 보여야 한다
 */
data class RateCollectSummary(
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
 * 금리 수집 (AF-102).
 *
 * 일일 수집과 백필이 같은 경로를 쓴다 — 둘 다 "이 구간을 ECOS가 준 값으로 맞춘다"이고 멱등하다.
 * 스케줄 실행이 매번 최근 2주를 다시 조회하는 이유는 셋이다:
 * 공표가 밀리는 계열이 있고, ECOS는 값을 정정하며, 잡이 하루 실패해도 다음 날이 메운다.
 *
 * **다년 구간은 나눠 호출해야 한다 — 편의가 아니라 필수다.**
 * `MarketRateEntity.id`가 할당식이고 `@Version`도 없어서 Spring Data가 기존 행을 전부
 * `em.merge`로 보낸다. merge는 행마다 SELECT를 하나씩 내고, `batch_size`는 쓰기만 묶지
 * 이 SELECT들은 묶지 않는다. 일별 10년치면 종목 하나에 ~2,600행이고 6종목이면 순차 왕복이
 * 만 회를 넘는다 — 무료 플랜 Neon에서 커넥션을 오래 쥐게 되어, 바로 아래 "트랜잭션을 안 붙인다"의
 * 이유를 스스로 무너뜨린다. 스케줄 실행은 2주라 상관없지만 어드민 엔드포인트는 from/to를
 * 그대로 받으므로, 긴 구간은 1~2년씩 끊어 호출할 것.
 * (AF-100의 `FxRateBackfillService`가 같은 비용 모델로 같은 주의를 달고 있다.)
 *
 * `@Transactional`을 붙이지 않는다 — 종목마다 HTTP 호출이 하나씩 있어서 트랜잭션에 넣으면
 * 루프가 끝날 때까지 Neon 커넥션을 쥐고 앉아 있게 된다. AF-101 지수 수집과 같은 이유다.
 */
@Service
class RateCollectService(
    private val client: EcosApiClient,
    private val store: Store,
    private val properties: MarketRateProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 저장에 필요한 것만 추린 좁은 포트.
     *
     * 서비스가 `JpaRepository`를 통째로 받으면 테스트가 스무 개 넘는 메서드를 구현하거나
     * 목으로 덮어야 한다. 실제로 쓰는 건 둘뿐이다.
     */
    interface Store {
        fun findRange(rateCode: String, from: LocalDate, to: LocalDate): List<MarketRateEntity>
        fun saveAll(entities: List<MarketRateEntity>)
    }

    companion object {
        private const val SOURCE = "ECOS"

        /**
         * 실패 사유 길이 상한.
         *
         * `DataIntegrityViolationException` 메시지는 SQL과 파라미터가 통째로 실린 여러 줄 덤프다.
         * 이 문자열은 어드민 JSON 응답과 GitHub Actions 주석에 그대로 나가므로 자른다 —
         * `EcosApiClient`가 본문 미리보기를 200자로 자르는 것과 같은 이유다.
         */
        private const val FAILURE_DETAIL_LENGTH = 200
    }

    fun collect(from: LocalDate, to: LocalDate, now: LocalDateTime): RateCollectSummary {
        require(!from.isAfter(to)) { "from이 to보다 늦습니다: $from ~ $to" }

        var inserted = 0
        var updated = 0
        var unchanged = 0
        var skippedRows = 0
        var outOfRange = 0
        val emptySeries = mutableListOf<String>()
        val failures = mutableListOf<String>()

        for (series in properties.series) {
            try {
                val result = client.fetch(
                    EcosQuery(
                        statCode = series.statCode,
                        itemCode = series.itemCode,
                        cycle = series.cycle,
                        // 금리는 0.00%도 마이너스도 실재한다 — 환율 정책으로 부르면 그 날이 사라진다
                        valuePolicy = EcosValuePolicy.PERCENT,
                    ),
                    from,
                    to,
                )
                skippedRows += result.skipped

                // 요청 구간 밖 날짜를 먼저 걷어낸다. 파서는 날짜만 파싱되면 통과시키므로
                // 소스가 구간 밖 날짜를 섞어 줄 수 있는데, 아래 existing 조회는 from..to로 한정된다 —
                // 그 날짜 행이 이미 테이블에 있으면(2주 창이 매일 겹치므로 반드시 있다) existing에서
                // 안 잡혀 새 UUID로 INSERT가 나가고 uk_market_rate가 배치 전체를 죽인다.
                // 재실행해도 똑같이 실패하고 운영자에게는 불투명한 제약 위반만 남는다.
                // AF-100의 FxRateBackfillService가 같은 방어를 한다 — 겪고 나서 생긴 것이다.
                val inRange = result.rates.filter { it.baseDate in from..to }
                outOfRange += result.rates.size - inRange.size

                // **0건을 실패로 만들지 않는다.** 기준금리처럼 변경 시에만 공표되는 계열은
                // 2주 창에 값이 없는 게 정상이다. 다만 통계표 코드가 죽어도 똑같이 0건이라
                // 자동으로는 못 가른다 — 이름을 남겨 사람이 보게 한다.
                if (inRange.isEmpty()) emptySeries += series.code

                val existing = store.findRange(series.code, from, to).associateBy { it.quoteDate }
                val toInsert = mutableListOf<MarketRateEntity>()

                // 갱신 대상. 키가 엔티티 인스턴스이고 MarketRateEntity는 equals를 정의하지 않으므로
                // 동일성으로 접힌다 — 같은 날짜가 두 번 와도 한 번만 든다. 값은 "덮기 전 값"이다:
                // 정정과 무변동을 가르려면 원본이 필요한데 덮고 나면 사라진다.
                val toUpdate = LinkedHashMap<MarketRateEntity, BigDecimal>()

                for (row in inRange) {
                    val prior = existing[row.baseDate]
                    if (prior == null) {
                        toInsert += MarketRateEntity(
                            id = UUID.randomUUID(),
                            rateCode = series.code,
                            quoteDate = row.baseDate,
                            rateValue = row.value,
                            source = SOURCE,
                            collectedAt = now,
                        )
                    } else {
                        toUpdate.putIfAbsent(prior, prior.rateValue)
                        // 값이 같아도 collectedAt은 갱신한다 — "언제 확인한 값인가"가 화면에 나간다.
                        // source도 다시 쓴다: 같은 지표를 다른 소스에서 재수집하는 날
                        // (FRED가 후속으로 붙는다) 첫 수집 소스가 그대로 굳으면,
                        // 정정된 값을 설명하려고 들여다볼 바로 그 필드가 거짓말을 한다
                        prior.rateValue = row.value
                        prior.source = SOURCE
                        prior.collectedAt = now
                    }
                }

                // 같은 응답에 같은 날짜가 두 번 오면 유니크 제약에 걸린다. 마지막 값을 남긴다 —
                // ECOS 정정본이 뒤에 오는 형태이기 때문이다
                val deduped = toInsert.associateBy { it.quoteDate }.values.toList()

                // 갱신분은 detached 상태라(open-in-view: false + 트랜잭션 없음) 필드만 바꿔서는
                // 아무 일도 일어나지 않는다. saveAll을 거쳐야 merge가 나간다.
                val rows = deduped + toUpdate.keys
                // 빈 배치도 리포지토리 레벨 트랜잭션을 연다. BASE_RATE는 대부분의 실행에서 정상적으로 비어 있다
                if (rows.isNotEmpty()) store.saveAll(rows)

                // **반드시 저장한 뒤에 센다.** 세고 나서 저장하면 saveAll이 통째로 터진 실행에서도
                // collected가 채워지고, 어드민이 collected == 0으로 잡아내려던 "한 건도 안 들어간 잡"이
                // 초록으로 지나간다. 커넥션이 끊겨 6종목 전부 실패해도 요약은 60건 수집이라고 말한다.
                inserted += deduped.size
                // 스케일이 달라도 같은 값이므로 compareTo로 본다 (3.10과 3.1000은 equals로는 다르다)
                toUpdate.forEach { (row, before) ->
                    if (row.rateValue.compareTo(before) == 0) unchanged++ else updated++
                }
            } catch (e: Exception) {
                // 한 종목의 실패가 나머지를 끌고 가지 않는다
                failures += "${series.code}: ${detail(e)}"
            }

            // 종료 신호는 예외로 위장해서 온다 — EcosApiClient는 InterruptedException을 만나면
            // 플래그를 되살리고 EcosApiException으로 바꿔 던지므로 위 catch가 그대로 삼킨다.
            // 플래그를 안 보면 셧다운 중에 남은 종목을 끝까지 돌며 가짜 실패만 쌓는다.
            if (Thread.currentThread().isInterrupted) break
        }

        val summary = RateCollectSummary(
            from = from,
            to = to,
            requested = properties.series.size,
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
            properties.series.isEmpty() ->
                log.warn("[금리] 설정된 수집 대상이 없습니다 — market-rate.series 확인")
            failures.isEmpty() -> log.info("[금리] 수집 완료 {}", summary)
            else -> log.warn("[금리] 일부 실패 {}", summary)
        }
        return summary
    }

    private fun detail(e: Exception): String {
        val message = e.message ?: return e.javaClass.simpleName
        return if (message.length <= FAILURE_DETAIL_LENGTH) message else message.take(FAILURE_DETAIL_LENGTH) + "…"
    }
}
