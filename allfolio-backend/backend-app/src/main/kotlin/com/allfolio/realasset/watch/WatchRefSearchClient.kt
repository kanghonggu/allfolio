package com.allfolio.realasset.watch

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

/**
 * watchpricedata `/api/refs` 클라이언트 (AF-207) — 모델명으로 ref 후보를 찾는다.
 *
 * ## 이 응답이 무엇인지
 *
 * 상류 정가표(`official_prices`)에서 모델명·ref가 질의에 걸리는 행을 ref 단위로 묶은 것이다.
 * 각 항목의 `refKey`는 상류가 `/api/valuation`과 **같은 정규화 함수**로 만든 값이라, 그대로
 * 확인(lookup) 단계에 넘기면 된다(watch-data #51 계약). `ref`는 표시용이다.
 *
 * 이게 [WatchRefLookupController][com.allfolio.api.market.WatchRefLookupController] KDoc이
 * "이름 검색이 필요해지면 먼저 만들어야 한다"고 적었던 상류 집계 엔드포인트다. 브라우저가
 * `/api/search` 원본 문서를 묶는 길은 여전히 안 간다.
 *
 * ## 🔴 '없다'와 '못 찾았다'를 섞지 않는다
 *
 * [WatchValuationClient.valuate]는 실패를 null로 접는다 — 배치에서는 하루 빠져도 되고,
 * 화면도 "자동 평가가 안 된다"만 말하면 되기 때문이다. **검색은 다르다.** 빈 목록이면 화면은
 * "영문 모델명이나 번호로 다시 찾아보라"고 하고, 실패면 "검색을 못 쓰니 번호로 직접
 * 확인하라"고 한다. 둘을 같은 `[]`로 뭉개면 상류가 죽어 있는 동안 사용자는 "그런 시계가
 * 없다"는 거짓 답을 받는다. 그래서 [RefSearchOutcome]으로 갈라 돌려준다.
 *
 * ## 타임아웃은 짧게 — 등록 흐름이 여기에 막히면 안 된다
 *
 * 자동완성은 선택 사항이다. 레퍼런스 직접 입력 → 확인 경로가 항상 살아 있으므로, 상류가
 * 늦으면 기다리지 않고 [RefSearchOutcome.Unavailable]을 돌려 화면이 그 경로로 안내하게 한다.
 * 값의 근거는 [DEFAULT_TIMEOUT_MS] 참조.
 */
@Component
class WatchRefSearchClient(
    @Value("\${watchprice.base-url:https://api.watchpricedata.com}") private val baseUrl: String,
    @Value("\${watchprice.search-timeout-ms:$DEFAULT_TIMEOUT_MS}") timeoutMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val timeout: Duration = Duration.ofMillis(timeoutMs)

    private val webClient: WebClient by lazy {
        WebClient.builder().baseUrl(baseUrl).build()
    }

    /**
     * **예외를 던지지 않는다.** 상류의 어떤 실패도 [RefSearchOutcome.Unavailable]이다 —
     * 컨트롤러가 그걸 5xx로 내보내지 않고 "검색 불가" 상태로 응답한다.
     *
     * 🔴 404도 Unavailable이다. `/api/valuation`의 404는 "그 ref가 없다"였지만 여기서의
     * 404는 **엔드포인트가 없다**는 뜻이다(#51 배포 전 실측 2026-10-06: `/api/refs` → 404).
     * 후보가 없으면 상류는 200 + `[]`를 준다.
     */
    fun search(query: String, brand: String?, size: Int): RefSearchOutcome {
        return try {
            val body = webClient.get()
                .uri {
                    it.path("/api/refs")
                        .queryParam("q", query)
                        .queryParamIfPresent("brand", java.util.Optional.ofNullable(brand))
                        .queryParam("size", size)
                        .build()
                }
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<List<WatchRefCandidateResponse>>() {})
                .timeout(timeout)
                .block()
            // 200인데 본문이 비어 있으면 계약 위반이다. "후보 없음"으로 읽지 않는다.
                ?: return RefSearchOutcome.Unavailable("EMPTY_BODY")
            RefSearchOutcome.Found(body.filter { !it.refKey.isNullOrBlank() })
        } catch (e: WebClientResponseException) {
            log.warn("[시계] ref 검색 실패: {}", e.statusCode)
            RefSearchOutcome.Unavailable("HTTP_${e.statusCode.value()}")
        } catch (e: Exception) {
            // 타임아웃(느린 ES·Mongo 질의, 상류 장애)·연결 실패·역직렬화 실패.
            log.warn("[시계] ref 검색 실패: {}", e.javaClass.simpleName)
            RefSearchOutcome.Unavailable(e.javaClass.simpleName)
        }
    }

    companion object {
        /**
         * 3초.
         *
         * - watchpricedata는 EC2에서 `docker run -d`로 상시 떠 있어 **잠들지 않는다**(콜드스타트 없음 —
         *   allfolio 백엔드의 Render 무료 인스턴스와 다르다). `/api/valuation` 5회 연속 실측(curl,
         *   2026-10-06): 첫 호출 1.77초(연결 수립·ES 캐시), 이후 0.17~0.45초.
         *   `/api/refs`는 배포 전이라 못 쟀다 — 같은 서버의 Mongo 정규식 질의라 비슷한 자릿수로
         *   잡았다. 3초면 첫 호출도 들어간다.
         * - 그보다 오래 걸리면 질의가 무겁거나 상류가 아픈 것이고, 어느 쪽이든 자동완성을
         *   기다리게 할 이유가 없다 — 직접 입력 경로로 보내는 편이 빠르다.
         * - 확인(lookup)은 20초를 그대로 둔다. 그건 사용자가 버튼을 눌러 기다리는 단계다.
         */
        const val DEFAULT_TIMEOUT_MS: Long = 3_000
    }
}

/** 검색 결과. **빈 목록과 실패는 다른 값이다** — 클래스 KDoc 참조 */
sealed interface RefSearchOutcome {
    /** 상류가 답했다. 비어 있을 수 있다 — 그건 "후보가 없다"는 정상 답이다 */
    data class Found(val candidates: List<WatchRefCandidateResponse>) : RefSearchOutcome

    /** 상류에 닿지 못했거나 답이 깨졌다. [reason]은 로그·진단용이다 */
    data class Unavailable(val reason: String) : RefSearchOutcome
}

/**
 * watch-data #51 `RefCandidate`를 옮긴 것이다. 상류는 `refKey`·`officialPriceKrw`를 non-null로
 * 약속하지만, 우리는 역직렬화가 깨지지 않게 전부 nullable로 받고 `refKey` 없는 항목만 버린다.
 */
data class WatchRefCandidateResponse(
    /** 표시용 원문 ref */
    val ref: String? = null,
    /** 🔴 확인 단계에 **그대로** 넘길 키 */
    val refKey: String? = null,
    val brand: String? = null,
    val modelTitle: String? = null,
    val officialPriceKrw: Long? = null,
)
