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
 * 경로가 틀렸다는 사실이 첫 호출에서 드러난다.
 *
 * [EcosStatisticSearchClient]와 합치지 않는 이유: 그쪽은 응답을 파서에 넘겨 도메인 타입으로
 * 바꾸는 것이 일이고, 이쪽은 바꾸지 않는 것이 일이다.
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
         */
        private const val MAX_ROWS = 10_000
    }

    /**
     * 통계표 목록. [statCode]를 주면 그 하위만 본다.
     *
     * 경로 형식: `/api/StatisticTableList/{인증키}/{요청유형}/{언어}/{시작건수}/{종료건수}[/{통계표코드}]`
     */
    fun tables(statCode: String?): String =
        call("/api/StatisticTableList/${properties.apiKey}/json/kr/1/$MAX_ROWS" + (statCode?.let { "/$it" } ?: ""))

    /** 통계표 하나의 항목 목록. 여기 나오는 ITEM_CODE가 `market-rate.series[].item-code`가 된다 */
    fun items(statCode: String): String =
        call("/api/StatisticItemList/${properties.apiKey}/json/kr/1/$MAX_ROWS/$statCode")

    private fun call(path: String): String {
        if (properties.apiKey.isBlank()) {
            throw EcosApiException("NO_KEY", "ECOS 인증키가 설정되지 않았습니다 (ECOS_API_KEY)")
        }
        // 인증키가 경로 첫 세그먼트에 있다. 전체 URL을 로그에 찍지 않는다
        log.info("[ECOS] 목록 조회")
        return try {
            webClient.get().uri(path).retrieve().bodyToMono(String::class.java).block(TIMEOUT)
                ?: throw EcosApiException("EMPTY", "응답 본문이 비어 있습니다")
        } catch (e: WebClientResponseException) {
            // **상태 코드를 남기는 게 이 분기의 전부다.** 서비스 이름이 틀리면 ECOS는 RESULT가 아니라
            // HTTP 404를 준다(실측). 아래 IO로 뭉개면 "경로가 틀렸다"와 "연결이 안 됐다"가 같은 문구가
            // 되는데, 경로를 확인하러 만든 엔드포인트가 경로 실수를 못 보여주면 존재 이유가 없다.
            // 본문은 싣지도 로그하지도 않는다 — 서버가 되울린 요청 URI에 인증키가 들어 있고,
            // 그걸 가리는 마스킹 기계는 EcosStatisticSearchClient에 있다. 여기서는 본문이 필요 없으므로
            // 복제하는 대신 아예 안 만진다(정상 응답 본문은 성공 경로로 그대로 나간다).
            val status = e.statusCode.value()
            log.warn("[ECOS] 목록 조회 HTTP {}", status)
            throw EcosApiException("HTTP-$status", "ECOS가 HTTP $status 를 반환했습니다")
        } catch (e: EcosApiException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) Thread.currentThread().interrupt()
            // 예외 메시지·스택에 인증키가 박힌 URI가 들어 있다. 갈아끼우고 cause도 붙이지 않는다 —
            // EcosStatisticSearchClient가 같은 이유로 같은 방어를 한다
            log.warn("[ECOS] 목록 조회 실패 reason={}", e.javaClass.simpleName)
            throw EcosApiException("IO", "ECOS 목록 조회에 실패했습니다")
        }
    }
}
