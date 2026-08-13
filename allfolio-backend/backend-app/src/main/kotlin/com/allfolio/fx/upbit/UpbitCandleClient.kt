package com.allfolio.fx.upbit

import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ClientHttpConnector
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
class UpbitCandleClient(
    baseUrl: String,
    /**
     * HTTP 커넥터. **운영은 null로 두고 기본값을 쓴다** — reactor-netty의 JVM 전역 커넥션 풀
     * (`reactor.netty.http.HttpResources`)이다. 실제 Upbit은 오래 사는 서버라
     * 커넥션 재사용이 이득이고, 페이지를 연달아 던지는 백필에서는 특히 그렇다.
     *
     * 이 자리가 있는 이유는 **테스트다**. 전역 풀은 JVM 하나를 쓰는 테스트 태스크 전체가
     * 공유하는데, 스텁 서버들은 임시 포트에 떴다가 테스트마다 죽는다. 죽은 서버의 커넥션이
     * 풀에서 지워지는 건 네티 이벤트 루프에서 비동기로 일어나므로, 루프가 바쁠 때 뒤늦은
     * 임시 포트 재사용과 겹치면 **이미 닫힌 소켓을 풀이 내주고** 요청이 그리로 나간다
     * (`Connection prematurely closed BEFORE response`). `UpbitCandleRateSourceTest` 참조.
     */
    connector: ClientHttpConnector? = null,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(512 * 1024) }
            .also { builder -> connector?.let(builder::clientConnector) }
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
