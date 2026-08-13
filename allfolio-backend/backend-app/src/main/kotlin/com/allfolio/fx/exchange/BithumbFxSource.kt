package com.allfolio.fx.exchange

import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

/**
 * Bithumb KRW 시세 소스 (폴백). ALL_KRW로 USDT·BTC·ETH를 한 번에 가져온다.
 *
 * `GET /public/ticker/USDT_KRW` — 무인증, 무료.
 *
 * 폴백을 두는 이유는 이번 사고의 본질이 "단일 소스가 조용히 죽었다"는 것이기 때문이다.
 * 2026-08-12 기준 두 소스 값이 1408 vs 1409로 일치해 상호 검증 역할도 한다.
 *
 * 오류 판정은 [BithumbFxParser]가 status로 한다 — 이 API는 실패도 HTTP 200으로 준다.
 */
class BithumbFxSource(
    baseUrl: String,
    private val parser: BithumbFxParser,
    /** 운영은 null(= reactor-netty 전역 풀). 테스트만 전용 커넥터를 넣는다 — `dedicatedConnector` 주석 참조. */
    connector: ClientHttpConnector? = null,
) : FxQuoteSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val sourceName = "BITHUMB"

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            // ALL_KRW는 상장 전 종목을 다 준다 — 2026-08-12 실측 169KB(480종목, 종목당 약 350B).
            // 기존 256KB로는 여유가 1.5배뿐이라 상장이 265개만 늘어도 조용히 실패한다.
            .codecs { it.defaultCodecs().maxInMemorySize(1024 * 1024) }
            .also { builder -> connector?.let(builder::clientConnector) }
            .build()
    }

    companion object {
        private const val PATH = "/public/ticker/ALL_KRW"
        private val TIMEOUT = Duration.ofSeconds(5)
    }

    override fun fetchKrwRates(): Map<String, BigDecimal> {
        val body = try {
            webClient.get()
                .uri(PATH)
                .retrieve()
                .bodyToMono(String::class.java)
                .block(TIMEOUT)
                ?: throw FxQuoteException("Bithumb 응답 본문이 비어 있습니다")
        } catch (e: FxQuoteException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            log.warn("[BithumbFx] 호출 실패 reason={}", e.javaClass.simpleName)
            throw FxQuoteException("Bithumb 호출에 실패했습니다", e)
        }

        return parser.parse(body)
    }
}
