package com.allfolio.market.index

import com.allfolio.broker.kis.KisApiClient
import com.allfolio.broker.kis.KisProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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

    /**
     * 해외 지수 일별 시세의 **응답 전체**를 그대로 돌려준다 (AF-110).
     *
     * 엔드포인트·tr_id는 KIS 공식 샘플에서 확인했다:
     * `examples_llm/overseas_stock/inquire_daily_chartprice`
     *
     * **일부러 파싱하지 않는다.** 이 응답은 `output1`과 `output2` 두 갈래로 오는데,
     * 최신 봉이 어느 쪽에 실리는지, 각 갈래가 어떤 필드를 담는지가 확정되지 않았다.
     * AF-101(국내 지수)에서 등락률의 단위(`1.23`인지 `0.0123`인지)와 부호 규약을 맞힌 이유는
     * 똑똑해서가 아니라 **파서를 쓰기 전에 원본 응답 한 건을 눈으로 봤기 때문**이다.
     * 추측으로 필드를 고르면 그 추측 위에 테스트까지 쌓여 틀린 값이 그럴듯하게 굳는다.
     * 그래서 여기서는 한쪽을 고르지도, 펼치지도, 이름을 바꾸지도 않는다 —
     * `rt_cd`·`msg1`을 포함한 본문을 통째로 올려보낸다.
     *
     * 토큰은 [accessToken]의 캐시를 그대로 쓴다. 진단용 호출이라도 발급을 새로 하면
     * 분당 1회 제한을 같이 나눠 쓰는 수집 배치의 토큰 발급을 밀어내 403을 부를 수 있다.
     *
     * `iscd`에는 `.DJI`·`HK#HS`처럼 `.`과 `#`이 들어간다. 그래서 값을 URI 템플릿 변수로
     * 넘겨 **쿼리 파라미터 값으로 인코딩**되게 한다 — 문자열을 직접 이어붙이면 `#`부터가
     * 프래그먼트로 잘려 나가 `HK`만 KIS에 도착한다.
     */
    fun fetchOverseasRaw(iscd: String, from: LocalDate, to: LocalDate): Map<String, Any?> {
        if (!kisProperties.isConfigured()) {
            throw KisIndexException("KIS 인증 정보가 설정되지 않았습니다 (KIS_APP_KEY/KIS_APP_SECRET).")
        }

        val token = accessToken()

        val body = try {
            webClient.get()
                .uri { b ->
                    b.path("/uapi/overseas-price/v1/quotations/inquire-daily-chartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "N")
                        .queryParam("FID_INPUT_ISCD", "{iscd}")
                        .queryParam("FID_INPUT_DATE_1", "{from}")
                        .queryParam("FID_INPUT_DATE_2", "{to}")
                        .queryParam("FID_PERIOD_DIV_CODE", "D")
                        .build(
                            mapOf(
                                "iscd" to iscd,
                                "from" to from.format(DateTimeFormatter.BASIC_ISO_DATE),
                                "to" to to.format(DateTimeFormatter.BASIC_ISO_DATE),
                            )
                        )
                }
                .header("authorization", "Bearer $token")
                .header("appkey", kisProperties.appKey)
                .header("appsecret", kisProperties.appSecret)
                .header("tr_id", OVERSEAS_TR_ID)
                .retrieve()
                .bodyToMono<Map<String, Any?>>()
                .block(TIMEOUT)
        } catch (e: Throwable) {
            // fetchRaw와 같은 이유 — block(timeout)은 WebClientException이 아니라
            // IllegalStateException을 던져서, WebClientException만 잡으면 타임아웃이 raw로 샌다.
            if (e is Error) throw e
            throw KisIndexException("KIS 해외 지수 조회 실패 iscd=$iscd: ${e.message}")
        } ?: throw KisIndexException("KIS 해외 지수 응답이 비어 있습니다 iscd=$iscd")

        if (body.isEmpty()) {
            throw KisIndexException("KIS 해외 지수 응답이 비어 있습니다 iscd=$iscd")
        }

        // rt_cd가 아예 없으면 통과시킨다. 응답 모양을 보러 온 엔드포인트라 "우리가 아는 형식이
        // 아니다"는 이유로 막으면 정작 보러 온 것을 못 본다. 대신 KIS가 실패라고 말했을 때는
        // 그 문구를 그대로 올린다 — 빈 output을 성공으로 착각하지 않게.
        val rtCd = body["rt_cd"]?.toString()
        if (rtCd != null && rtCd != RT_CD_SUCCESS) {
            throw KisIndexException("KIS 해외 지수 조회 실패 iscd=$iscd rt_cd=$rtCd: ${body["msg1"]}")
        }

        return body
    }

    companion object {
        private const val TR_ID = "FHPUP02100000"

        /** 해외지수 일별 차트 조회 (FID_COND_MRKT_DIV_CODE=N) */
        private const val OVERSEAS_TR_ID = "FHKST03030100"

        /** KIS 공통 응답의 성공 코드 */
        private const val RT_CD_SUCCESS = "0"

        private val TIMEOUT: Duration = Duration.ofSeconds(15)

        /** 만료 직전 토큰으로 요청이 나가는 것을 막는 여유. 수집 한 바퀴가 이 안에 끝난다 */
        private const val SAFETY_MARGIN_SECONDS = 60L

        /** `expires_in`을 못 믿을 때의 수명. 짧게 잡아도 한 바퀴(세 지수)는 한 토큰으로 돈다 */
        private const val FALLBACK_LIFETIME_SECONDS = 300L
    }
}
