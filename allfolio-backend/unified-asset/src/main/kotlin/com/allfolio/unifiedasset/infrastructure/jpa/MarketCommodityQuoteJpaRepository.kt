package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketCommodityQuoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface MarketCommodityQuoteJpaRepository : JpaRepository<MarketCommodityQuoteEntity, UUID> {

    /**
     * 그 종목의 구간 내 기존 행. 수집은 구간을 통째로 받아 덮으므로 한 번에 읽는다 —
     * 행마다 조회하면 창 하나에 수십 번의 왕복이 된다(Neon은 원격이다).
     * `MarketRateJpaRepository.findByRateCodeAndQuoteDateBetween`과 같은 자리다.
     */
    fun findByCodeAndTradeDateBetween(
        code: String,
        from: LocalDate,
        to: LocalDate,
    ): List<MarketCommodityQuoteEntity>

    /**
     * `trade_date`가 [before]보다 **앞선** 행 중 가장 최근 한 건. 전일대비 계산의 출발점이다.
     *
     * **날짜 산술로 "어제"를 찾으면 안 되는 이유가 이 메서드다.** 월간 계열의 직전 관측은
     * 한 달 전 1일이고 일간 계열도 연휴 뒤엔 나흘 전일 수 있다 — 하루를 빼서 조회하면
     * 월간은 영원히 `prev_close`가 null이 된다. "가장 최근 이전 행"만이 두 주기를 함께 만족한다.
     *
     * 수집 창 안쪽의 직전 값은 이 쿼리를 쓰지 않는다(창 안은 이미 [findByCodeAndTradeDateBetween]로
     * 통째로 읽었고, 그쪽이 이번 수집으로 덮일 새 값을 들고 있다). 이건 창 **바깥**의 출발점 전용이라
     * 종목당 한 번만 나간다.
     *
     * `(code, trade_date)` 유니크 인덱스가 그대로 쓰인다 — Postgres는 btree를 역방향으로도
     * 비용 없이 스캔하므로 별도 DESC 인덱스가 필요 없다.
     */
    fun findFirstByCodeAndTradeDateLessThanOrderByTradeDateDesc(
        code: String,
        before: LocalDate,
    ): MarketCommodityQuoteEntity?
}
