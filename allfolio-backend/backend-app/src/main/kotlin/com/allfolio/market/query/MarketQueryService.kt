package com.allfolio.market.query

import com.allfolio.market.index.MarketIndexProperties
import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import org.springframework.stereotype.Service

/**
 * 시장 화면용 조회 (AF-104).
 *
 * **읽기 전용이고 파생값은 여기서 만든다.** 전일대비·bp 변동은 저장하지 않기로 한 값이라
 * (원본이 정정되면 파생값은 같이 안 고쳐져 화석이 된다 — AF-102 설계 판단) 조회 시점에 계산한다.
 *
 * 지수는 종목마다 `findLatest`를 부른다. 한 쿼리로 긁으면 슬롯 순서 규칙을 JPQL과 코틀린에
 * 두 벌로 갖게 되고, 갈리는 순간 같은 날 개장 값이 종가보다 최신으로 잡힌다.
 * 이미 테스트된 규칙 한 벌을 재사용하는 쪽이 낫다고 봤다.
 */
@Service
class MarketQueryService(
    private val indexRepository: MarketIndexQuoteJpaRepository,
    private val indexProperties: MarketIndexProperties,
) {
    fun snapshot(): MarketSnapshot = MarketSnapshot(
        domestic = indexProperties.domestic.mapNotNull { view(it.code) },
        overseas = indexProperties.overseas.mapNotNull { view(it.code) },
        flags = MarketFlags(indicesEnabled = true),
    )

    /** 수집된 적 없는 지수는 null이다 — 0으로 채우면 화면이 그걸 진짜 값으로 보여준다 */
    private fun view(code: String): IndexQuoteView? =
        indexRepository.findLatest(code)?.toView()

    private fun MarketIndexQuoteEntity.toView() = IndexQuoteView(
        code = indexCode,
        price = price,
        change = changeValue,
        changeRate = changeRate,
        marketStatus = marketStatus,
        tradeDate = tradeDate,
        slot = slot,
        collectedAt = collectedAt,
    )
}
