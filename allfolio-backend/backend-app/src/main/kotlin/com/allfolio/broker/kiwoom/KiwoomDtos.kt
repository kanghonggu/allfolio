package com.allfolio.broker.kiwoom

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ── OAuth ────────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class KiwoomTokenResponse(
    @JsonProperty("access_token")  val accessToken: String = "",
    @JsonProperty("token_type")    val tokenType: String = "Bearer",
    @JsonProperty("expires_in")    val expiresIn: Long = 0,
    @JsonProperty("scope")         val scope: String = "",
)

// ── 주문 체결 내역 ─────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class KiwoomOrderHistoryResponse(
    @JsonProperty("return_code")   val returnCode: Int = -1,
    @JsonProperty("return_msg")    val returnMsg: String = "",
    @JsonProperty("has_next")      val hasNext: Boolean = false,
    @JsonProperty("next_key")      val nextKey: String = "",
    @JsonProperty("list")          val list: List<KiwoomOrderItem> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KiwoomOrderItem(
    @JsonProperty("order_no")      val orderNo: String = "",        // 주문번호 (externalTradeId)
    @JsonProperty("stock_code")    val stockCode: String = "",       // 종목코드
    @JsonProperty("stock_name")    val stockName: String = "",
    @JsonProperty("order_type")    val orderType: String = "",       // "BUY" / "SELL"
    @JsonProperty("filled_qty")    val filledQty: String = "0",     // 체결수량
    @JsonProperty("filled_price")  val filledPrice: String = "0",   // 체결단가
    @JsonProperty("filled_amt")    val filledAmt: String = "0",     // 체결금액
    @JsonProperty("order_date")    val orderDate: String = "",       // YYYYMMDD
    @JsonProperty("order_time")    val orderTime: String = "",       // HHMMSS
    @JsonProperty("fee")           val fee: String = "0",
)

// ── 계좌 목록 ─────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class KiwoomAccountResponse(
    @JsonProperty("return_code") val returnCode: Int = -1,
    @JsonProperty("account_list") val accountList: List<KiwoomAccountItem> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KiwoomAccountItem(
    @JsonProperty("account_no")   val accountNo: String = "",
    @JsonProperty("account_name") val accountName: String = "",
    @JsonProperty("account_type") val accountType: String = "",
)
