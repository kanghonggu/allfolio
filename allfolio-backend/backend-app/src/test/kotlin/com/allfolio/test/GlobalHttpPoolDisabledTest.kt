package com.allfolio.test

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Collections

/**
 * [NoPooledHttpConnectionsListener]가 **실제로 돌고 있는지**를 지킨다.
 *
 * 그물은 조용히 걷히면 아무도 모른다 — services 파일 오타 하나, 리스너 자동등록 비활성화
 * (`junit.platform.launcher.listeners.autoRegistration.enabled=false`), 클래스 이름 변경이면
 * 끝이다. 그때 잃는 건 컴파일 오류가 아니라 **몇 달 뒤 남의 PR에서 터지는 간헐 실패**다.
 *
 * 그래서 설정을 읽어 확인하지 않고 **행동으로** 확인한다: 커넥터를 지정하지 않은 평범한
 * `WebClient`로 [CALLS]번 요청하고, 서버가 본 클라이언트 포트가 **전부 다른지**를 본다.
 *
 *  - 풀링이 꺼져 있으면 요청마다 새 소켓을 연다 → 포트가 [CALLS]개 **전부 다르다**
 *  - 풀링이 켜져 있으면 커넥션을 돌려 쓴다 → 포트 가짓수가 [CALLS]보다 **적다**
 *
 * "포트가 두 개면 재사용한 것"이라고 쓰면 안 된다. 순차 호출이어도 reactor-netty는 커넥션
 * 하나로 수렴하지 않는다 — `block()`이 풀에 반납하기 직전에 다음 요청이 들어와 두 번째
 * 커넥션을 여는 일이 흔하다. 실측으로 4회 요청에 커넥션 2개를 번갈아 썼다. 그래서 판별
 * 기준은 "가짓수가 2인가"가 아니라 **"요청 수만큼인가"**다.
 *
 * 커넥터를 일부러 안 넣는 것이 이 테스트의 핵심이다. [dedicatedConnector]를 넣으면 전역
 * 설정과 무관하게 통과해버려서 그물이 걷힌 걸 못 잡는다.
 */
class GlobalHttpPoolDisabledTest {

    private companion object {
        /** 풀링이 켜졌을 때와 가짓수가 확실히 갈리도록 넉넉히 부른다(실측: 풀링 시 2개 안팎). */
        const val CALLS = 6
    }

    private lateinit var server: HttpServer
    private val clientPorts = Collections.synchronizedList(mutableListOf<Int>())

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            clientPorts += exchange.remoteAddress.port
            val bytes = "{}".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `커넥터를 지정하지 않은 WebClient도 커넥션을 재사용하지 않는다`() {
        // 전역 기본값을 그대로 쓰는 클라이언트다 — 여기에 커넥터를 넣으면 검사 의미가 사라진다.
        val client = WebClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build()

        repeat(CALLS) {
            client.get().uri("/ping").retrieve()
                .bodyToMono(String::class.java).block(Duration.ofSeconds(10))
        }

        assertThat(clientPorts).hasSize(CALLS)
        assertThat(clientPorts.toSet())
            .describedAs(
                "포트 가짓수가 요청 수보다 적다 = 커넥션을 돌려 썼다 = 전역 풀이 살아 있다. " +
                    "NoPooledHttpConnectionsListener가 등록돼 실제로 도는지 확인할 것 (ports=%s)",
                clientPorts,
            )
            .hasSize(CALLS)
    }
}
