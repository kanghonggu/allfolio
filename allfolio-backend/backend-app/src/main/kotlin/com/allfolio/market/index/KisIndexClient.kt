package com.allfolio.market.index

import com.allfolio.broker.kis.KisApiClient
import com.allfolio.broker.kis.KisProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration

/** KIS 지수 응답을 신뢰할 수 없을 때. AF-99의 HanaFxParseException과 같은 뜻 — "응답이 이상하다" */
class KisIndexException(message: String) : RuntimeException(message)

/**
 * KIS 국내 업종 지수 조회 (AF-101).
 *
 * 엔드포인트·tr_id는 KIS 공식 샘플에서 확인했다:
 * https://github.com/koreainvestment/open-trading-api/tree/main/examples_llm/domestic_stock/inquire_index_price
 *
 * **이 클래스는 파싱하지 않는다.** 원본 Map을 그대로 돌려준다 —
 * 필드 형식이 확정되기 전에 파서를 쓰면 잘못된 가정 위에 테스트를 쌓게 된다.
 */
@Component
class KisIndexClient(
    private val kisProperties: KisProperties,
    private val kisApiClient: KisApiClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient = WebClient.builder()
        .baseUrl(kisProperties.baseUrl)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    /** 지수 한 건의 원본 응답. output 맵을 그대로 돌려준다. */
    @Suppress("UNCHECKED_CAST")
    fun fetchRaw(kisIscd: String): Map<String, Any?> {
        if (!kisProperties.isConfigured()) {
            throw KisIndexException("KIS 인증 정보가 설정되지 않았습니다 (KIS_APP_KEY/KIS_APP_SECRET).")
        }

        val token = kisApiClient.issueToken().accessToken

        val body = try {
            webClient.get()
                .uri { b ->
                    b.path("/uapi/domestic-stock/v1/quotations/inquire-index-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                        .queryParam("FID_INPUT_ISCD", kisIscd)
                        .build()
                }
                .header("authorization", "Bearer $token")
                .header("appkey", kisProperties.appKey)
                .header("appsecret", kisProperties.appSecret)
                .header("tr_id", TR_ID)
                .retrieve()
                .bodyToMono<Map<String, Any?>>()
                .block(TIMEOUT)
        } catch (e: Throwable) {
            // AF-100·AF-99에서 쓴 형태. block(timeout)은 WebClientException이 아니라
            // IllegalStateException을 던져서, WebClientException만 잡으면 타임아웃이 raw로 샌다.
            if (e is Error) throw e
            throw KisIndexException("KIS 지수 조회 실패 iscd=$kisIscd: ${e.message}")
        } ?: throw KisIndexException("KIS 지수 응답이 비어 있습니다 iscd=$kisIscd")

        val output = body["output"] as? Map<String, Any?>
            ?: throw KisIndexException("KIS 지수 응답에 output이 없습니다 iscd=$kisIscd: ${body["msg1"]}")

        return output
    }

    companion object {
        private const val TR_ID = "FHPUP02100000"
        private val TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
