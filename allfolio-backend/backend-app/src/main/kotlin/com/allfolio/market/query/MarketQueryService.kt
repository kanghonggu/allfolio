package com.allfolio.market.query

import com.allfolio.market.index.MarketIndexProperties
import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

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
// 커넥션을 따로 빌렸다 돌려준다. 환율 4번이 여기 붙었고 금리 시리즈당 1번이 Task 3에서 더 붙는다.
// 스냅샷 조립 중간에 수집 크론이 커밋해도 한 시점으로 읽는 효과도 같이 얻는다 —
// 환율은 네 쿼리에 걸쳐 읽으므로 그사이 새 회차가 커밋되면 머리와 본문이 다른 회차가 될 수 있다.
// (기존 관례: GetDashboardUseCase)
@Transactional(readOnly = true)
class MarketQueryService(
    private val indexRepository: MarketIndexQuoteJpaRepository,
    private val indexProperties: MarketIndexProperties,
    private val fxRepository: HanaFxQuoteJpaRepository,
) {
    fun snapshot(): MarketSnapshot {
        val codes = indexProperties.domestic.map { it.code } + indexProperties.overseas.map { it.code }
        val latestByCode = indexRepository.findLatestByCodes(codes).associateBy { it.indexCode }

        // 국내·해외를 코드로 다시 가른다. 설정 순서를 그대로 유지해야 화면 줄 순서가 안 흔들린다.
        // 수집된 적 없는 지수는 맵에 없어 빠진다 — 0으로 채우면 화면이 그걸 진짜 값으로 보여준다.
        return MarketSnapshot(
            domestic = indexProperties.domestic.mapNotNull { latestByCode[it.code]?.toView() },
            overseas = indexProperties.overseas.mapNotNull { latestByCode[it.code]?.toView() },
            fx = fxSnapshot(),
            flags = MarketFlags(indicesEnabled = true),
        )
    }

    /**
     * 최신 회차 전 통화 + 직전 기준일 대비.
     *
     * 쿼리 4회로 끝난다: 최신 한 건 → 그 회차 전량 → 직전 기준일 한 건 → 그 회차 전량.
     * 통화가 58종이라 통화마다 최신을 찾으면 왕복이 58번이 되고,
     * 통화별로 회차가 갈려 한 화면에 서로 다른 회차가 섞인다.
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
                val before = prior[quote.currency]?.baseRate
                FxQuoteView(
                    currency = quote.currency,
                    baseRate = quote.baseRate,
                    cashBuy = quote.cashBuy,
                    cashSell = quote.cashSell,
                    remitSend = quote.remitSend,
                    remitReceive = quote.remitReceive,
                    // 어제 없던 통화는 0이 아니라 null이다 — 0은 "안 움직였다"는 뜻이 된다
                    change = before?.let { quote.baseRate - it },
                    // 0으로 나누기를 막는다. 하나은행이 0을 고시할 일은 없지만 파싱이 어긋나면 들어올 수 있다
                    changeRate = before?.takeIf { it.signum() != 0 }?.let {
                        (quote.baseRate - it).divide(it, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
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
