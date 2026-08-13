package com.allfolio.market.index

import com.allfolio.broker.kis.KisApiClient
import com.allfolio.broker.kis.KisProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

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
    private val webClient = WebClient.builder()
        .baseUrl(kisProperties.baseUrl)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    /**
     * 발급받은 access_token. Pair는 (만료 epoch millis, 토큰)이고, AF-99의
     * [com.allfolio.fx.HanaFxRateService.cached]와 같은 모양이다.
     *
     * **지수마다 토큰을 발급하면 2번째부터 403이 난다.** KIS는 토큰 자체는 약 24시간 살려두면서
     * 발급 호출은 분당 1회 정도로 막는다. 그래서 지수 목록을 도는 동안 매번 `issueToken()`을 부르면
     * 첫 지수만 통과하고 나머지가 `403 Forbidden from POST .../oauth2/tokenP`로 죽는다 —
     * 2026-08-13 운영에서 실제로 3건 중 KOSPI만 남고 KOSDAQ·KOSPI200이 이렇게 실패했다.
     * 한 번 받은 토큰을 여기 두고 세 지수가 나눠 쓴다. **"어차피 발급이 싸다"고 이 캐시를 걷어내면
     * 그날로 그 장애가 그대로 돌아온다.**
     *
     * 수명은 응답의 `expires_in`에서 [SAFETY_MARGIN_SECONDS]를 뺀 값으로 잡는다. 24시간을 상수로
     * 박아두면 KIS가 수명을 줄이는 날 죽은 토큰을 조용히 계속 내주게 된다. `expires_in`이 0이거나
     * 안 오면 [FALLBACK_LIFETIME_SECONDS]로 짧게 잡는다 — 모르는 값은 길게가 아니라 짧게.
     *
     * **발급 실패는 절대 캐시하지 않는다.** 예외는 그대로 밖으로 나가고 캐시는 손대지 않으므로
     * 다음 호출이 다시 발급을 시도한다. AF-100에서 "결과가 아닌 것"을 기억했다가 폴백 값이
     * 프로세스 수명 내내 고정되던 함정이 바로 이 지점이다.
     *
     * **이 캐시는 프로세스 안에만 있다 — 단일 인스턴스 전제다.** [HanaFxRateService][com.allfolio.fx.HanaFxRateService]와
     * 같은 한계로, 스케일아웃하면 인스턴스마다 따로 발급해 발급 호출이 인스턴스 수만큼 늘어난다.
     * 지금은 `render.yaml`이 `plan: free`라 인스턴스가 하나뿐이라 성립한다.
     */
    private val cachedToken = AtomicReference<Pair<Long, String>?>(null)

    /**
     * 살아 있는 토큰이 있으면 그걸 쓰고, 없을 때만 발급한다.
     * 발급이 터지면 예외가 그대로 올라가고 캐시는 그대로 남는다(= 실패를 기억하지 않는다).
     */
    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        cachedToken.get()?.let { (expiresAt, token) -> if (now < expiresAt) return token }

        val response = kisApiClient.issueToken()
        val lifetime = if (response.expiresIn > 0) response.expiresIn else FALLBACK_LIFETIME_SECONDS
        val usableSeconds = (lifetime - SAFETY_MARGIN_SECONDS).coerceAtLeast(0)
        cachedToken.set((now + usableSeconds * 1_000) to response.accessToken)
        return response.accessToken
    }

    /** 지수 한 건의 원본 응답. output 맵을 그대로 돌려준다. */
    @Suppress("UNCHECKED_CAST")
    fun fetchRaw(kisIscd: String): Map<String, Any?> {
        if (!kisProperties.isConfigured()) {
            throw KisIndexException("KIS 인증 정보가 설정되지 않았습니다 (KIS_APP_KEY/KIS_APP_SECRET).")
        }

        val token = accessToken()

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

        /** 만료 직전 토큰으로 요청이 나가는 것을 막는 여유. 수집 한 바퀴가 이 안에 끝난다 */
        private const val SAFETY_MARGIN_SECONDS = 60L

        /** `expires_in`을 못 믿을 때의 수명. 짧게 잡아도 한 바퀴(세 지수)는 한 토큰으로 돈다 */
        private const val FALLBACK_LIFETIME_SECONDS = 300L
    }
}
