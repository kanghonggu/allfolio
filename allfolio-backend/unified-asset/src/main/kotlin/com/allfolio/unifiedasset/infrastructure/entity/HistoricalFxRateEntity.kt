package com.allfolio.unifiedasset.infrastructure.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 일별 확정 환율 (AF-100).
 *
 * 자산 평가에 쓰는 Redis 현재 환율과 별개다 — 이쪽은 "그날 얼마였나" 전용이고,
 * 현금흐름(cash_flow.amount_krw)을 발생일 환율로 환산하는 데 쓴다.
 *
 * rateKrw는 항상 통화 1단위당 KRW. ECOS는 JPY를 100엔 기준으로 주므로
 * 수집기(FxRateBackfillService)가 1단위로 정규화해서 넣는다.
 *
 * rateKrw·source가 var인 이유: 백필 재실행 시 같은 (baseDate, currency) 행을
 * 덮어쓰기 때문. 자연키가 UNIQUE로 걸려 있어 중복은 생기지 않는다.
 */
@Entity
@Table(name = "fx_rate_daily")
class HistoricalFxRateEntity(
    @Id val id: UUID,
    @Column(name = "base_date", nullable = false) val baseDate: LocalDate,
    @Column(name = "currency", nullable = false, length = 10) val currency: String,
    @Column(name = "rate_krw", nullable = false, precision = 18, scale = 6) var rateKrw: BigDecimal,
    @Column(name = "source", nullable = false, length = 20) var source: String,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime,
)
