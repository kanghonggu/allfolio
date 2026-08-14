package com.allfolio.fx

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration

/**
 * ECOS 통계표·항목 **목록** 조회 (AF-102).
 *
 * 수집이 아니라 **코드 확인용**이다. ECOS는 틀린 통계표·항목 코드에 오류가 아니라 0건을 준다 —
 * 그래서 코드를 추정해 넣으면 "코드가 틀렸는지 기간이 빈 건지" 영영 구분할 수 없다.
 * 로컬에는 인증키가 없고 Render에만 있으므로, 배포된 서버를 통하는 이 경로가
 * 사람이 사이트를 뒤지지 않는 유일한 확인 방법이다.
 *
 * **응답을 파싱하지 않고 그대로 돌려준다.** 파싱하면 우리가 기대한 모양만 보이는데,
 * 이 도구의 목적은 기대가 맞는지 확인하는 것이다. 오류 응답(RESULT)도 그대로 보여야
 * 코드가 틀렸다는 사실이 첫 호출에서 드러난다.
 * (경로가 틀린 경우는 여기로 오지 않는다 — 서비스 이름이 틀리면 ECOS는 HTTP 404를 주고,
 *  그건 아래 예외 분기에서 상태 코드만 남기고 본문 없이 끝난다.)
 *
 * **딱 하나 손댄다: 인증키 마스킹.** "그대로"의 예외를 두는 이유는 이 본문이 로그가 아니라
 * 브라우저·devtools·디스크 캐시로 나가고, 이 도구의 사용 방식이 "받은 걸 붙여넣어 물어본다"라서다.
 * 아무도 자기 인증키가 되울려 오길 원해서 부르지 않는다. 근거는 [maskEcosApiKey] 참조.
 *
 * [EcosStatisticSearchClient]와 합치지 않는 이유: 그쪽은 응답을 파서에 넘겨 도메인 타입으로
 * 바꾸는 것이 일이고, 이쪽은 바꾸지 않는 것이 일이다.
 *
 * **statCode를 검증하지 않고 부르지 말 것.** 여기서는 받은 값을 인증키가 실린 경로에 그대로
 * 이어 붙인다 — `/`·`%2F`·`?`·`#`가 섞이면 요청이 다른 경로/쿼리로 새면서 인증키를 달고 간다.
 * 모양 검사는 호출자([com.allfolio.api.admin.MarketRateAdminController])가 400으로 막는다.
 */
@Component
class EcosStatListClient(
    private val properties: EcosProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(properties.baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
            .build()
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(30)

        /**
         * 한 번에 받아 올 행 수. 통계표는 전체가 900개 남짓, 항목은 통계표 하나에 많아야 수천 개라
         * 한 번에 받는다 — 페이지를 넘기게 만들면 코드를 찾으러 온 사람이 페이징까지 신경 써야 한다.
         *
         * **한 번만 받으므로 잘릴 수 있다.** 잘렸는지는 응답 본문의 `list_total_count`로 판별한다 —
         * 그 값이 이 상한을 넘으면 뒷부분은 안 온 것이다. 페이징을 안 넣은 대신 신호를 남긴다.
         */
        private const val MAX_ROWS = 10_000
    }

    /**
     * 통계표 목록. [statCode]를 주면 그 하위만 본다.
     *
     * 경로 형식: `/api/StatisticTableList/{인증키}/{요청유형}/{언어}/{시작건수}/{종료건수}[/{통계표코드}]`
     *
     * 응답의 `list_total_count`가 10000을 넘으면 목록이 잘린 것이다(단발 조회, [MAX_ROWS]).
     */
    fun tables(statCode: String?): String {
        val suffix = statCode?.let { "/$it" } ?: ""
        return call(
            api = "StatisticTableList",
            statCode = statCode,
            path = "/api/StatisticTableList/${properties.apiKey}/json/kr/1/$MAX_ROWS$suffix",
        )
    }

    /**
     * 통계표 하나의 항목 목록. 여기 나오는 ITEM_CODE가 `market-rate.ecos[].item-code`가 된다.
     *
     * 응답의 `list_total_count`가 10000을 넘으면 목록이 잘린 것이다(단발 조회, [MAX_ROWS]).
     */
    fun items(statCode: String): String =
        call(
            api = "StatisticItemList",
            statCode = statCode,
            path = "/api/StatisticItemList/${properties.apiKey}/json/kr/1/$MAX_ROWS/$statCode",
        )

    private fun call(api: String, statCode: String?, path: String): String {
        if (properties.apiKey.isBlank()) {
            throw EcosApiException("NO_KEY", "ECOS 인증키가 설정되지 않았습니다 (ECOS_API_KEY)")
        }
        // 인증키가 경로 첫 세그먼트에 있다. 전체 URL은 찍지 않고, 비밀이 아닌 둘만 남긴다 —
        // 이 엔드포인트의 사용 방식이 "코드를 바꿔 가며 여러 번 부른다"라서, 어느 API에 어떤 코드로
        // 물었는지가 없으면 로그로는 시도를 구분할 수 없다(EcosStatisticSearchClient도 같이 남긴다).
        log.info("[ECOS] 목록 조회 api={} statCode={}", api, statCode ?: "-")
        return try {
            val raw = webClient.get().uri(path).retrieve().bodyToMono(String::class.java).block(TIMEOUT)
                ?: throw EcosApiException("EMPTY", "응답 본문이 비어 있습니다")
            // **성공 본문은 그대로 HTTP 응답이 된다 — 여기가 이 클래스의 배송물이다.**
            // ECOS는 RESULT 오류를 200으로 주고, 그 메시지에 인증키가 실려 올 수 있다(실제로
            // EcosStatisticSearchClient의 마스킹이 존재하는 이유가 그 형태다). 한 줄이면 막는데,
            // 탐색 가치는 조금도 줄지 않는다 — 본 사람이 확인하려는 건 코드지 자기 인증키가 아니다.
            maskEcosApiKey(raw, properties.apiKey)
        } catch (e: WebClientResponseException) {
            // **상태 코드를 남기는 게 이 분기의 전부다.** 서비스 이름이 틀리면 ECOS는 RESULT가 아니라
            // HTTP 404를 준다(실측). 아래 IO로 뭉개면 "경로가 틀렸다"와 "연결이 안 됐다"가 같은 문구가
            // 되는데, 경로를 확인하러 만든 엔드포인트가 경로 실수를 못 보여주면 존재 이유가 없다.
            // 본문은 싣지도 로그하지도 않는다 — 서버가 되울린 요청 URI에 인증키가 들어 있고,
            // 그걸 통째로 지우는 정규식은 EcosStatisticSearchClient에 있다(자르기 때문에 필요하다).
            // 여기서는 실패 본문이 필요 없으므로 복제하는 대신 아예 안 만진다.
            val status = e.statusCode.value()
            if (e.statusCode.is2xxSuccessful) {
                // 2xx인데 이 예외가 나왔다는 건 본문을 못 읽었다는 뜻이다(코덱 8MB 초과, 중간 절단 등).
                // "HTTP 200 실패"로 보고하면 멀쩡한 한국은행을 확인하러 가게 된다 — 404를 IO로 뭉개지
                // 않기로 한 것과 같은 이유로, 상태만 보고 남의 장애로 부르지 않는다.
                log.warn("[ECOS] 목록 응답 디코드 실패 status={} api={}", status, api)
                throw EcosApiException("DECODE", "응답 본문을 읽지 못했습니다 (status=$status)")
            }
            log.warn("[ECOS] 목록 조회 HTTP {} api={}", status, api)
            throw EcosApiException("HTTP-$status", "ECOS가 HTTP $status 를 반환했습니다")
        } catch (e: EcosApiException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) Thread.currentThread().interrupt()
            // 예외 메시지·스택에 인증키가 박힌 URI가 들어 있다. 갈아끼우고 cause도 붙이지 않는다 —
            // EcosStatisticSearchClient가 같은 이유로 같은 방어를 한다
            log.warn("[ECOS] 목록 조회 실패 api={} reason={}", api, e.javaClass.simpleName)
            throw EcosApiException("IO", "ECOS 목록 조회에 실패했습니다")
        }
    }
}
