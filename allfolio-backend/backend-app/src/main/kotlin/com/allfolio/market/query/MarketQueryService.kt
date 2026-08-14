package com.allfolio.market.query

import com.allfolio.market.index.MarketIndexProperties
import com.allfolio.market.rate.MarketRateProperties
import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.MarketRateJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * 시장 화면용 조회 (AF-104).
 *
 * **읽기 전용이다.** 환율·금리의 전일대비·bp 변동은 저장하지 않기로 한 값이라
 * (원본이 정정되면 파생값은 같이 안 고쳐져 화석이 된다 — AF-102 설계 판단) 조회 시점에 계산한다.
 * **지수는 다르다** — 등락을 KIS가 주고 우리는 다시 계산하지 않는다. 이유는 [toView] 참조.
 *
 * 지수는 국내·해외를 합쳐 **쿼리 한 번**으로 긁는다. 슬롯 순서 규칙(CLOSE > MID > OPEN)은
 * [MarketIndexQuoteJpaRepository.findLatestByCodes]의 JPQL 한 곳에만 있고 여기로 내려오지 않는다.
 * 종목마다 부르면 원격 Neon Postgres 왕복이 지수 수(현재 국내 5 + 해외 9)만큼 난다.
 */
@Service
// open-in-view가 꺼져 있고 커넥션 풀이 10이라, 트랜잭션이 없으면 리포지터리 호출마다
// 커넥션을 따로 빌렸다 돌려준다. 스냅샷 한 장을 조립하는 동안 커넥션 하나로 끝내는 것,
// **이 커넥션 절약이 이 애너테이션의 이유 전부다.**
// 구간별 쿼리 횟수는 여기 적지 않고 각 구간의 KDoc에 둔다 — 여기 적어 두면 어느 구간을 고치든
// 이 주석이 거짓이 되고, 실제로 두 번 그랬다.
// **시점 일관성은 여기서 얻지 못한다** — 격리 수준은 READ COMMITTED 그대로다(readOnly는 격리를
// 안 바꾼다). 쿼리마다 스냅샷이 새로 잡힌다. 그런데도 환율이 한 회차로 모이는 이유는 [fxSnapshot] 참조.
// (기존 관례: GetDashboardUseCase)
@Transactional(readOnly = true)
class MarketQueryService(
    private val indexRepository: MarketIndexQuoteJpaRepository,
    private val indexProperties: MarketIndexProperties,
    private val fxRepository: HanaFxQuoteJpaRepository,
    private val rateRepository: MarketRateJpaRepository,
    private val rateProperties: MarketRateProperties,
    private val queryProperties: MarketQueryProperties,
) {
    companion object {
        /**
         * 금리 조회 창. 직전 값 하나만 있으면 되지만 넉넉히 잡는다 —
         * 연휴가 길면 직전 영업일이 2주 밖일 수 있고, 10종 x 30일이면 300행이라 비용이 없다.
         */
        private const val RATE_LOOKBACK_DAYS = 30L

        /** 1%p = 100bp */
        private val BP_PER_PERCENT = BigDecimal(100)

        /**
         * **`LocalDate.now()`를 그냥 쓰지 않는다.** Render 컨테이너는 UTC라 KST 새벽에 하루 전으로
         * 밀린다 — 조회 창의 상한이 오늘을 놓쳐 그날 수집분이 통째로 안 보인다.
         */
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    fun snapshot(): MarketSnapshot {
        // 플래그가 off면 **읽지도 않는다.** 읽어 두고 응답에서만 빼도 지금 당장의 결과는 같지만,
        // 재배포를 막는 건 바이트가 프로세스를 안 떠나는 것이라 조립부를 손대는 순간 다시 새어 나간다.
        // 이 확인을 함수 맨 앞의 조기 반환으로 옮기지 말 것 — 지수 두 탭을 끄려다 환율·금리까지
        // 같이 사라진다. 플래그가 지우는 건 지수뿐이다([MarketQueryProperties]).
        val indicesOn = queryProperties.indicesEnabled
        // off면 맵이 아니라 null이다. `emptyMap()`을 두면 아래 두 줄이 각자 다시 플래그를 봐야 하고,
        // 그중 하나만 고치면 한 탭만 새어 나간다. null로 두면 "안 읽었으면 안 싣는다"가 타입으로 강제된다.
        val latestByCode = if (indicesOn) latestIndexQuotes() else null

        // 국내·해외를 코드로 다시 가른다. 설정 순서를 그대로 유지해야 화면 줄 순서가 안 흔들린다.
        // 수집된 적 없는 지수는 맵에 없어 빠진다 — 0으로 채우면 화면이 그걸 진짜 값으로 보여준다.
        // **off는 빈 리스트가 아니라 null이다.** 빈 리스트는 "조회했는데 데이터가 없다"는 뜻이라
        // 뜻이 다르고, 프런트가 그걸 렌더해도 이미 늦다(MarketSnapshot KDoc).
        // **flags는 `latestByCode != null`로 유도하지 말 것.** 플래그는 조회 결과가 아니라 설정을
        // 보고해야 한다 — 유도해 두면 조회를 건너뛰는 최적화(캐시 등)가 들어오는 날
        // 설정은 on인데 응답은 off라고 말한다.
        return MarketSnapshot(
            domestic = latestByCode?.let { byCode -> indexProperties.domestic.mapNotNull { byCode[it.code]?.toView() } },
            overseas = latestByCode?.let { byCode -> indexProperties.overseas.mapNotNull { byCode[it.code]?.toView() } },
            fx = fxSnapshot(),
            rates = rateViews(),
            flags = MarketFlags(indicesEnabled = indicesOn),
        )
    }

    /** 국내·해외를 합쳐 쿼리 한 번. 왜 종목마다 안 부르는지는 클래스 KDoc 참조 */
    private fun latestIndexQuotes(): Map<String, MarketIndexQuoteEntity> {
        val codes = indexProperties.domestic.map { it.code } + indexProperties.overseas.map { it.code }
        return indexRepository.findLatestByCodes(codes).associateBy { it.indexCode }
    }

    /**
     * 설정에 있는 전 지표의 최근 [RATE_LOOKBACK_DAYS]일을 **쿼리 한 번으로** 긁어,
     * 지표마다 마지막 둘로 값과 bp 변동을 만든다.
     * 지표마다 부르면 원격 Neon 왕복이 지표 수(운영 설정 한국 6 + 미국 4 = 10종)만큼 난다.
     *
     * **출력은 설정 순서다.** 묶음 결과를 `groupBy`한 맵을 돌면 안 된다 — 그 맵의 순서는 DB가
     * 행을 준 순서라 임의이고, "설정엔 있는데 수집된 적 없는 지표는 빠진다"는 뜻도 같이 사라진다.
     * 기준일 순으로 정렬해도 안 된다 — 공표가 늦는 기준금리 때문에 줄 순서가 날마다 뒤바뀐다.
     */
    private fun rateViews(): List<RateView> {
        // **`ecos`만 읽지 말 것.** 설정이 소스별 목록으로 갈려 있어서, 한쪽만 열거하면 다른 쪽
        // 종목은 조회 대상에 아예 안 들어간다 — 수집은 되고 DB에도 쌓이는데 화면에만 없고,
        // 오류도 로그도 안 난다. 그래서 합치는 일은 [MarketRateProperties.allCodes] 한 곳에만 둔다
        val codes = rateProperties.allCodes
        // 빈 목록을 그대로 넘기면 `IN ()`이라 벤더에 따라 문법 오류다. 설정이 빈 건 그 자체로 사고지만
        // 화면이 SQL 오류로 죽을 일은 아니다. 지수 쪽 findLatestByCodes도 똑같이 노출돼 있다.
        if (codes.isEmpty()) return emptyList()

        val to = LocalDate.now(KST)
        val from = to.minusDays(RATE_LOOKBACK_DAYS)
        // 리포지터리는 순서를 보장하지 않는다. 정렬 없이 마지막 둘을 집으면 최신도 직전도 아닐 수 있다.
        // 정렬은 묶기 **전에** 한 번만 한다 — groupBy가 그룹 안의 등장 순서를 지키므로 결과는 같고,
        // 정렬이 지표 수만큼이 아니라 한 번이다.
        val rowsByCode = rateRepository
            .findByRateCodeInAndQuoteDateBetween(codes, from, to)
            .sortedBy { it.quoteDate }
            .groupBy { it.rateCode }

        return codes.mapNotNull { code ->
            // 수집된 적 없는 지표는 빠진다 — 0으로 채우면 화면이 그걸 진짜 금리로 보여준다.
            // **30일 넘게 안 들어온 지표도 같이 빠진다.** 무료 플랜에서 평일 크론이 죽거나
            // ECOS가 통계표 코드를 내리면 실제로 그렇게 된다. 묵은 값을 정직한 기준일과 함께
            // 보여주는 쪽이 아니라 빼는 쪽을 골랐다 — 묵은 값을 진짜처럼 보여주느니 뺀다.
            val rows = rowsByCode[code].orEmpty()
            val latest = rows.lastOrNull() ?: return@mapNotNull null
            // "끝에서 두 번째 행"이 아니라 "기준일이 더 이른 마지막 행"이다. uk_market_rate
            // (rate_code, quote_date) 덕에 오늘은 둘이 같지만, 같은 날짜가 두 벌 들어오면
            // 끝에서 두 번째는 같은 날끼리의 차(대개 0)가 되어 화면에 "안 움직였다"로 찍힌다.
            // 제약조건에 말없이 기대지 않도록 조건으로 쓴다.
            val prior = rows.lastOrNull { it.quoteDate < latest.quoteDate }
            RateView(
                // 행이 아니라 설정에서 가져온다 — 묶음 키와 어긋날 수 없는 쪽이다
                code = code,
                value = latest.rateValue,
                quoteDate = latest.quoteDate,
                // %p가 아니라 bp로 낸다(1%p = 100bp). 화면이 다시 100을 곱하지 않는다.
                // 비교할 직전 값이 없으면 0이 아니라 null이다 — 0은 "안 움직였다"는 뜻이 된다.
                // **스케일을 2로 못 박는다.** 빼기·곱하기는 스케일을 컬럼에서 물려받아 1bp가
                // `-1.0000`으로 직렬화되고(Jackson은 BigDecimal 스케일을 보존한다), market_rate의
                // precision/scale을 누가 고치면 API 숫자 형식이 조용히 따라 바뀐다.
                // 소스 해상도가 0.0001%p = 0.01bp라 자리 손실은 없다.
                changeBp = prior?.let {
                    ((latest.rateValue - it.rateValue) * BP_PER_PERCENT).setScale(2, RoundingMode.HALF_UP)
                },
            )
        }
    }

    /**
     * 최신 회차 전 통화 + 직전 기준일 대비.
     *
     * 쿼리 4회로 끝난다: 최신 한 건 → 그 회차 전량 → 직전 기준일 한 건 → 그 회차 전량.
     * 통화가 58종이라 통화마다 최신을 찾으면 왕복이 58번이 되고,
     * 통화별로 회차가 갈려 한 화면에 서로 다른 회차가 섞인다.
     *
     * 트랜잭션이 시점 일관성을 주지 않는데도(클래스 애너테이션 주석 참조) 응답이 한 회차로 모이는
     * 이유는 2·4번째 쿼리를 1·3번째가 준 (기준일, 회차)로 걸기 때문이다. 그사이 수집 크론이 새 회차를
     * 커밋하면 한 회차 묵은 응답이 나갈 뿐, 응답 안에서 회차가 어긋나지는 않는다.
     */
    private fun fxSnapshot(): FxSnapshot? {
        val latest = fxRepository.findTopByOrderByBaseDateDescRoundNoDesc() ?: return null
        val current = fxRepository.findAllByBaseDateAndRoundNo(latest.baseDate, latest.roundNo)

        // 직전 "기준일"이다. 직전 회차와 비교하면 전일대비가 아니라 장중 변동이 된다
        val priorHead = fxRepository.findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(latest.baseDate)
        val prior = priorHead
            ?.let { fxRepository.findAllByBaseDateAndRoundNo(it.baseDate, it.roundNo) }
            ?.associateBy { it.currency }
            ?: emptyMap()

        return FxSnapshot(
            baseDate = latest.baseDate,
            roundNo = latest.roundNo,
            collectedAt = latest.collectedAt,
            quotes = current.map { quote ->
                // 직전 값이 0이면 "직전이 없다"로 함께 다룬다 — change만 살려 두면 화면에 매매기준율만 한
                // 큰 폭이 등락률 없이 찍힌다. 하나은행이 0을 고시할 일은 없고 파서도 0 이하를 버리지만
                // (HanaFxParser.number의 `it > ZERO`), 파싱이 어긋나면 들어올 수 있어 두 값을 같은 조건에 건다.
                val before = prior[quote.currency]?.baseRate?.takeIf { it.signum() != 0 }
                FxQuoteView(
                    currency = quote.currency,
                    baseRate = quote.baseRate,
                    cashBuy = quote.cashBuy,
                    cashSell = quote.cashSell,
                    remitSend = quote.remitSend,
                    remitReceive = quote.remitReceive,
                    // 어제 없던 통화는 0이 아니라 null이다 — 0은 "안 움직였다"는 뜻이 된다
                    change = before?.let { quote.baseRate - it },
                    // 100을 먼저 곱하고 나눗셈에서 한 번만 반올림한다. 나눠서 반올림하고 곱한 뒤
                    // 다시 반올림하면 경계값에서 마지막 자리가 한 칸 밀린다 — 반올림은 한 번뿐이어야 한다
                    changeRate = before?.let {
                        (quote.baseRate - it).multiply(BigDecimal(100)).divide(it, 2, RoundingMode.HALF_UP)
                    },
                )
            }.sortedBy { it.currency },
        )
    }

    /**
     * 등락(`change`·`changeRate`)은 KIS가 준 값을 그대로 싣는다 —
     * **`price - prevClose`로 다시 계산하지 말 것.**
     *
     * 지수만 파생값을 안 만들어 일관성이 없어 보이지만 이유가 있다. 저장된 `prevClose`는 KIS가
     * 준 전일종가가 아니라 파서가 `price - change`로 **역산해 넣은 값**이다(`KisIndexParser`,
     * `KisOverseasIndexParser`). 그래서 재계산은 잘해야 같은 값을 되돌려 받는 항등식이고,
     * 해외 지수는 price가 일봉·change가 output1이라 두 출처가 갈리면 재계산값이 KIS 표시값과
     * 어긋난다. 그 어긋남은 수집 가드가 잡아야 할 신호지 화면이 조용히 덮어쓸 것이 아니다.
     * `changeRate`도 마찬가지다 — 우리가 나눠 만들면 반올림이 KIS와 달라진다.
     */
    private fun MarketIndexQuoteEntity.toView() = IndexQuoteView(
        code = indexCode,
        price = price,
        change = changeValue,
        changeRate = changeRate,
        marketStatus = marketStatus,
        tradeDate = tradeDate,
        slot = slot,
    )
}
