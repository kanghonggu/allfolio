package com.allfolio.fx.upbit

import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * Upbit 일봉 조회. HTTP만 한다 — 파싱은 [UpbitCandleParser].
 *
 * `GET /v1/candles/days?market=KRW-{SYM}&to={ISO8601}&count={n}` — 무인증, 무료.
 * 레이트리밋은 `remaining-req: group=candles; min=600; sec=9`.
 *
 * 코덱을 512KB로 두는 이유: 200건 × 캔들당 약 300B면 60KB 남짓이라 여유가 넉넉하다.
 */
class UpbitCandleClient(baseUrl: String) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(512 * 1024) }
            .build()
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(10)
    }

    /** @param to 배타적 상한. 이 시각 **이전** 캔들만 온다. */
    fun fetchDays(market: String, to: String, count: Int): String =
        try {
            webClient.get()
                .uri { b -> b.path("/v1/candles/days").queryParam("market", market)
                    .queryParam("to", to).queryParam("count", count).build() }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(TIMEOUT)
                ?: throw UpbitCandleException("Upbit 일봉 응답 본문이 비어 있습니다")
        } catch (e: UpbitCandleException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // block(TIMEOUT)의 타임아웃은 WebClientException이 아니라 IllegalStateException으로
            // 새로 던져진다. 예외 종류를 열거하면 그런 경로가 샌다.
            log.warn("[UpbitCandle] 호출 실패 market={} to={} reason={}", market, to, e.javaClass.simpleName)
            throw UpbitCandleException("Upbit 일봉 호출에 실패했습니다", e)
        }
}
