package com.allfolio.realasset.watch

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.time.LocalDate

/**
 * watchpricedata `/api/valuation` 클라이언트 (W5).
 *
 * ## 이 응답이 무엇인지
 *
 * ref 하나에 대한 **최근 30일 매물 호가의 중앙값**이다. 체결가가 아니다 — chrono24 매물은
 * 미국·UAE·호주에 있고 네이버 카페는 개인 판매 희망가라, 한국 사용자가 그 값에 팔 수 있는
 * 것이 아니다(설계 7절). 그래서 `priceBasis`가 항상 `ASK`다.
 *
 * ## 🔴 `asOf`는 관측일이 아니라 조회일이다
 *
 * 실측(2026-09-02) 응답의 `asOf`가 그날 날짜로 온다. 30일 창의 **끝**이지 그 창에서 가장
 * 최근 매물이 올라온 날이 아니다. 응답에 그 날짜를 알려 주는 필드가 없다.
 *
 * **이게 중요한 이유**는 소스마다 신선도가 다르기 때문이다 — 같은 날 실측에서 튜더는
 * 당일까지 관측이 있는데 롤렉스는 8/23에서 멈춰 있었다. 그런데도 둘 다 `asOf`는 9/2로
 * 온다. 화면이 "9/2 기준"이라고 쓰면 후자에 대해서는 없는 신선도를 주장하는 셈이다.
 *
 * 지금은 설계 7절의 표기 예시(`시세 기준: 매물 호가 중앙값 (날짜, 표본 N건)`)를 따라
 * `asOf`를 그대로 쓰되, **"30일 창의 끝"이라는 뜻으로만** 쓴다. 관측일이 필요해지면
 * watchpricedata에 필드를 추가해야 한다 — 우리 쪽에서 만들어 낼 수 있는 값이 아니다.
 *
 * ## 표본이 적으면 서버가 null을 준다
 *
 * W4가 `표본 3건 미만 → null`을 이미 서버에서 판정한다. 우리가 다시 세지 않는다 —
 * 임계치가 두 곳에 생기면 한쪽만 바뀐다.
 */
@Component
class WatchValuationClient(
    @Value("\${watchprice.base-url:https://api.watchpricedata.com}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder().baseUrl(baseUrl).build()
    }

    /**
     * 산출 불가면 null. **예외를 던지지 않는다** — ref 하나가 배치 전체를 죽이면 안 된다
     * (설계 1절 원칙 3과 같은 결).
     */
    fun valuate(refKey: String): WatchValuationResponse? =
        try {
            webClient.get()
                .uri { it.path("/api/valuation").queryParam("ref", refKey).build() }
                .retrieve()
                .bodyToMono(WatchValuationResponse::class.java)
                .timeout(TIMEOUT)
                .block()
                // 서버가 표본 부족으로 median을 안 준 경우다. 200이지만 값이 없다.
                ?.takeIf { it.median != null }
        } catch (e: WebClientResponseException) {
            if (e.statusCode == HttpStatus.NOT_FOUND) {
                log.debug("[시계] ref={} 는 소스에 없다", refKey)
            } else {
                log.warn("[시계] ref={} 조회 실패: {}", refKey, e.statusCode)
            }
            null
        } catch (e: Exception) {
            // 콜드스타트·네트워크 단절 등. 이 배치는 매일 돌므로 하루 빠져도 폴백이 받는다.
            log.warn("[시계] ref={} 조회 실패: {}", refKey, e.javaClass.simpleName)
            null
        }

    companion object {
        /**
         * watchpricedata는 EC2 단일 인스턴스라 콜드스타트가 없지만, ES 질의가 무거워질 수
         * 있다. 배치가 ref 수만큼 순차 호출하므로 한 건이 오래 물리면 전체가 밀린다.
         */
        private val TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}

/**
 * 실측 응답(2026-09-02, ref=126300)을 그대로 옮긴 것이다.
 *
 * **필드를 임의로 줄이지 않았다** — `p25`·`p75`·`dispersion`은 지금 평가에 안 쓰지만
 * 신뢰도 판정의 근거라 캐시에 남긴다. 나중에 임계치를 바꿀 때 원본 없이 재판정할 수 있다.
 */
data class WatchValuationResponse(
    val ref: String? = null,
    val refKey: String? = null,
    /** 🔴 조회일이다. 클래스 KDoc 참조 */
    val asOf: LocalDate? = null,
    val windowDays: Int? = null,
    val sampleSize: Int? = null,
    val listingCount: Int? = null,
    /** null이면 표본 부족이다 — 서버가 이미 판정했다 */
    val median: Long? = null,
    val p25: Long? = null,
    val p75: Long? = null,
    val min: Long? = null,
    val max: Long? = null,
    val dispersion: java.math.BigDecimal? = null,
    val officialPriceKrw: Long? = null,
    val priceBasis: String? = null,
    val confidence: String? = null,
    val reason: String? = null,
)
