package com.allfolio.realasset.watch

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 진짜 HTTP로 상류를 흉내 낸다(JDK 내장 HttpServer — 새 의존성 없음).
 *
 * 목 클라이언트로는 이 클래스가 하는 일(상태 코드·타임아웃·본문을 Found/Unavailable로 가르기)을
 * 하나도 못 잰다. 그래서 여기서는 WebClient까지 실제로 태운다.
 *
 * 🔴 클래스 전체에 시간 제한을 건다. `.timeout()`을 빼는 변이를 넣었더니 "연결 자체가 안 되면"
 * 테스트가 빨간불이 아니라 **멈춤**으로 나타났다(10분 대기 후 수동 종료). 상류가 응답 없이
 * 물고 있으면 등록 화면도 똑같이 멈춘다는 뜻이고, 그걸 빨간불로 바꾸려는 것이다.
 */
@Timeout(10)
class WatchRefSearchClientTest {

    private var server: HttpServer? = null
    private var lastQuery: String? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    private fun serve(status: Int, body: String, delayMs: Long = 0): WatchRefSearchClient {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/refs") { ex ->
            lastQuery = URLDecoder.decode(ex.requestURI.rawQuery ?: "", StandardCharsets.UTF_8)
            if (delayMs > 0) Thread.sleep(delayMs)
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        s.start()
        server = s
        return WatchRefSearchClient("http://127.0.0.1:${s.address.port}", 500)
    }

    @Test
    fun `200 후보 목록은 Found다`() {
        val client = serve(
            200,
            """[{"ref":"126500ln","refKey":"126500LN","brand":"Rolex","modelTitle":"Cosmograph Daytona","officialPriceKrw":23000000}]""",
        )

        val outcome = client.search("daytona", "롤렉스", 20)

        assertThat(outcome).isInstanceOf(RefSearchOutcome.Found::class.java)
        val c = (outcome as RefSearchOutcome.Found).candidates.single()
        assertThat(c.refKey).isEqualTo("126500LN")
        assertThat(c.ref).isEqualTo("126500ln")
        assertThat(c.officialPriceKrw).isEqualTo(23_000_000)
        // 쿼리는 값으로 파싱해 본다 — 인코딩 문자열로 단언하지 않는다
        assertThat(lastQuery!!.split("&")).containsExactlyInAnyOrder("q=daytona", "brand=롤렉스", "size=20")
    }

    @Test
    fun `200 빈 배열은 Found 빈 목록이다 — Unavailable이 아니다`() {
        val client = serve(200, "[]")

        val outcome = client.search("데이토나", null, 20)

        assertThat(outcome).isEqualTo(RefSearchOutcome.Found(emptyList()))
        assertThat(lastQuery!!.split("&")).containsExactlyInAnyOrder("q=데이토나", "size=20")
    }

    @Test
    fun `refKey 없는 항목은 버린다 — 확인 단계에 넘길 키가 없다`() {
        val client = serve(200, """[{"ref":"x","refKey":null},{"ref":"1603","refKey":"1603"}]""")

        val outcome = client.search("16", null, 20) as RefSearchOutcome.Found

        assertThat(outcome.candidates.map { it.refKey }).containsExactly("1603")
    }

    @Test
    fun `상류 5xx는 Unavailable이다`() {
        val client = serve(503, "")

        assertThat(client.search("daytona", null, 20)).isEqualTo(RefSearchOutcome.Unavailable("HTTP_503"))
    }

    @Test
    fun `상류 404는 엔드포인트 없음이라 Unavailable이다 — 후보 없음이 아니다`() {
        // 실측 2026-10-06: #51 배포 전 운영 /api/refs → 404. 이걸 [] 로 읽으면 배포 전 내내
        // 모든 검색이 "후보 없음"으로 보인다.
        val client = serve(404, """{"error":"Not Found"}""")

        assertThat(client.search("daytona", null, 20)).isEqualTo(RefSearchOutcome.Unavailable("HTTP_404"))
    }

    @Test
    fun `타임아웃은 Unavailable이다 — 등록 흐름이 상류를 기다리지 않는다`() {
        val client = serve(200, "[]", delayMs = 2_000)

        val started = System.nanoTime()
        val outcome = client.search("daytona", null, 20)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(outcome).isInstanceOf(RefSearchOutcome.Unavailable::class.java)
        // 타임아웃 500ms로 만들었다 — 상류 지연(2초)을 다 기다리지 않았는지 본다
        assertThat(elapsedMs).isLessThan(1_500)
    }

    @Test
    fun `연결 자체가 안 되면 Unavailable이다`() {
        // 아무도 듣지 않는 포트. 상류가 내려가 있을 때다.
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = s.address.port
        s.stop(0)
        val client = WatchRefSearchClient("http://127.0.0.1:$port", 500)

        assertThat(client.search("daytona", null, 20)).isInstanceOf(RefSearchOutcome.Unavailable::class.java)
    }

    @Test
    fun `200인데 배열이 아니면 Unavailable이다 — 후보 없음으로 읽지 않는다`() {
        val client = serve(200, """{"unexpected":true}""")

        assertThat(client.search("daytona", null, 20)).isInstanceOf(RefSearchOutcome.Unavailable::class.java)
    }
}
