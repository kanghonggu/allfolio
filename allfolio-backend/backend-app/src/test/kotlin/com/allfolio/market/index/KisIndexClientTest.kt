package com.allfolio.market.index

import com.allfolio.broker.kis.KisApiClient
import com.allfolio.broker.kis.KisApiException
import com.allfolio.broker.kis.KisProperties
import com.allfolio.broker.kis.KisTokenResponse
import com.allfolio.test.dedicatedConnector
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * KIS 지수 클라이언트의 토큰 캐시 (AF-101 후속).
 *
 * **가짜 응답을 밀어 넣는 대신 진짜 HTTP를 태운다.** JDK 내장 [HttpServer]를 루프백에 띄우고
 * 그쪽을 baseUrl로 준다. 토큰 획득만 떼어내 직접 부르는 테스트는 `fetchRaw`가 그 경로를
 * 실제로 쓰는지를 증명하지 못하고, 반대로 baseUrl을 죽은 주소로 두고 "발급 횟수만" 세는
 * 테스트는 요청이 실제로 그 토큰을 달고 나갔는지를 못 본다. 여기서는 [FakeKis]가 받은
 * `authorization` 헤더를 그대로 기록하므로, **몇 번 발급했는지와 어떤 토큰으로 나갔는지를
 * 같이** 확인한다. 외부로 나가는 통신은 없다(127.0.0.1, 포트는 OS가 준다).
 *
 * 토큰 발급기만 Mockito mock이다 — `issueToken()`은 인자가 없어 이 저장소가 두 번 물린
 * `any()`가 null을 돌려주는 함정에 걸리지 않고, 호출 횟수는 `verify(times(n))`로 센다.
 */
class KisIndexClientTest {

    private lateinit var kis: FakeKis

    @BeforeEach
    fun startServer() {
        kis = FakeKis()
    }

    @AfterEach
    fun stopServer() {
        kis.stop()
    }

    @Test
    fun `토큰은 지수마다가 아니라 한 번만 발급된다`() {
        // 2026-08-13 운영 장애 그대로: 지수마다 발급하면 KIS가 분당 1회 제한으로 2번째부터
        // 403을 준다. 세 지수가 한 토큰을 나눠 쓰는지를 여기서 붙잡는다.
        val issuer = issuer(token("T1", expiresIn = 86_400))
        val client = indexClient(properties(), issuer)

        assertThat(client.fetchRaw("0001")).containsEntry("bstp_nmix_prpr", "6579.04")
        assertThat(client.fetchRaw("1001")).containsEntry("bstp_nmix_prpr", "6579.04")

        verify(issuer, times(1)).issueToken()
        assertThat(kis.authHeaders).containsExactly("Bearer T1", "Bearer T1")
    }

    @Test
    fun `캐시가 만료되면 다시 발급한다`() {
        // expires_in이 안전 여유(60초)보다 짧으면 캐시는 이미 만료된 상태로 들어간다.
        // 24시간을 상수로 박아둔 구현이라면 여기서 T1을 계속 재사용해 죽은 토큰을 내준다.
        val issuer = issuer(token("T1", expiresIn = 30), token("T2", expiresIn = 30))
        val client = indexClient(properties(), issuer)

        client.fetchRaw("0001")
        client.fetchRaw("1001")

        verify(issuer, times(2)).issueToken()
        assertThat(kis.authHeaders).containsExactly("Bearer T1", "Bearer T2")
    }

    @Test
    fun `expires_in이 없으면 짧은 기본 수명으로 잡되 한 바퀴는 한 토큰으로 돈다`() {
        // 0은 "24시간"이 아니라 "모른다"다. 모르는 값을 길게 잡으면 죽은 토큰을 오래 쓰게 되고,
        // 아예 캐시를 접으면 그 순간 원래 장애로 돌아간다 — 짧게 잡고 재사용한다.
        val issuer = issuer(token("T1", expiresIn = 0))
        val client = indexClient(properties(), issuer)

        client.fetchRaw("0001")
        client.fetchRaw("1001")

        verify(issuer, times(1)).issueToken()
        assertThat(kis.authHeaders).containsExactly("Bearer T1", "Bearer T1")
    }

    @Test
    fun `발급이 실패해도 캐시에 남지 않아 다음 호출이 다시 시도한다`() {
        // AF-100의 함정 — "결과가 아닌 것"을 기억하면 한 번의 실패가 프로세스 수명 내내 굳는다.
        val issuer = issuer(
            { throw KisApiException("403 Forbidden from POST /oauth2/tokenP") },
            { token("T2", expiresIn = 86_400) },
        )
        val client = indexClient(properties(), issuer)

        // 발급 예외는 감싸지 않고 그대로 올라간다 — IndexCollectService가 그 메시지를
        // failures에 담아야 운영에서 원인(403)이 보인다.
        assertThatThrownBy { client.fetchRaw("0001") }
            .isInstanceOf(KisApiException::class.java)
            .hasMessageContaining("403")
        assertThat(kis.authHeaders).isEmpty()

        assertThat(client.fetchRaw("1001")).containsEntry("bstp_nmix_prpr", "6579.04")

        verify(issuer, times(2)).issueToken()
        assertThat(kis.authHeaders).containsExactly("Bearer T2")
    }

    @Test
    fun `인증 정보가 없으면 토큰을 발급하지도 않는다`() {
        val issuer = issuer(token("T1", expiresIn = 86_400))
        val client = indexClient(KisProperties().apply { baseUrl = kis.baseUrl }, issuer)

        assertThatThrownBy { client.fetchRaw("0001") }
            .isInstanceOf(KisIndexException::class.java)
            .hasMessageContaining("KIS_APP_KEY")

        verify(issuer, times(0)).issueToken()
    }

    // ── helpers ──────────────────────────────────────────────────

    /** 커넥터를 [dedicatedConnector]로 두는 이유는 그쪽 주석에 있다 — 빼면 간헐적으로 깨진다. */
    private fun indexClient(properties: KisProperties, issuer: KisApiClient) =
        KisIndexClient(properties, issuer).apply { connector = dedicatedConnector() }

    private fun properties() = KisProperties().apply {
        appKey = "key"
        appSecret = "secret"
        baseUrl = kis.baseUrl
    }

    private fun token(accessToken: String, expiresIn: Long) =
        KisTokenResponse(accessToken = accessToken, expiresIn = expiresIn)

    private fun issuer(vararg answers: KisTokenResponse): KisApiClient =
        issuer(*answers.map { r -> { r } }.toTypedArray())

    /** 호출 순서대로 답을 돌려주는 발급기. 마지막 답은 그 뒤로도 계속 쓰인다 */
    private fun issuer(vararg answers: () -> KisTokenResponse): KisApiClient {
        val calls = AtomicInteger()
        val client = mock(KisApiClient::class.java)
        org.mockito.Mockito.`when`(client.issueToken()).thenAnswer {
            answers[minOf(calls.getAndIncrement(), answers.size - 1)]()
        }
        return client
    }

    /**
     * KIS 지수 엔드포인트를 흉내내는 루프백 서버. 받은 `authorization` 헤더를 순서대로 남긴다.
     * 본문은 2026-08-12 실측 KOSPI 응답의 형태를 줄인 것으로, 여기서 파싱을 검증하진 않는다
     * (그건 KisIndexParserTest의 몫).
     */
    private class FakeKis {
        val authHeaders = mutableListOf<String>()

        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/uapi/domestic-stock/v1/quotations/inquire-index-price") { exchange ->
                synchronized(authHeaders) {
                    authHeaders += exchange.requestHeaders.getFirst("authorization") ?: "(없음)"
                }
                val body = """{"rt_cd":"0","output":{"bstp_nmix_prpr":"6579.04"}}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)
    }
}
