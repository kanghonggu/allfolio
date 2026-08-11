package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * @param saved   저장(신규+갱신)된 행 수
 * @param skipped 값·날짜가 이상해 버린 행 수 — 조용히 삼키지 않는다
 */
data class BackfillSummary(
    val currency: String,
    val from: LocalDate,
    val to: LocalDate,
    val saved: Int,
    val skipped: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
)

/**
 * ECOS 과거 환율 백필 (AF-100).
 *
 * 재실행이 안전해야 긴 기간을 나눠 돌릴 수 있으므로, 기존 행을 한 번에 읽어
 * 같은 (통화, 기준일)이면 값만 덮는다. 네이티브 UPSERT를 쓰지 않는 이유는
 * H2(테스트)와 Postgres(운영) 문법이 갈리기 때문이고, 어드민이 수동으로 한 번씩
 * 돌리는 경로라 동시 실행 경합을 걱정할 필요가 없다. 자연키 UNIQUE 제약이 최후 방어선이다.
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
    private val client: EcosApiClient,
    private val repository: HistoricalFxRateJpaRepository,
    private val properties: EcosProperties,
    private val fxConverter: UnifiedAssetFxConverterAdapter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val SOURCE = "ECOS"
        private const val SCALE = 6
    }

    fun backfill(currency: String, from: LocalDate, to: LocalDate): BackfillSummary {
        val code = currency.trim().uppercase()
        val series = seriesOf(code)
            ?: throw IllegalArgumentException("ECOS 시계열 설정이 없는 통화입니다: $code")
        require(!from.isAfter(to)) { "from은 to보다 이후일 수 없습니다: $from > $to" }

        // 예외는 그대로 올려보낸다 — 호출자(어드민 엔드포인트)가 상태 코드로 옮긴다.
        // 스택을 통째로 찍지 않는 이유: EcosStatisticSearchClient가 인증키(URL 경로에 있다)를
        // 흘리지 않도록 예외를 정제해 두는데, 여기서 원본 스택을 찍으면 그 방어가 무의미해질 수 있다.
        val result = try {
            client.fetchDailyRates(series.statCode, series.itemCode, from, to)
        } catch (e: Exception) {
            // INFO-200("해당 기간 데이터 없음")도 여기로 온다. 별도로 가르지 않는 이유는
            // 결과가 같기 때문이다 — 어느 쪽이든 한 행도 쓰지 않고 중단한다.
            // 장애와의 구분은 EcosApiException.code에 이미 실려 있고, 그걸 상태 코드로 옮기는 건
            // 호출자 몫이다. 여기서 갈아끼우면 그 code가 사라진다.
            log.warn(
                "[ECOS] 백필 실패 currency={} {}~{} reason={} code={}",
                code, from, to, e.javaClass.simpleName, (e as? EcosApiException)?.code,
            )
            throw e
        }

        // 빈 응답으로 기존 값을 덮지 않는다 — 통계표 코드가 틀려도 0건이 온다
        check(result.rates.isNotEmpty()) {
            "ECOS 응답 0건 — 기존 값을 덮지 않고 중단합니다 (currency=$code $from~$to)"
        }

        // 파서는 중복 날짜를 걸러내지 않는다. 그대로 저장하면 (base_date, currency) UNIQUE 제약에
        // 걸려 배치 전체가 깨지므로 여기서 접는다 — 같은 날짜가 여럿이면 뒤에 온 것이 정정치다.
        val rates = result.rates.associateBy { it.baseDate }
        val duplicates = result.rates.size - rates.size
        if (duplicates > 0) {
            log.warn("[ECOS] 중복 날짜 {}건 제거 currency={} {}~{}", duplicates, code, from, to)
        }

        val existing = repository.findAllByCurrencyAndBaseDateBetween(code, from, to)
            .associateBy { it.baseDate }

        val rows = rates.values.map { rate ->
            // 고시 단위를 1단위로 되돌린다 — JPY 100엔 고시가 그대로 들어가면 100배가 된다
            val normalized = rate.rateKrw.divide(series.unitDivisor, SCALE, RoundingMode.HALF_UP)
            existing[rate.baseDate]?.apply {
                rateKrw = normalized
                source = SOURCE
            } ?: HistoricalFxRateEntity(
                id = UUID.randomUUID(),
                baseDate = rate.baseDate,
                currency = code,
                rateKrw = normalized,
                source = SOURCE,
                createdAt = LocalDateTime.now(),
            )
        }
        repository.saveAll(rows)

        // 살아있는 프로세스에 API로 때리는 경로라, 정정된 값이 캐시에 가려지면 안 된다.
        // 저장이 끝난 뒤에 비운다 — 먼저 비우면 그 사이 조회가 옛 값을 다시 캐시에 넣는다.
        fxConverter.invalidate()

        val summary = BackfillSummary(
            currency = code, from = from, to = to,
            saved = rows.size, skipped = result.skipped,
            firstDate = rates.keys.min(),
            lastDate = rates.keys.max(),
        )
        log.info("[ECOS] 백필 완료 {}", summary)
        return summary
    }

    /**
     * 통화 설정을 대소문자 무관하게 찾는다.
     *
     * 맵 키는 YAML에 쓴 그대로 들어오는데, 환경변수로 주입하면(ECOS_SERIES_JPY_STAT_CODE)
     * relaxed binding이 `ecos.series.jpy.*`로 소문자화한다. 대문자만 보면 그때 "설정이 없는 통화"로
     * 오진하고, 그건 설정 문제로 위장한 코드 문제라 운영에서 가장 찾기 어려운 종류다.
     */
    private fun seriesOf(code: String): EcosProperties.Series? =
        properties.series.entries.firstOrNull { it.key.equals(code, ignoreCase = true) }?.value
}
