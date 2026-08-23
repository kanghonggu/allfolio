package com.allfolio.market.realestate

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.YearMonth

/**
 * 국토교통부 아파트 매매 실거래가 **상세** 자료 (`getRTMSDataSvcAptTradeDev`).
 *
 * ## 왜 '상세'인가
 *
 * 기본 자료(`15126469`)에는 **단지일련번호가 없다.** 그러면 단지 식별이 아파트명 문자열
 * 매칭이 되는데, "래미안"·"e편한세상"은 전국에 수백 개라 조용히 다른 단지 시세를 섞는다.
 * 상세(`15126468`)는 `aptSeq`(`11110-132`)를 준다.
 *
 * ## 질의 단위가 설계를 정한다
 *
 * **단지로는 물을 수 없다.** `(시군구 5자리, 계약년월)`이 유일한 질의 단위이고 응답은 그 달
 * 그 시군구의 **모든 아파트 거래**다. 그래서 시군구-월을 통째로 받아 로컬에서 거른다.
 *
 * **일 1,000회 제한**이 붙는다. 한 (시군구, 월)이 200건을 넘으면 페이징이 필요해
 * 호출이 더 든다 — 실측에서 분당(41135) 2026-07이 450건이라 3콜이었다. 그래서 호출부는
 * 이미 받은 조합을 기록해 다시 받지 않아야 한다.
 *
 * ## 🔴 인증키가 쿼리 파라미터(`serviceKey=`)에 실린다
 *
 * `FscCommodityClient`·`FredApiClient`와 같은 방어 셋을 지킨다: **전체 URL을 로그에 찍지
 * 않는다 · 예외에 `cause`를 붙이지 않는다**(Reactor의 checkpoint 프레임에 요청 URI가 통째로
 * 들어 있다) **· 응답 본문 미리보기를 남기지 않는다**(기본 오류 페이지가 요청 URI를
 * 되울려 렌더링한다). 이 예외 메시지는 수집 요약을 타고 어드민 응답까지 나가는 값이다.
 *
 * **키는 `FSC_API_KEY`를 그대로 쓴다.** 공공데이터포털은 계정당 인증키 하나이고 승인만
 * 오퍼레이션별이라, 금시세·지수와 같은 키가 그대로 통한다(2026-08-21 실측 확인).
 */
@Component
class RtmsClient(
    @Value("\${fsc.api-key:}") private val apiKey: String,
    // 기본값은 FscCommodityClient가 쓰는 것과 호스트가 같고 서비스 경로만 다르다.
    // 애너테이션 인자는 컴파일 상수여야 해서 상수 참조로 묶지 못한다 — 주소를 고칠 땐 함께 볼 것
    @Value("\${rtms.base-url:https://apis.data.go.kr/1613000}") private val baseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
            .also { builder -> connector?.let(builder::clientConnector) }
            .build()
    }

    /** 응답 대기 상한. 테스트에서만 줄인다 */
    internal var timeout: Duration = DEFAULT_TIMEOUT

    /** HTTP 커넥터. **운영은 null로 두고 기본값을 쓴다** — 루프백 스텁 검증용이다 */
    internal var connector: ClientHttpConnector? = null

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * `(시군구, 년월)` 한 페이지.
     *
     * **페이징은 호출부가 돈다** — [RtmsFetch.totalCount]와 [PAGE_SIZE]를 보고 다음
     * 페이지가 필요한지 판단한다. 여기서 다 감싸면 한 조합이 몇 콜을 썼는지 예산 관리가
     * 안 보인다.
     *
     * @param sggCode 법정동 코드 **앞 5자리** (예: 서울 종로구 `11110`)
     * @throws RtmsApiException 키 미설정·헤더 오류. 그 조합을 통째로 못 받은 것이라
     *         재시도 대상이다 — 행 단위 실패(`skipped`)와 구분한다
     */
    fun fetchDeals(sggCode: String, month: YearMonth, page: Int = 1): RtmsFetch {
        // 설정 누락은 상류 장애가 아니라 우리 문제다 — 사유가 남아야 운영자가 환경변수를 보러 간다
        if (!isConfigured()) {
            throw RtmsApiException("공공데이터포털 인증키가 설정되지 않았습니다 (FSC_API_KEY)")
        }

        // 구간만 남긴다. 전체 URL을 찍으면 serviceKey가 그대로 로그에 박힌다
        log.info("[실거래가] {} {} p{} 조회", sggCode, month, page)

        val raw = try {
            webClient.get()
                .uri { b ->
                    b.path(PATH)
                        .queryParam("serviceKey", apiKey)
                        // **`_type`이다. `resultType`이 아니다** — 금시세(FSC)와 파라미터
                        // 이름이 다르다. 틀리면 XML이 와서 파서가 통째로 깨진다
                        .queryParam("_type", "json")
                        .queryParam("LAWD_CD", sggCode)
                        // **`yyyyMM` 6자리다.** YearMonth.toString()은 `2026-07`이라 그대로
                        // 넘기면 조용히 0건이 된다
                        .queryParam("DEAL_YMD", "%04d%02d".format(month.year, month.monthValue))
                        .queryParam("numOfRows", PAGE_SIZE)
                        .queryParam("pageNo", page)
                        .build()
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(timeout)
        } catch (e: Exception) {
            // cause를 붙이지 않는다 — Reactor checkpoint에 요청 URI(=키)가 들어 있다
            throw RtmsApiException("실거래가 조회 실패 $sggCode $month p$page (${e.javaClass.simpleName})")
        } ?: throw RtmsApiException("실거래가 응답이 비었다 $sggCode $month p$page")

        // 본문 미리보기를 남기지 않는다 — 오류 페이지가 요청 URI를 되울린다
        val root = try {
            objectMapper.readTree(raw)
        } catch (e: Exception) {
            throw RtmsApiException("실거래가 응답이 JSON이 아니다 $sggCode $month p$page")
        }
        return RtmsDealParser.parse(root)
    }

    /** 이 페이지 뒤에 더 있는지. 호출부의 페이징 판단을 한 곳에 모은다 */
    fun hasMore(fetch: RtmsFetch, page: Int): Boolean = page * PAGE_SIZE < fetch.totalCount

    companion object {
        private const val PATH = "/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev"

        /**
         * 한 번에 받을 행 수. 포털 상한이 있어 더 키워도 안 늘어난다 —
         * 실측에서 `numOfRows=200`에 450건짜리 조합이 3페이지로 나뉘었다.
         */
        const val PAGE_SIZE = 200

        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
