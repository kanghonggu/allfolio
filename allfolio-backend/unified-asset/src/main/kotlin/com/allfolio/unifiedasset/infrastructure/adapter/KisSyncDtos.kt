package com.allfolio.unifiedasset.infrastructure.adapter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisTokenResponse(
    @JsonProperty("access_token") val accessToken: String = "",
    @JsonProperty("expires_in")   val expiresIn: Long = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisBalanceResponse(
    @JsonProperty("rt_cd")   val rtCd: String = "",
    @JsonProperty("msg1")    val msg1: String = "",
    @JsonProperty("output1") val output1: List<KisBalanceItem> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KisBalanceItem(
    @JsonProperty("pdno")          val pdno: String = "",        // 종목코드
    @JsonProperty("prdt_name")     val prdtName: String = "",    // 종목명
    @JsonProperty("hldg_qty")      val hldgQty: String = "0",    // 보유수량
    @JsonProperty("pchs_avg_pric") val pchsAvgPric: String = "0", // 매입평균가
    @JsonProperty("pchs_amt")      val pchsAmt: String = "0",    // 매입금액
    @JsonProperty("prpr")          val prpr: String = "0",       // 현재가
    @JsonProperty("evlu_amt")      val evluAmt: String = "0",    // 평가금액
)
