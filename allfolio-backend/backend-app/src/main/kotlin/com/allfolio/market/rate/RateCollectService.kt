package com.allfolio.market.rate

import com.allfolio.fx.EcosApiClient
import com.allfolio.fx.EcosQuery
import com.allfolio.fx.EcosValuePolicy
import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketRateJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 금리 수집 한 번의 결과.
 *
 * @param requested 설정에 있는 종목 수. **0이면 설정이 빈 것이지 ECOS 문제가 아니다**
 * @param skippedRows 값·날짜가 이상해 파서가 버린 행 수. 0이 아니면 형식이 바뀐 신호다
 * @param outOfRange 요청 구간 밖 날짜라 걷어낸 행 수. 아래 필터 주석 참조
 * @param emptySeries 0건으로 돌아온 종목. **실패가 아니다** — 기준금리처럼 변경 시에만
 *                    공표되는 계열은 2주 창에 값이 없는 게 정상이다. 다만 코드가 죽어도
 *                    똑같이 0건이라, 어느 쪽인지는 사람이 봐야 한다. 그래서 세지 말고 이름을 남긴다
 * @param failures "KTB_3Y: <사유>" 형태. 어느 종목이 왜 빠졌는지 한 번에 보여야 한다
 */
data class RateCollectSummary(
    val from: LocalDate,
    val to: LocalDate,
    val requested: Int,
    val collected: Int,
    val inserted: Int,
    val updated: Int,
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
    }

    fun collect(from: LocalDate, to: LocalDate, now: LocalDateTime): RateCollectSummary {
        require(!from.isAfter(to)) { "from이 to보다 늦습니다: $from ~ $to" }

        var inserted = 0
        var updated = 0
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
                        // 값이 같아도 collectedAt은 갱신한다 — "언제 확인한 값인가"가 화면에 나간다.
                        // source도 다시 쓴다: 같은 지표를 다른 소스에서 재수집하는 날
                        // (FRED가 후속으로 붙는다) 첫 수집 소스가 그대로 굳으면,
                        // 정정된 값을 설명하려고 들여다볼 바로 그 필드가 거짓말을 한다
                        prior.rateValue = row.value
                        prior.source = SOURCE
                        prior.collectedAt = now
                        updated++
                    }
                }

                // 같은 응답에 같은 날짜가 두 번 오면 유니크 제약에 걸린다. 마지막 값을 남긴다 —
                // ECOS 정정본이 뒤에 오는 형태이기 때문이다
                val deduped = toInsert.associateBy { it.quoteDate }.values.toList()

                // 위에서 값을 덮어쓴 기존 행을 골라 같이 보낸다 — collectedAt이 now인 게 그 표시다.
                // 이 행들은 detached 상태라(open-in-view: false + 트랜잭션 없음) 필드만 바꿔서는
                // 아무 일도 일어나지 않는다. saveAll을 거쳐야 merge가 나간다.
                // 손대지 않은 행까지 싣지 않는 이유는 merge가 행마다 SELECT를 하나씩 내기 때문이다.
                store.saveAll(deduped + existing.values.filter { it.collectedAt == now })
                inserted += deduped.size
            } catch (e: Exception) {
                // 한 종목의 실패가 나머지를 끌고 가지 않는다
                failures += "${series.code}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        val summary = RateCollectSummary(
            from = from,
            to = to,
            requested = properties.series.size,
            collected = inserted + updated,
            inserted = inserted,
            updated = updated,
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
}

/**
 * JPA 레포를 [RateCollectService.Store]에 맞춘다.
 *
 * 서비스가 JPA 인터페이스를 직접 받지 않게 하는 얇은 층이다 — 테스트가 스무 개 넘는
 * 상속 메서드를 흉내 내지 않아도 되게 하는 것이 목적이고, 다른 의도는 없다.
 */
@Component
class JpaRateStore(private val repository: MarketRateJpaRepository) : RateCollectService.Store {
    override fun findRange(rateCode: String, from: LocalDate, to: LocalDate) =
        repository.findByRateCodeAndQuoteDateBetween(rateCode, from, to)

    override fun saveAll(entities: List<MarketRateEntity>) {
        repository.saveAll(entities)
    }
}
