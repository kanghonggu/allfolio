package com.allfolio.unifiedasset.infrastructure.adapter

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
class KisSyncClientImpl(
    private val props: KisSyncProperties,
) : KisSyncClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val webClient = WebClient.builder().baseUrl(props.baseUrl()).build()

    // appkey -> (token, 만료 epoch ms). KIS 토큰 발급은 앱키당 1분 1회 제한이라 캐시 필수.
    private val tokenCache = ConcurrentHashMap<String, Pair<String, Long>>()

    override fun issueToken(appKey: String, appSecret: String): String {
        tokenCache[appKey]?.let { (token, expiry) ->
            if (System.currentTimeMillis() < expiry) return token
        }
        val resp = webClient.post()
            .uri("/oauth2/tokenP")
            .bodyValue(mapOf(
                "grant_type" to "client_credentials",
                "appkey"     to appKey,
                "appsecret"  to appSecret,
            ))
            .retrieve()
            .bodyToMono<KisTokenResponse>()
            .block(Duration.ofSeconds(10))
        if (resp == null || resp.accessToken.isBlank()) {
            throw RuntimeException("KIS 토큰 발급 실패")
        }
        val ttlMs = (resp.expiresIn - 120).coerceAtLeast(0) * 1000
        tokenCache[appKey] = resp.accessToken to (System.currentTimeMillis() + ttlMs)
        log.info("[KIS] access token issued (cache miss)")
        return resp.accessToken
    }

    override fun fetchBalance(
        appKey: String, appSecret: String, cano: String, acntPrdtCd: String,
    ): KisBalanceResponse {
        val token = issueToken(appKey, appSecret)
        return webClient.get()
            .uri { ub ->
                ub.path("/uapi/domestic-stock/v1/trading/inquire-balance")
                    .queryParam("CANO", cano)
                    .queryParam("ACNT_PRDT_CD", acntPrdtCd)
                    .queryParam("AFHR_FLPR_YN", "N")
                    .queryParam("OFL_YN", "")
                    .queryParam("INQR_DVSN", "02")
                    .queryParam("UNPR_DVSN", "01")
                    .queryParam("FUND_STTL_ICLD_YN", "N")
                    .queryParam("FNCG_AMT_AUTO_RDPT_YN", "N")
                    .queryParam("PRCS_DVSN", "00")
                    .queryParam("CTX_AREA_FK100", "")
                    .queryParam("CTX_AREA_NK100", "")
                    .build()
            }
            .header("Authorization", "Bearer $token")
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", props.trIdBalance())
            .header("custtype", "P")
            .retrieve()
            .bodyToMono<KisBalanceResponse>()
            .block(Duration.ofSeconds(15))
            ?: KisBalanceResponse(rtCd = "-1", msg1 = "KIS 응답 없음")
    }
}
