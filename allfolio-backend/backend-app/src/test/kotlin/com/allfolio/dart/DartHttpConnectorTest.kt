package com.allfolio.dart

import io.netty.handler.ssl.SslContextBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **이 테스트가 지키는 것은 하나다 — 암호군에 DHE가 남아 있는가.**
 *
 * 클라이언트 테스트는 전부 JDK 내장 `HttpServer` 루프백 스텁이라 실제 TLS를 안 탄다.
 * 그래서 "OpenDART와 협상 가능한 암호군이 하나도 없다"는 상태를 어떤 테스트도 못 잡았고,
 * 운영 첫 수집에서 502로 드러났다(2026-08-19).
 *
 * 실제 핸드셰이크를 테스트에서 하지는 않는다 — 외부 의존이라 CI가 상류 사정에 흔들린다.
 * 대신 **원인이었던 조건 자체**를 못 박는다: 기본 목록에 DHE가 없다는 것과,
 * 우리가 그것을 더한다는 것.
 */
class DartHttpConnectorTest {

    private val dheGcm = listOf(
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
    )

    @Test
    fun `netty 기본 암호군에는 DHE가 없다 — 이것이 사고의 원인이었다`() {
        // 이 단언이 언젠가 깨진다면 netty가 기본값을 바꾼 것이다. 그때는 우리 추가분이
        // 불필요해졌는지 다시 판단할 것 — 지우기 전에 OpenDART가 ECDHE를 켰는지 먼저 확인한다.
        val defaults = SslContextBuilder.forClient().build().cipherSuites()

        assertThat(defaults).isNotEmpty()
        assertThat(defaults).noneMatch { it.contains("_DHE_") }
    }

    @Test
    fun `커넥터가 기본 암호군을 지우지 않고 DHE만 더한다`() {
        // 기본 8개를 덮어쓰면 OpenDART가 나중에 ECDHE를 켰을 때 오히려 못 붙는다
        val defaults = SslContextBuilder.forClient().build().cipherSuites()
        val ciphers = SslContextBuilder.forClient()
            .ciphers(defaults + dheGcm)
            .build()
            .cipherSuites()

        assertThat(ciphers).containsAll(defaults)
        assertThat(ciphers).containsAll(dheGcm)
    }

    @Test
    fun `정적 RSA는 쓰지 않는다 — JDK가 막았고 전방향 비밀성이 없다`() {
        // OpenDART는 정적 RSA(AES128-GCM-SHA256)도 받지만 그쪽으로 가지 않는다.
        // 되살리려면 jdk.tls.disabledAlgorithms를 JVM 전역으로 낮춰야 하고,
        // 그러면 KIS·Binance 같은 실 키가 오가는 연결까지 같이 느슨해진다.
        assertThat(dheGcm).noneMatch { it.startsWith("TLS_RSA_") }
    }

    @Test
    fun `커넥터가 만들어진다`() {
        assertThat(DartHttpConnector.create()).isNotNull()
    }
}
