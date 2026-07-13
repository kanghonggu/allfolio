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
    @JsonProperty("pdno")          val pdno: String = "",
    @JsonProperty("prdt_name")     val prdtName: String = "",
    @JsonProperty("hldg_qty")      val hldgQty: String = "0",
    @JsonProperty("pchs_avg_pric") val pchsAvgPric: String = "0",
    @JsonProperty("pchs_amt")      val pchsAmt: String = "0",
    @JsonProperty("prpr")          val prpr: String = "0",
    @JsonProperty("evlu_amt")      val evluAmt: String = "0",
)
