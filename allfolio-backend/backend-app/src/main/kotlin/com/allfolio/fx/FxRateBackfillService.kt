package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 백필 한 번의 결과. "무엇이 들어갔나"에 답하는 게 존재 이유다.
 *
 * 저장된 행을 셋으로 가르는 이유: 2,600건 신규 삽입과, 2,600건을 같은 값으로 다시 쓴 것과,
 * 그중 300건의 값이 실제로 바뀐 정정은 운영자에게 전혀 다른 사건이다.
 * 마지막 경우가 [UnifiedAssetFxConverterAdapter.invalidate]가 존재하는 이유이므로,
 * `updated > 0`이 곧 캐시를 비운 이유를 설명하는 신호가 된다.
 *
 * 버려진 행도 셋으로 나눠 싣는다. 하나로 뭉치면 "2,600건 요청했는데 2,400건만 들어간" 이유를
 * 로그를 뒤져야 알 수 있고, 그건 반환값이 있는 의미가 없다.
 *
 * @param saved      저장된 행 수 = [inserted] + [updated] + [unchanged]
 * @param inserted   그 날짜에 행이 없어 새로 만든 수
 * @param updated    기존 행의 환율 값이 실제로 바뀐 수 (정정)
 * @param unchanged  기존 행을 같은 값으로 다시 쓴 수. 출처(source)만 바뀐 경우도 값이 같으면 여기 든다
 * @param skipped    값·날짜가 이상해 파서가 버린 행 수 — 조용히 삼키지 않는다
 * @param duplicates 같은 날짜가 중복으로 와서 접은 행 수
 * @param outOfRange 요청 범위 밖 날짜라 버린 행 수
 */
data class BackfillSummary(
    val currency: String,
    val from: LocalDate,
    val to: LocalDate,
    val saved: Int,
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val skipped: Int,
    val duplicates: Int,
    val outOfRange: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
)

/**
 * 과거 환율 백필 (AF-100).
 *
 * **가져오기는 소스별이고 저장하기는 공용이다.** 어떤 [HistoricalRateSource]가 통화를 맡을지는
 * `supports`로 고르고, 그 뒤(0건 중단·범위 밖 제거·dedupe·계수·캐시 무효화)는 소스와 무관하게
 * 이 서비스가 한 벌만 갖는다 — ECOS를 겪으며 생긴 방어지만 어느 소스에나 옳기 때문이다.
 *
 * 재실행이 안전해야 긴 기간을 나눠 돌릴 수 있으므로, 기존 행을 한 번에 읽어
 * 같은 (통화, 기준일)이면 값만 덮는다. 네이티브 UPSERT를 쓰지 않는 이유는
 * H2(테스트)와 Postgres(운영) 문법이 갈리기 때문이고, 어드민이 수동으로 한 번씩
 * 돌리는 경로라 동시 실행 경합을 걱정할 필요가 없다. 자연키 UNIQUE 제약이 최후 방어선이다.
 * (같은 id를 다시 저장하면 INSERT가 아니라 UPDATE가 된다는 이 전제는
 * `HistoricalFxRateJpaRepositoryTest`가 H2에서 직접 고정해 둔다.)
 *
 * **다년 범위는 나눠 돌려야 한다 — 편의가 아니라 필수다.**
 * `id`가 할당식이고 `@Version`도 없어서 Spring Data가 모든 행을 `em.merge`로 보낸다.
 * merge는 행마다 SELECT를 한 번씩 내고, `batch_size: 500`은 쓰기만 묶지 이 SELECT들은 묶지 않는다.
 * 일별 10년치 ~2,600행이면 순차 왕복 2,600회다 — 무료 플랜 Neon에서 수십 초 커넥션 점유이고,
 * 아래에서 트랜잭션을 붙이지 않은 이유("커넥션을 오래 쥐지 않는다")를 스스로 무너뜨린다.
 * 1~2년씩 끊어 호출하면 이 왕복이 호출 사이로 분산된다. (`Persistable` 도입은 이 작업 범위 밖이다.)
 *
 * **[backfill]에 `@Transactional`을 붙이지 않는다 — 의도된 것이다.**
 * 이 메서드는 최대 30초 블로킹인 HTTP 호출을 품고 있어서, 트랜잭션을 열면 그 대기 내내
 * Neon 커넥션을 쥔 채로 앉아 있게 된다(커넥션 풀이 작고 Neon은 유휴 커넥션에 인색하다).
 * 배치 원자성은 `saveAll`이 Spring Data 리포지토리 레벨에서 이미 트랜잭션이라 확보된다 —
 * 한 번의 `saveAll`이 전부 들어가거나 전부 안 들어간다.
 * 트랜잭션을 HTTP 밖으로 빼겠다고 빈을 쪼개지도 않는다: 자기호출은 프록시를 타지 않아
 * 조용히 무효가 되고(AF-90에서 `@Async` 자기호출로 이미 겪었다), 여기서는 애초에 안 붙이면 되는 문제다.
 */
@Service
class FxRateBackfillService(
    private val sources: List<HistoricalRateSource>,
    private val repository: HistoricalFxRateJpaRepository,
    private val fxConverter: UnifiedAssetFxConverterAdapter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun backfill(currency: String, from: LocalDate, to: LocalDate): BackfillSummary {
        val code = currency.trim().uppercase()
        require(!from.isAfter(to)) { "from은 to보다 이후일 수 없습니다: $from > $to" }

        val rateSource = sources.firstOrNull { it.supports(code) }
            ?: throw IllegalArgumentException("과거 환율 소스가 없는 통화입니다: $code")

        val result = rateSource.fetch(code, from, to)

        // 빈 응답으로 기존 값을 덮지 않는다 — 통계표 코드가 틀려도 0건이 온다
        check(result.rates.isNotEmpty()) {
            "${rateSource.sourceName} 응답 0건 — 기존 값을 덮지 않고 중단합니다 (currency=$code $from~$to)"
        }

        // 요청 범위 밖 날짜를 먼저 걷어낸다. 파서는 날짜만 파싱되면 통과시키므로
        // 소스가 범위 밖 날짜를 섞어 줄 수 있는데, 아래 existing 조회는 from..to로 한정된다 —
        // 그 날짜 행이 이미 테이블에 있으면(중첩 백필 뒤라면 충분히 있다) existing에서 안 잡혀
        // 새 UUID로 INSERT가 나가고 uk_fx_rate_daily가 배치 전체를 죽인다. 재실행해도 똑같이 실패하고
        // 운영자에게는 불투명한 제약 위반만 남는다. 파서와 같은 규율로 버리되 센다.
        val inRange = result.rates.filter { it.baseDate in from..to }
        val outOfRange = result.rates.size - inRange.size
        if (outOfRange > 0) {
            log.warn(
                "[Backfill] 요청 범위 밖 {}건 제거 source={} currency={} {}~{}",
                outOfRange, rateSource.sourceName, code, from, to,
            )
        }

        val rates = dedupe(inRange, rateSource.sourceName, code, from, to)
        val duplicates = inRange.size - rates.size

        // 위 0건 방어와 같은 이유다. 범위 밖 행만 온 경우가 여기 걸린다 —
        // 막지 않으면 아래 min()/max()가 빈 집합에서 터져 원인이 안 보이는 예외가 된다.
        check(rates.isNotEmpty()) {
            "${rateSource.sourceName} 응답에 요청 범위 안의 행이 없습니다 — 기존 값을 덮지 않고 중단합니다 " +
                "(currency=$code $from~$to, 범위 밖 ${outOfRange}건)"
        }

        val existing = repository.findAllByCurrencyAndBaseDateBetween(code, from, to)
            .associateBy { it.baseDate }

        var inserted = 0
        var updated = 0
        var unchanged = 0

        val rows = rates.values.map { rate ->
            val prior = existing[rate.baseDate]
                ?: return@map HistoricalFxRateEntity(
                    id = UUID.randomUUID(),
                    baseDate = rate.baseDate,
                    currency = code,
                    rateKrw = rate.rateKrw,
                    source = rateSource.sourceName,
                    createdAt = LocalDateTime.now(),
                ).also { inserted++ }

            // 반드시 덮기 전에 센다. compareTo로 비교하는 이유는 스케일이 달라도 같은 값이기 때문이다
            // (1385.5와 1385.500000은 equals로는 다르다).
            if (prior.rateKrw.compareTo(rate.rateKrw) == 0) unchanged++ else updated++
            prior.apply {
                rateKrw = rate.rateKrw
                source = rateSource.sourceName
            }
        }
        repository.saveAll(rows)

        // 살아있는 프로세스에 API로 때리는 경로라, 정정된 값이 캐시에 가려지면 안 된다.
        // 저장이 끝난 뒤에 비운다 — 순서를 뒤집으면 비우는 즉시 옛 값이 다시 들어올 창이 넓어진다.
        // 다만 이 순서로도 창이 닫히지는 않는다: 이미 query()를 마치고 밀려나 있던 스레드가
        // invalidate() 이후에 캐시에 옛 값을 써 넣을 수 있다. 세대 카운터로 닫을 수는 있지만
        // 어드민이 수동으로 돌리는 경로라 그 복잡도를 들일 값이 없다고 판단했다 —
        // 남은 창은 다음 백필이나 재시작으로 해소된다.
        fxConverter.invalidate()

        val summary = BackfillSummary(
            currency = code, from = from, to = to,
            saved = rows.size,
            inserted = inserted, updated = updated, unchanged = unchanged,
            skipped = result.skipped, duplicates = duplicates, outOfRange = outOfRange,
            firstDate = rates.keys.min(),
            lastDate = rates.keys.max(),
        )
        log.info("[Backfill] 완료 source={} {}", rateSource.sourceName, summary)
        return summary
    }

    /**
     * 같은 날짜를 하나로 접는다. 소스는 중복을 걸러내지 않을 수 있으므로(ECOS 파서는
     * `EcosResponseParserTest`가 그 동작을 고정해 뒀다) 그대로 저장하면 `(base_date, currency)`
     * UNIQUE 제약에 걸려 배치 전체가 깨진다.
     *
     * **마지막 값을 택하는 데 근거는 없다.** 소스가 중복 순서의 의미론을 문서화한 적이 없어
     * "뒤에 온 것이 정정치"라는 건 추측이다. 그래도 임의로 하나를 고르는 쪽이 맞다 —
     * 중복 하나 때문에 2,600행을 통째로 걷어차는 건 과하다.
     *
     * 대신 **값이 실제로 갈리는 중복만** warn으로 올린다. 같은 값이 두 번 온 건 무해하고,
     * 값이 다른 중복이야말로 "어느 쪽이 맞나"를 사람이 봐야 하는 이상 신호다.
     */
    private fun dedupe(
        rates: List<DailyRate>,
        sourceName: String,
        code: String,
        from: LocalDate,
        to: LocalDate,
    ): Map<LocalDate, DailyRate> {
        val deduped = LinkedHashMap<LocalDate, DailyRate>(rates.size)
        var conflicting = 0

        rates.forEach { rate ->
            val prior = deduped.put(rate.baseDate, rate)
            if (prior != null && prior.rateKrw.compareTo(rate.rateKrw) != 0) conflicting++
        }

        val removed = rates.size - deduped.size
        when {
            conflicting > 0 -> log.warn(
                "[Backfill] 값이 다른 중복 날짜 {}건 — 마지막 값을 취한다 (전체 중복 {}건) source={} currency={} {}~{}",
                conflicting, removed, sourceName, code, from, to,
            )
            removed > 0 -> log.info(
                "[Backfill] 같은 값 중복 {}건 제거 source={} currency={} {}~{}",
                removed, sourceName, code, from, to,
            )
        }
        return deduped
    }
}
