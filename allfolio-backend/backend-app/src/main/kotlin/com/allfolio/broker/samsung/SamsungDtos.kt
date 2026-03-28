package com.allfolio.broker.samsung

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * 삼성증권 Open API DTOs
 *
 * Anti-Corruption Layer: 도메인으로 절대 유출하지 않음.
 */

// ── OAuth2 ────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class SamsungTokenResponse(
    @JsonProperty("access_token")  val accessToken: String,
    @JsonProperty("refresh_token") val refreshToken: String?,
    @JsonProperty("token_type")    val tokenType: String,
    @JsonProperty("expires_in")    val expiresIn: Int,
    val scope: String? = null,
)

// ── 계좌 ──────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class SamsungAccountDto(
    @JsonProperty("account_no")   val accountNo: String,
    @JsonProperty("account_name") val accountName: String,
    val currency: String = "KRW",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SamsungAccountListResponse(
    val accounts: List<SamsungAccountDto>,
)

// ── 체결 내역 ─────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class SamsungTradeDto(
    @JsonProperty("ord_no")        val ordNo: String,          // 주문번호 (dedup key)
    @JsonProperty("ord_dt")        val ordDt: String,          // yyyyMMdd
    @JsonProperty("ord_tm")        val ordTm: String,          // HHmmss
    @JsonProperty("isin_cd")       val isinCd: String,         // 종목코드
    @JsonProperty("stk_nm")        val stkNm: String,          // 종목명
    @JsonProperty("buy_sell_gb")   val buySellGb: String,      // "01"=매도, "02"=매수
    @JsonProperty("ccld_qty")      val ccldQty: BigDecimal,    // 체결수량
    @JsonProperty("ccld_prc")      val ccldPrc: BigDecimal,    // 체결단가
    @JsonProperty("comm_amt")      val commAmt: BigDecimal,    // 수수료
    @JsonProperty("ccld_crnc")     val ccldCrnc: String = "KRW",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SamsungTradePageResponse(
    val trades: List<SamsungTradeDto>,
    @JsonProperty("next_cursor") val nextCursor: String? = null,
    @JsonProperty("has_more")    val hasMore: Boolean = false,
)
