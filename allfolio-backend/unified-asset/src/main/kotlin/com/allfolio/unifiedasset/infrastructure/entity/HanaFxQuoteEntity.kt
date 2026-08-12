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
 * 하나은행 회차별 고시환율 (AF-99).
 *
 * ECOS 일별 확정 종가([HistoricalFxRateEntity])와 성격이 다르다 — 저쪽은 하루 한 건,
 * 이쪽은 하루 안 여러 회차 × 통화별 6개 환율이다. 섞지 않는다.
 *
 * baseDate는 **하나은행이 응답에 담아 준 기준일**이지 우리가 요청한 조회일자가 아니다.
 * 주말·공휴일에 조회하면 직전 영업일 고시가 돌아오므로, 조회일자를 키로 쓰면
 * 연휴 사흘 동안 같은 고시가 세 번 들어간다.
 *
 * 환율 필드가 var인 이유: 같은 회차를 다시 수집하면 값만 덮기 때문.
 */
@Entity
@Table(
    name = "hana_fx_quote",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_hana_fx_quote", columnNames = ["base_date", "round_no", "currency"]),
    ],
)
class HanaFxQuoteEntity(
    @Id val id: UUID,
    @Column(name = "base_date", nullable = false) val baseDate: LocalDate,
    @Column(name = "round_no", nullable = false) val roundNo: Int,
    @Column(name = "currency", nullable = false, length = 10) val currency: String,
    @Column(name = "base_rate", nullable = false, precision = 18, scale = 4) var baseRate: BigDecimal,
    @Column(name = "cash_buy", precision = 18, scale = 4) var cashBuy: BigDecimal?,
    @Column(name = "cash_sell", precision = 18, scale = 4) var cashSell: BigDecimal?,
    @Column(name = "remit_send", precision = 18, scale = 4) var remitSend: BigDecimal?,
    @Column(name = "remit_receive", precision = 18, scale = 4) var remitReceive: BigDecimal?,
    @Column(name = "collected_at", nullable = false) val collectedAt: LocalDateTime,
)
