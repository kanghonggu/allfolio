package com.allfolio.fx.exchange

import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

/**
 * Upbit KRW-USDT 시세 소스 (주 소스).
 *
 * `GET /v1/ticker?markets=KRW-USDT` — 무인증, 무료.
 * 레이트리밋은 응답 헤더 `remaining-req: group=ticker; min=600; sec=8` 기준
 * 초당 10회·분당 600회다. 60초 폴링 대비 약 600배 여유.
 *
 * 국내 거래소를 쓰는 이유는 Binance에 KRW 마켓이 없어서만이 아니다.
 * 거래소 USDT를 KRW로 실현하는 실제 경로가 국내 거래소 매도이므로,
 * AF-99가 "실현 가능한 값"이라고 부른 것이 바로 이 시세다.
 */
class UpbitFxSource(
    baseUrl: String,
    private val parser: UpbitFxParser,
) : FxQuoteSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val sourceName = "UPBIT"

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(256 * 1024) }
            .build()
    }

    companion object {
        private const val PATH = "/v1/ticker?markets=KRW-USDT"
        private val TIMEOUT = Duration.ofSeconds(5)
    }

    override fun fetchUsdtKrw(): BigDecimal {
        val body = try {
            webClient.get()
                .uri(PATH)
                .retrieve()
                .bodyToMono(String::class.java)
                .block(TIMEOUT)
                ?: throw FxQuoteException("Upbit 응답 본문이 비어 있습니다")
        } catch (e: FxQuoteException) {
            // 위 "본문 비어 있음". 아래 Throwable 절이 메시지를 덮지 않도록 먼저 통과시킨다.
            throw e
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // block(TIMEOUT)의 타임아웃은 WebClientException이 아니라 IllegalStateException으로
            // 새로 던져진다. 예외 종류를 열거하면 그런 경로가 샌다.
            log.warn("[UpbitFx] 호출 실패 reason={}", e.javaClass.simpleName)
            throw FxQuoteException("Upbit 호출에 실패했습니다", e)
        }

        return parser.parse(body)
    }
}
