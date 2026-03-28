package com.allfolio.broker.toss

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * Toss 증권 Open API DTOs
 *
 * Anti-Corruption Layer: 도메인으로 절대 유출하지 않음.
 * 참조: https://openapi.toss.im (토스증권 오픈 API 문서 기준)
 */

// ── OAuth2 ────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class TossTokenResponse(
    @JsonProperty("access_token")  val accessToken: String,
    @JsonProperty("refresh_token") val refreshToken: String?,
    @JsonProperty("token_type")    val tokenType: String,
    @JsonProperty("expires_in")    val expiresIn: Int,    // seconds
    val scope: String? = null,
)

// ── 계좌 ──────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class TossAccountDto(
    val accountNumber: String,
    val accountName: String,
    val currency: String,
    val balance: BigDecimal? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TossAccountListResponse(
    val accounts: List<TossAccountDto>,
)

// ── 체결 내역 ─────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class TossTradeDto(
    val orderId: String,           // 주문번호 (dedup key)
    val orderDate: String,         // yyyyMMdd
    val orderTime: String,         // HHmmss
    val stockCode: String,         // 종목코드 (KRW 기준 6자리)
    val stockName: String,
    val orderType: String,         // "BUY" | "SELL"
    val orderedQuantity: BigDecimal,
    val executedPrice: BigDecimal,
    val commissionAmount: BigDecimal,
    val currencyCode: String,      // "KRW"
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TossTradePageResponse(
    val trades: List<TossTradeDto>,
    val nextCursor: String? = null,   // 다음 페이지 커서
    val hasMore: Boolean = false,
)
