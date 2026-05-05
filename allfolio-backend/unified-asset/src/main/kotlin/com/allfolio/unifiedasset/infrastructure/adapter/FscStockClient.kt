package com.allfolio.unifiedasset.infrastructure.adapter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

/**
 * 금융위원회 주식시세정보 API 클라이언트 (공공데이터포털 data.go.kr)
 *
 * 환경변수: FSC_API_KEY (공공데이터포털 발급 인증키)
 *
 * 제공 기능:
 *   - 개별 종목 현재가 (종가 기준, 최근 거래일)
 *   - KRX 전체 상장종목 목록 (KOSPI/KOSDAQ/KONEX/ETF/ETN)
 */
@Component
class FscStockClient(
    @Value("\${fsc.api-key:}") private val apiKey: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val BASE = "https://apis.data.go.kr/1160100/service"
    private val client = WebClient.builder()
        .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
        .build()

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * 종목 현재가(종가) 조회.
     * 6자리 한국 종목코드 전용. 설정 없거나 조회 실패 시 null 반환.
     */
    fun getPrice(symbol: String): BigDecimal? {
        if (!isConfigured()) return null
        return runCatching {
            val json = client.get()
                .uri("$BASE/GetStockSecuritiesInfoService/getStockPriceInfo") {
                    it.queryParam("serviceKey", apiKey)
                        .queryParam("numOfRows", 1)
                        .queryParam("pageNo", 1)
                        .queryParam("resultType", "json")
                        .queryParam("likeSrtnCd", symbol)
                        .build()
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(Duration.ofSeconds(5))
                ?: return null

            val resp = objectMapper.readValue(json, FscPriceResponse::class.java)
            val item = resp.response?.body?.items?.item?.firstOrNull() ?: return null
            val price = item.clpr?.toBigDecimalOrNull()
            if (price != null) log.debug("[FSC] {} price={}", symbol, price)
            price
        }.onFailure { e ->
            log.warn("[FSC] price lookup failed for {}: {}", symbol, e.message)
        }.getOrNull()
    }

    /**
     * KRX 전체 상장종목 목록 조회 (페이지네이션).
     * 상장 종목 DB 갱신용. 설정 없으면 빈 리스트 반환.
     */
    fun listAllStocks(): List<KrStockItem> {
        if (!isConfigured()) {
            log.warn("[FSC] API 키 미설정 — 종목 목록 조회 건너뜀")
            return emptyList()
        }
        val result = mutableListOf<KrStockItem>()
        var page = 1
        val pageSize = 3000
        while (true) {
            val items = fetchStockPage(page, pageSize)
            result.addAll(items)
            if (items.size < pageSize) break
            page++
        }
        log.info("[FSC] 전체 상장종목 {}개 조회 완료", result.size)
        return result
    }

    private fun fetchStockPage(page: Int, size: Int): List<KrStockItem> = runCatching {
        val json = client.get()
            .uri("$BASE/GetKrxListedInfoService/getItemInfo") {
                it.queryParam("serviceKey", apiKey)
                    .queryParam("numOfRows", size)
                    .queryParam("pageNo", page)
                    .queryParam("resultType", "json")
                    .build()
            }
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(15))
            ?: return emptyList()

        val resp = objectMapper.readValue(json, FscListResponse::class.java)
        resp.response?.body?.items?.item
            ?.filter { it.srtnCd?.isNotBlank() == true && it.itmsNm?.isNotBlank() == true }
            ?.map { item ->
                val market = when (item.mrktCtg?.uppercase()) {
                    "KOSPI" -> "KOSPI"
                    "KOSDAQ" -> "KOSDAQ"
                    "KONEX" -> "KONEX"
                    else -> item.mrktCtg ?: "KRX"
                }
                KrStockItem(
                    symbol = item.srtnCd!!.trim(),
                    name = item.itmsNm!!.trim(),
                    market = market,
                )
            } ?: emptyList()
    }.onFailure { e ->
        log.warn("[FSC] 종목목록 page={} 조회 실패: {}", page, e.message)
    }.getOrElse { emptyList() }

    // ── Response DTOs ──────────────────────────────────────────────

    data class KrStockItem(val symbol: String, val name: String, val market: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscPriceResponse(val response: FscPriceBody? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscPriceBody(val body: FscPriceItems? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscPriceItems(val items: FscPriceItemList? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscPriceItemList(val item: List<FscPriceItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscPriceItem(
        val srtnCd: String? = null,
        val itmsNm: String? = null,
        val clpr: String? = null,  // 종가
        val vs: String? = null,    // 전일대비
        val fltRt: String? = null, // 등락률
        val mkp: String? = null,   // 시가
        val hipr: String? = null,  // 고가
        val lopr: String? = null,  // 저가
        val trqu: String? = null,  // 거래량
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscListResponse(val response: FscListBody? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscListBody(val body: FscListItems? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscListItems(val items: FscListItemList? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscListItemList(val item: List<FscListItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscListItem(
        @JsonProperty("srtnCd")  val srtnCd: String? = null,
        @JsonProperty("isinCd")  val isinCd: String? = null,
        @JsonProperty("itmsNm")  val itmsNm: String? = null,
        @JsonProperty("corpNm")  val corpNm: String? = null,
        @JsonProperty("mrktCtg") val mrktCtg: String? = null,
    )
}
