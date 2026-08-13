package com.allfolio.fx.upbit

import com.allfolio.test.dedicatedConnector
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.Collections

/**
 * **페이지네이션을 못 박는다.**
 *
 * Upbit은 요청당 200건만 준다 — count=201도 count=500도 조용히 200건만 돌아온다(실측).
 * 즉 200일이 넘는 구간에서 페이지를 안 넘기면 **오래된 쪽이 조용히 비어** 그 날짜의
 * 현금흐름이 계속 현재가 폴백으로 떨어진다. 오류도 로그도 없이.
 */
class UpbitCandleRateSourceTest {

    private lateinit var server: HttpServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    /** 요청받은 `to`에 따라 200건씩 내려주는 가짜 Upbit. 날짜만 있으면 되므로 가격은 고정한다. */
    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.query ?: ""
            requests += query
            val to = runCatching { kstDateOf(query) }.getOrDefault(LocalDate.of(2026, 8, 13))

            // to는 배타적이라 to-1일부터 200일치를 내림차순으로 만든다
            val body = (0 until 200).joinToString(",") { i ->
                val d = to.minusDays(1L + i)
                """{"candle_date_time_kst":"${d}T09:00:00","trade_price":90000000.0}"""
            }
            val bytes = "[$body]".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * 커서(UTC Z)를 그것이 뜻하는 **KST 날짜**로 되돌린다.
     *
     * 소스는 KST 자정을 UTC로 바꿔 보낸다(`+`를 그대로 보내면 Upbit이 400을 준다 — 실측).
     * 스텁도 실제 Upbit처럼 UTC를 받아 KST 경계로 해석해야 한다.
     */
    private fun kstDateOf(query: String): java.time.LocalDate =
        java.time.Instant.parse(Regex("to=([^&]+)").find(query)!!.groupValues[1])
            .atZone(java.time.ZoneOffset.ofHours(9)).toLocalDate()

    /**
     * 쿼리에서 `to` 값을 꺼내 **시각으로** 비교한다.
     *
     * 문자열 비교를 하지 않는 이유가 두 겹이다. WebClient가 `:`를 퍼센트 인코딩할지는 구현
     * 세부이고, 더 고약하게는 `URLDecoder.decode`가 리터럴 `+`를 **공백으로** 바꾼다
     * (form-urlencoded 규칙). `+09:00`이 ` 09:00`이 되어, 커서 계산이 완벽히 맞는데도
     * 단언만 깨진다 — 실제로 이 함정에 한 번 빠졌다.
     * 그래서 `+`를 먼저 보호한 뒤 디코드하고, OffsetDateTime으로 파싱해 값을 비교한다.
     */
    private fun toParam(query: String): java.time.Instant =
        java.time.Instant.parse(Regex("to=([^&]+)").find(query)!!.groupValues[1])

    /** 기대 커서: 그 KST 날짜의 자정을 UTC로 옮긴 시각 */
    private fun cursorOf(date: java.time.LocalDate): java.time.Instant =
        java.time.OffsetDateTime.of(date, java.time.LocalTime.MIDNIGHT, java.time.ZoneOffset.ofHours(9))
            .toInstant()

    /** 커넥터를 [dedicatedConnector]로 두는 이유는 그쪽 주석에 있다 — 빼면 간헐적으로 깨진다. */
    private fun source() = UpbitCandleRateSource(
        UpbitCandleClient("http://localhost:${server.address.port}", dedicatedConnector()),
        UpbitCandleParser(ObjectMapper()),
    )

    @Test
    fun `BTC와 ETH만 지원한다`() {
        val s = source()

        assertThat(s.supports("BTC")).isTrue()
        assertThat(s.supports("ETH")).isTrue()
        assertThat(s.supports("btc")).isTrue()
        assertThat(s.supports("USD")).isFalse()
        assertThat(s.supports("DOGE")).isFalse()
    }

    @Test
    fun `소스 이름은 UPBIT다`() {
        assertThat(source().sourceName).isEqualTo("UPBIT")
    }

    @Test
    fun `200일 이하 구간은 한 번만 요청한다`() {
        val to = LocalDate.of(2026, 8, 1)
        val from = to.minusDays(10)

        val fetched = source().fetch("BTC", from, to)

        assertThat(requests).hasSize(1)
        assertThat(fetched.rates.map { it.baseDate }).containsExactlyInAnyOrderElementsOf((0..10L).map { to.minusDays(it) })
    }

    @Test
    fun `to는 배타적이므로 요청에 to+1일을 싣는다`() {
        // to=2026-08-03T00:00 이 08-02까지 돌려주는 것을 실측했다. to 당일을 포함하려면 +1일.
        val to = LocalDate.of(2026, 8, 1)

        source().fetch("BTC", to.minusDays(3), to)

        assertThat(toParam(requests.single())).isEqualTo(cursorOf(LocalDate.of(2026, 8, 2)))
    }

    @Test
    fun `400일 구간은 두 번 요청하고 두 번째 to가 첫 페이지의 가장 오래된 날짜다`() {
        val to = LocalDate.of(2026, 8, 1)
        val from = to.minusDays(399)

        val fetched = source().fetch("BTC", from, to)

        assertThat(requests).hasSize(2)
        // 커서는 to+1에서 출발하므로 첫 페이지는 to ~ (to+1-200) = to-199 를 덮는다.
        // 따라서 두 번째 to는 to-199다. to-200으로 쓰면 하루가 조용히 빈다.
        assertThat(toParam(requests[1])).isEqualTo(cursorOf(to.minusDays(199)))
        assertThat(fetched.rates.map { it.baseDate }.min()).isEqualTo(from)
    }

    @Test
    fun `요청 구간 밖 날짜는 담지 않는다`() {
        val to = LocalDate.of(2026, 8, 1)
        val from = to.minusDays(5)

        val fetched = source().fetch("BTC", from, to)

        assertThat(fetched.rates.map { it.baseDate }).allMatch { it in from..to }
    }

    @Test
    fun `to가 뒤로 물러나지 않으면 중단한다 - 무한루프 방지`() {
        // 같은 페이지를 반복해 주는 서버. 방어가 없으면 영원히 돈다.
        server.removeContext("/")
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.query ?: ""
            val body = """[{"candle_date_time_kst":"2026-08-01T09:00:00","trade_price":9.0E7}]"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        assertThatThrownBy { source().fetch("BTC", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 8, 1)) }
            .isInstanceOf(UpbitCandleException::class.java)
            .hasMessageContaining("진행하지 못했습니다")
    }

    @Test
    fun `이력이 요청 구간보다 짧으면 채운 것만 돌려주고 경고를 남긴다`() {
        // Upbit에 그 이전 이력이 없는 경우(상장 이전 등). 예외는 아니지만 조용해서도 안 된다 —
        // 부분 결과가 완전한 성공과 구분되지 않으면 못 채운 날짜가 현재가 폴백으로 떨어진다.
        server.removeContext("/")
        // 거래소에 이 사흘치만 존재한다. **실제 Upbit처럼 커서보다 과거의 것만 돌려주고,
        // 더 없으면 빈 배열을 준다.** 커서를 무시하고 같은 페이지를 계속 주면 그건
        // "이력 소진"이 아니라 "고장난 서버"라서 no-progress 가드가 먼저 걸린다.
        val history = listOf(
            LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 20),
        )
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.query ?: ""
            requests += query
            val cursor = runCatching { kstDateOf(query) }.getOrDefault(LocalDate.of(2026, 8, 13))

            val body = history.filter { it < cursor }
                .joinToString(",") { """{"candle_date_time_kst":"${it}T09:00:00","trade_price":9.0E7}""" }
            val bytes = "[$body]".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val to = LocalDate.of(2026, 8, 1)
        val fetched = source().fetch("BTC", to.minusDays(300), to)

        // 있는 만큼은 돌려준다 (요청은 300일이었지만 사흘치만 존재한다)
        assertThat(fetched.rates.map { it.baseDate })
            .containsExactlyInAnyOrderElementsOf(history)
        // 2회 요청: 1회차에 사흘치, 2회차는 빈 배열로 "더 없음"을 알린다
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `최대 페이지를 넘기면 예외 - 부분 결과를 성공처럼 돌려주지 않는다`() {
        // 한 번에 한 건씩만 주는 서버. 100페이지를 넘겨도 from에 닿지 못한다.
        // WARN만 남기고 부분 결과를 돌려주면 "덜 채웠다"가 조용히 성공이 된다.
        server.removeContext("/")
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.query ?: ""
            requests += query
            val to = runCatching { kstDateOf(query) }.getOrDefault(LocalDate.of(2026, 8, 13))
            val d = to.minusDays(1)
            val bytes = """[{"candle_date_time_kst":"${d}T09:00:00","trade_price":9.0E7}]"""
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val to = LocalDate.of(2026, 8, 1)

        assertThatThrownBy { source().fetch("BTC", to.minusDays(500), to) }
            .isInstanceOf(UpbitCandleException::class.java)
            .hasMessageContaining("최대치")
    }

    @Test
    fun `빈 응답이면 더 요청하지 않고 모은 것만 돌려준다`() {
        server.removeContext("/")
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.query ?: ""
            val bytes = "[]".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val fetched = source().fetch("BTC", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 1))

        assertThat(fetched.rates).isEmpty()
        assertThat(requests).hasSize(1)
    }
}
