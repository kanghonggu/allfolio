package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketCommodityQuoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface MarketCommodityQuoteJpaRepository : JpaRepository<MarketCommodityQuoteEntity, UUID> {

    /**
     * 여러 종목의 가장 최근 한 건씩을 **쿼리 한 번으로** 준다. 시장 화면(AF-108)이 쓴다.
     *
     * **최신 판정 규칙이 이 JPQL 안에만 있다.** 코틀린에서 정렬해 고르면 같은 규칙이 두 벌이 되고,
     * 한쪽만 고쳐지는 순간 화면이 묵은 값을 최신이라고 말한다
     * (`MarketIndexQuoteJpaRepository.findLatestByCodes`와 같은 자리·같은 이유다.
     *  여기에 슬롯 분기가 없는 것은 원자재가 하루/한 달에 한 값이라 OPEN/MID/CLOSE가 없어서다).
     *
     * **조회 창을 두지 않는다.** 금리 조회는 최근 30일을 긁어 직전 값까지 함께 만들지만,
     * 원자재는 전일대비가 수집 시점에 이미 행에 저장돼 있어 최신 한 행이면 화면이 완성된다.
     * 게다가 월간 계열(FRED/IMF)은 최신 관측이 두 달 넘게 묵는 것이 정상이라
     * (실측: 2026-08-16 시점 최신 관측이 2026-06-01) 창을 두면 그 13종이 통째로 사라진다.
     *
     * **`DISTINCT ON`이나 윈도 함수를 쓰지 말 것** — 이 리포지터리 테스트는 H2에서 돈다.
     * 벤더 전용 문법으로 바꾸면 최신 판정 검증이 통째로 테스트에서 빠진다.
     * 그래서 이식 가능한 상관 서브쿼리 `NOT EXISTS`("나보다 최신인 행이 없다")로 짰다.
     *
     * 수집된 적 없는 코드는 결과에 **그냥 없다.** 호출부가 설정 코드로 매핑해 빠진 것을 가려낸다.
     */
    @Query(
        """
        SELECT q FROM MarketCommodityQuoteEntity q
        WHERE q.code IN :codes
          AND NOT EXISTS (
            SELECT 1 FROM MarketCommodityQuoteEntity o
            WHERE o.code = q.code AND o.tradeDate > q.tradeDate
          )
        """,
    )
    fun findLatestByCodes(@Param("codes") codes: Collection<String>): List<MarketCommodityQuoteEntity>

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
