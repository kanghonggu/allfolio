package com.allfolio.fx.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * 소스가 **어떤 URL을 때리는지**를 못 박는다.
 *
 * Binance 클라이언트가 조용히 실패한 원인이 정확히 여기였다 — 파싱이 아니라
 * 존재하지 않는 심볼을 조회하는 URL. 파서 테스트로는 잡을 수 없는 계열이라 따로 둔다.
 */
class ExchangeFxSourceTest {

    private lateinit var server: HttpServer
    private val requestUri = AtomicReference<String>()
    @Volatile private var responseCode = 200
    @Volatile private var responseBody = ""

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            requestUri.set(exchange.requestURI.toString())
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(responseCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun baseUrl() = "http://localhost:${server.address.port}"

    private fun upbit() = UpbitFxSource(baseUrl(), UpbitFxParser(ObjectMapper()))
    private fun bithumb() = BithumbFxSource(baseUrl(), BithumbFxParser(ObjectMapper()))

    @Test
    fun `Upbit은 세 마켓을 한 번에 정확한 경로로 조회한다`() {
        responseBody = """
            [{"market":"KRW-USDT","trade_price":1409.0},
             {"market":"KRW-BTC","trade_price":89825000.0},
             {"market":"KRW-ETH","trade_price":2663000.0}]
        """.trimIndent()

        val rates = upbit().fetchKrwRates()

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
        assertThat(requestUri.get()).isEqualTo("/v1/ticker?markets=KRW-USDT,KRW-BTC,KRW-ETH")
    }

    @Test
    fun `Bithumb은 ALL_KRW를 정확한 경로로 조회한다`() {
        responseBody = """
            {"status":"0000","data":{
              "BTC":{"closing_price":"89880000"},
              "ETH":{"closing_price":"2664000"},
              "USDT":{"closing_price":"1410"},
              "date":"1786521700219"}}
        """.trimIndent()

        val rates = bithumb().fetchKrwRates()

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
        assertThat(requestUri.get()).isEqualTo("/public/ticker/ALL_KRW")
    }

    @Test
    fun `Upbit이 404를 주면 FxQuoteException - 잘못된 마켓의 실제 응답이다`() {
        responseCode = 404
        responseBody = """{"error":{"name":404,"message":"Code not found"}}"""

        assertThatThrownBy { upbit().fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `Bithumb은 HTTP 200이어도 status가 나쁘면 FxQuoteException`() {
        responseCode = 200
        responseBody = """{"status":"5500","message":"상장 코인이 아닙니다."}"""

        assertThatThrownBy { bithumb().fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `Upbit 본문이 비면 FxQuoteException - 파서까지 가지 않는다`() {
        assertThatThrownBy { upbit().fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("본문이 비어")
    }

    @Test
    fun `Bithumb 본문이 비면 FxQuoteException`() {
        assertThatThrownBy { bithumb().fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("본문이 비어")
    }

    @Test
    fun `Bithumb은 큰 ALL_KRW 응답도 받는다 - 코덱 한도 회귀 방지`() {
        // 실측 169KB. 기존 256KB 한도로는 상장이 늘면 조용히 깨진다.
        // 500KB짜리 응답을 만들어 1MB 상향이 실제로 먹는지 확인한다.
        // (필러 1500개는 약 187KB밖에 안 돼 아래 256KB 체크 자체가 항상 실패했다 — 4000개로 상향)
        val filler = (1..4000).joinToString(",") {
            """"FILLER$it":{"opening_price":"1","closing_price":"1","min_price":"1","max_price":"1","units_traded":"1","acc_trade_value":"1"}"""
        }
        responseBody = """{"status":"0000","data":{$filler,"USDT":{"closing_price":"1410"},"date":"1"}}"""
        check(responseBody.length > 256 * 1024) { "픽스처가 기존 한도보다 커야 의미가 있다: ${responseBody.length}" }

        assertThat(bithumb().fetchKrwRates()).containsOnlyKeys("USDT")
    }

    @Test
    fun `소스 이름은 로그에서 구분되도록 고정한다`() {
        assertThat(upbit().sourceName).isEqualTo("UPBIT")
        assertThat(bithumb().sourceName).isEqualTo("BITHUMB")
    }
}
