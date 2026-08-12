package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 지수 시세 한 건 (AF-101).
 *
 * **키가 `(지수코드, 거래일, 슬롯)`인 이유**: KIS 지수 응답에는 기준시각이 없다.
 * 조회 시각을 키로 쓰면 GitHub cron 지연(5~30분)이 그대로 데이터에 새겨져,
 * 15:50에 돈 날과 16:20에 돈 날이 서로 다른 행이 되고 같은 종가가 두 건으로 남는다.
 * 스케줄 지점을 키에 넣으면 언제 돌든 그날 그 지점의 한 건으로 수렴한다.
 *
 * 값 필드가 var인 이유: 같은 슬롯을 다시 수집하면 값만 덮기 때문.
 */
@Entity
@Table(
    name = "market_index_quote",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_market_index_quote",
            columnNames = ["index_code", "trade_date", "slot"],
        ),
    ],
)
class MarketIndexQuoteEntity(
    @Id val id: UUID,
    @Column(name = "index_code", nullable = false, length = 20) val indexCode: String,
    @Column(name = "trade_date", nullable = false) val tradeDate: LocalDate,
    @Column(name = "slot", nullable = false, length = 10) val slot: String,
    @Column(name = "price", nullable = false, precision = 18, scale = 4) var price: BigDecimal,
    @Column(name = "prev_close", nullable = false, precision = 18, scale = 4) var prevClose: BigDecimal,
    @Column(name = "change_value", nullable = false, precision = 18, scale = 4) var changeValue: BigDecimal,
    @Column(name = "change_rate", nullable = false, precision = 9, scale = 4) var changeRate: BigDecimal,
    @Column(name = "prev_close_date") var prevCloseDate: LocalDate?,
    @Column(name = "market_status", nullable = false, length = 10) var marketStatus: String,
    @Column(name = "source", nullable = false, length = 20) val source: String,
    @Column(name = "collected_at", nullable = false) var collectedAt: LocalDateTime,
)
