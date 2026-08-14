package com.allfolio.fx

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.RoundingMode
import java.time.LocalDate

/**
 * ECOS(한국은행) 과거 환율 소스 (AF-100).
 *
 * [FxRateBackfillService]에서 뽑아낸 것이라 동작은 그대로다. 옮겨 온 것은 셋이다:
 * 시계열 설정 조회 · 호출과 예외 로깅 · **고시 단위 정규화**.
 *
 * 정규화가 여기 있어야 하는 이유: `unitDivisor`는 ECOS가 JPY를 100엔 단위로 고시해서
 * 필요한 것이다. 서비스에 두면 Upbit 일봉에도 제수가 걸린다.
 */
@Component
class EcosHistoricalRateSource(
    private val client: EcosApiClient,
    private val properties: EcosProperties,
) : HistoricalRateSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val sourceName = "ECOS"

    companion object {
        private const val SCALE = 6
    }

    override fun supports(currency: String): Boolean = seriesOf(currency) != null

    override fun fetch(currency: String, from: LocalDate, to: LocalDate): SourceFetch {
        val series = seriesOf(currency)
            ?: throw IllegalArgumentException("ECOS 시계열 설정이 없는 통화입니다: $currency")

        // 예외는 그대로 올려보낸다 — 호출자(어드민 엔드포인트)가 상태 코드로 옮긴다.
        // 스택을 통째로 찍지 않는 이유: EcosStatisticSearchClient가 인증키(URL 경로에 있다)를
        // 흘리지 않도록 예외를 정제해 두는데, 여기서 원본 스택을 찍으면 그 방어가 무의미해질 수 있다.
        val result = try {
            client.fetch(
                EcosQuery(
                    statCode = series.statCode,
                    itemCode = series.itemCode,
                    cycle = EcosQuery.DAILY_CYCLE,
                    // 0원짜리 환율은 없다. 금리와 달리 부호로 거르는 게 맞다
                    valuePolicy = RateValuePolicy.POSITIVE,
                ),
                from,
                to,
            )
        } catch (e: Exception) {
            // INFO-200("해당 기간 데이터 없음")도 여기로 온다. 별도로 가르지 않는 이유는
            // 결과가 같기 때문이다 — 어느 쪽이든 한 행도 쓰지 않고 중단한다.
            // 장애와의 구분은 EcosApiException.code에 이미 실려 있고, 그걸 상태 코드로 옮기는 건
            // 호출자 몫이다. 여기서 갈아끼우면 그 code가 사라진다.
            log.warn(
                "[ECOS] 백필 실패 currency={} {}~{} reason={} code={}",
                currency, from, to, e.javaClass.simpleName, (e as? EcosApiException)?.code,
            )
            throw e
        }

        // 고시 단위를 1단위로 되돌린다 — JPY 100엔 고시가 그대로 들어가면 100배가 된다
        val rates = result.rates.map {
            DailyRate(it.baseDate, it.value.divide(series.unitDivisor, SCALE, RoundingMode.HALF_UP))
        }
        return SourceFetch(rates, result.skipped)
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
