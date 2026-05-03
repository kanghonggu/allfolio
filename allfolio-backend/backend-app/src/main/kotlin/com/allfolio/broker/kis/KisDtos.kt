package com.allfolio.broker.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ── OAuth ────────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisTokenResponse(
    @JsonProperty("access_token")  val accessToken: String = "",
    @JsonProperty("token_type")    val tokenType: String = "Bearer",
    @JsonProperty("expires_in")    val expiresIn: Long = 0,
    @JsonProperty("access_token_token_expired") val expiredAt: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisApprovalKeyResponse(
    @JsonProperty("approval_key") val approvalKey: String = "",
)

// ── 거래 내역 ─────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisOrderHistoryResponse(
    @JsonProperty("rt_cd")   val rtCd: String = "",    // "0" = 성공
    @JsonProperty("msg_cd")  val msgCd: String = "",
    @JsonProperty("msg1")    val msg1: String = "",
    @JsonProperty("ctx_area_fk100") val ctxAreaFk100: String = "",  // 다음 페이지 커서 (연속조회)
    @JsonProperty("ctx_area_nk100") val ctxAreaNk100: String = "",
    @JsonProperty("output1") val output1: List<KisOrderItem> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisOrderItem(
    @JsonProperty("ord_dt")       val orderDate: String = "",       // 주문일자 YYYYMMDD
    @JsonProperty("odno")         val orderNo: String = "",         // 주문번호 (externalTradeId)
    @JsonProperty("pdno")         val stockCode: String = "",       // 종목코드
    @JsonProperty("prdt_name")    val stockName: String = "",       // 종목명
    @JsonProperty("sll_buy_dvsn_cd") val sideCode: String = "",    // 01=매도, 02=매수
    @JsonProperty("tot_ccld_qty") val filledQty: String = "0",     // 체결수량
    @JsonProperty("avg_prvs")     val avgPrice: String = "0",      // 평균체결가
    @JsonProperty("ccld_amt")     val filledAmt: String = "0",     // 체결금액
    @JsonProperty("ord_tmd")      val orderTime: String = "",       // 주문시각 HHMMSS
)

// ── 계좌 목록 ─────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisAccountListResponse(
    @JsonProperty("output") val accounts: List<KisAccountItem> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisAccountItem(
    @JsonProperty("cano")        val accountNo: String = "",        // 계좌번호 앞 8자리
    @JsonProperty("acnt_prdt_cd") val productCode: String = "",    // 계좌상품코드
    @JsonProperty("acnt_nm")     val accountName: String = "",
)

// ── WebSocket 수신 ────────────────────────────────────────────────────────────

/**
 * KIS WebSocket 체결가 데이터 (H0STCNT0)
 * 파이프(|) 구분 헤더 + ^ 구분 body 형식으로 수신됨
 * 예: 0|H0STCNT0|001|005930^171011^85600^...
 */
data class KisWsTickData(
    val trId: String,           // tr_id  ex) H0STCNT0
    val dataCount: Int,         // 데이터 건수
    val stockCode: String,      // 종목코드
    val currentPrice: String,   // 현재가
    val tradingTime: String,    // 체결시각 HHMMSS
    val volume: String,         // 체결수량
    val priceChange: String,    // 전일대비
)
