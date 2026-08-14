package com.allfolio.market.rate.fred

import com.allfolio.fx.RateValuePolicy
import com.allfolio.market.rate.RateFetch
import com.fasterxml.jackson.core.JsonProcessingException
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.time.LocalDate

/**
 * FRED `series/observations` 호출.
 *
 * **인증키를 로그에 남기지 않는다.** 키가 쿼리 파라미터에 실려서, 전체 URL을 찍거나
 * 예외에 cause를 붙이면(Reactor의 checkpoint 프레임에 요청 URI가 통째로 들어 있다)
 * 그대로 샌다. `EcosStatisticSearchClient`가 같은 이유로 같은 방어를 한다.
 */
@Component
class FredApiClient(
    private val properties: FredProperties,
    private val parser: FredObservationParser,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(properties.baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
            .also { builder -> connector?.let(builder::clientConnector) }
            .build()
    }

    /**
     * 응답 대기 상한. 테스트에서만 줄인다 — 기본 30초를 매번 태우면 타임아웃 경로를 검증할 수 없다.
     * 프로덕션에서 바꿀 값이 아니라 설정으로 빼지 않았다. (`EcosStatisticSearchClient`와 같다.)
     */
    internal var timeout: Duration = DEFAULT_TIMEOUT

    /**
     * HTTP 커넥터. **운영은 null로 두고 기본값(reactor-netty 전역 커넥션 풀)을 쓴다.**
     * 테스트만 전용 커넥터를 넣어 전역 풀을 공유하지 않게 한다 — `dedicatedConnector` 주석 참조.
     *
     * 생성자 인자가 아니라 [timeout]과 같은 `internal var`인 이유는 `EcosStatisticSearchClient`와
     * 같다: 이 클래스는 `@Component`라 생성자 인자를 두면 스프링이 `ClientHttpConnector` 빈을
     * **운영에서** 주입해 동작이 바뀐다.
     */
    internal var connector: ClientHttpConnector? = null

    fun fetch(seriesId: String, from: LocalDate, to: LocalDate): RateFetch {
        // 설정 누락은 서버 문제다 — NO_KEY로 던져 GlobalExceptionHandler가 500으로 내보내게 한다.
        // 502로 나가면 운영자가 멀쩡한 세인트루이스 연은을 확인하러 간다
        if (properties.apiKey.isBlank()) {
            throw FredApiException("NO_KEY", "FRED 인증키가 설정되지 않았습니다 (FRED_API_KEY)")
        }
        if (seriesId.isBlank()) {
            throw FredApiException("NO_SERIES", "FRED 시리즈 ID가 설정되지 않았습니다")
        }

        log.info("[FRED] 조회 seriesId={} {}~{}", seriesId, from, to)

        val body = try {
            webClient.get()
                .uri { b ->
                    b.path("/fred/series/observations")
                        .queryParam("series_id", seriesId)
                        .queryParam("api_key", properties.apiKey)
                        .queryParam("file_type", "json")
                        // LocalDate.toString()이 FRED가 받는 ISO 형식(yyyy-MM-dd)이다.
                        // ECOS의 yyyyMMdd와 다르므로 그쪽 포맷터를 가져오지 말 것 — 조용히 0건이 된다
                        .queryParam("observation_start", from)
                        .queryParam("observation_end", to)
                        .build()
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(timeout)
                ?: throw FredApiException("EMPTY", "응답 본문이 비어 있습니다")
        } catch (e: FredApiException) {
            throw e
        } catch (e: WebClientResponseException) {
            // 상태만 남긴다. 본문에는 우리 요청 URL이 되울려 올 수 있고 거기 키가 들어 있다.
            // cause도 붙이지 않는다 — Reactor checkpoint 프레임에 URI가 통째로 있다
            log.warn("[FRED] HTTP {} seriesId={}", e.statusCode.value(), seriesId)
            throw FredApiException("HTTP-${e.statusCode.value()}", "FRED가 HTTP ${e.statusCode.value()} 를 반환했습니다")
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) Thread.currentThread().interrupt()
            log.warn("[FRED] 호출 실패 seriesId={} reason={}", seriesId, e.javaClass.simpleName)
            throw FredApiException("IO", "FRED 호출에 실패했습니다")
        }

        // 파서는 JSON 모양이 어긋난 경우만 FredApiException으로 보고한다. 본문이 아예 JSON이 아니면
        // (점검 안내 HTML 등) Jackson 예외가 그대로 새는데, 그 메시지에는 원본 본문이
        // `[Source: (String)"..."]`로 붙어 있다 — 기본 오류 페이지가 요청 URI를 렌더링하면
        // 거기 `api_key=`가 들어 있다. 이 예외 메시지는 `RateCollectSummary.failures`를 타고
        // 어드민 응답까지 나가므로 여기서 갈아끼운다. cause도 붙이지 않는다(같은 본문을 물고 있다).
        //
        // 본문 미리보기는 로그에도 남기지 않는다. ECOS 쪽은 키 마스킹 + 요청 URI 제거 두 단계를
        // 거쳐 미리보기를 남기지만, 그건 키가 경로에 있어 정규식으로 통째 지울 수 있어서다.
        // 쿼리 파라미터는 인코딩 형태가 여러 가지라 같은 보장을 못 하므로, 진단 이득보다 유출 위험을 크게 본다.
        return try {
            parser.parse(body, RateValuePolicy.PERCENT)
        } catch (e: JsonProcessingException) {
            log.warn("[FRED] 응답이 JSON이 아닙니다 seriesId={} reason={}", seriesId, e.javaClass.simpleName)
            throw FredApiException("MALFORMED", "응답 본문이 올바른 JSON이 아닙니다")
        }
    }

    companion object {
        private val DEFAULT_TIMEOUT = Duration.ofSeconds(30)
    }
}
