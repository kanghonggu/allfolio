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
    private var responseCode = 200
    private var responseBody = ""

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
    fun `Upbit은 KRW-USDT 마켓을 정확한 경로로 조회한다`() {
        responseBody = """[{"market":"KRW-USDT","trade_price":1408.0}]"""

        val rate = upbit().fetchUsdtKrw()

        assertThat(rate).isEqualByComparingTo("1408.0")
        assertThat(requestUri.get()).isEqualTo("/v1/ticker?markets=KRW-USDT")
    }

    @Test
    fun `Bithumb은 USDT_KRW 심볼을 정확한 경로로 조회한다`() {
        responseBody = """{"status":"0000","data":{"closing_price":"1409"}}"""

        val rate = bithumb().fetchUsdtKrw()

        assertThat(rate).isEqualByComparingTo("1409")
        assertThat(requestUri.get()).isEqualTo("/public/ticker/USDT_KRW")
    }

    @Test
    fun `Upbit이 404를 주면 FxQuoteException - 잘못된 마켓의 실제 응답이다`() {
        responseCode = 404
        responseBody = """{"error":{"name":404,"message":"Code not found"}}"""

        assertThatThrownBy { upbit().fetchUsdtKrw() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `Bithumb은 HTTP 200이어도 status가 나쁘면 FxQuoteException`() {
        // 이 조합이 Bithumb의 실제 실패 형태다. retrieve()가 안 던지므로
        // 여기서 막지 못하면 조회 실패가 그대로 환율이 된다.
        responseCode = 200
        responseBody = """{"status":"5500","message":"상장 코인이 아닙니다."}"""

        assertThatThrownBy { bithumb().fetchUsdtKrw() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `소스 이름은 로그에서 구분되도록 고정한다`() {
        assertThat(upbit().sourceName).isEqualTo("UPBIT")
        assertThat(bithumb().sourceName).isEqualTo("BITHUMB")
    }
}
