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
 * 금리 한 건 (AF-102).
 *
 * **키가 `(지표코드, 기준일)`인 이유**: 지수는 장중에 값이 변해 스케줄 지점(슬롯)이 키였지만,
 * 금리는 공표 기관이 확정한 하루 한 값이고 ECOS 응답이 기준일(`TIME`)을 직접 준다.
 * 슬롯을 넣으면 같은 값이 슬롯 수만큼 복제된다.
 *
 * **전일대비(bp)와 스프레드는 담지 않는다.** 파생값이라 원본이 정정될 때 같이 안 고쳐져
 * 화석이 된다 — 그리고 ECOS는 정정한다. 조회 시 직전 행과 비교해 계산한다.
 *
 * 컬럼 이름이 `value`가 아니라 `rate_value`인 이유: `VALUE`는 SQL 예약어 계열이라
 * DB·드라이버마다 인용부호 요구가 갈린다. 값어치 없는 위험이다.
 *
 * `rateValue`·`collectedAt`이 var인 이유: 같은 날을 다시 수집하면 값만 덮기 때문.
 */
@Entity
@Table(
    name = "market_rate",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_market_rate", columnNames = ["rate_code", "quote_date"]),
    ],
)
class MarketRateEntity(
    @Id val id: UUID,
    @Column(name = "rate_code", nullable = false, length = 20) val rateCode: String,
    @Column(name = "quote_date", nullable = false) val quoteDate: LocalDate,
    /** 연 %. 마이너스 금리가 실재하므로 부호를 제한하지 않는다 */
    @Column(name = "rate_value", nullable = false, precision = 9, scale = 4) var rateValue: BigDecimal,
    @Column(name = "source", nullable = false, length = 20) val source: String,
    @Column(name = "collected_at", nullable = false) var collectedAt: LocalDateTime,
)
