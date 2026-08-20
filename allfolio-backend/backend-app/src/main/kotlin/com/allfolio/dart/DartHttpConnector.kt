package com.allfolio.dart

import io.netty.handler.ssl.SslContextBuilder
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import reactor.netty.http.client.HttpClient

/**
 * OpenDART 전용 HTTP 커넥터. **암호군에 DHE를 더한 것 말고는 기본값 그대로다.**
 *
 * ## 왜 필요한가
 *
 * reactor-netty의 기본 암호군은 **8개이고 전부 ECDHE 아니면 TLS 1.3**이다. DHE도 정적 RSA도
 * 하나도 없다(실측):
 *
 * ```
 * TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384   TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
 * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256   TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA
 * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256     TLS_AES_128_GCM_SHA256
 * TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384     TLS_AES_256_GCM_SHA384
 * ```
 *
 * 그런데 `opendart.fss.or.kr`은 **ECDHE를 전부 거절하고 TLS 1.3도 지원하지 않는다.** 받아주는
 * 것은 정적 RSA와 DHE뿐이다(실측, 2026-08-19):
 *
 * ```
 * ECDHE-RSA-AES128-GCM-SHA256   거절      DHE-RSA-AES128-GCM-SHA256   수용
 * ECDHE-RSA-AES256-GCM-SHA384   거절      AES128-GCM-SHA256(정적 RSA)  수용
 * TLS 1.3                       미지원     최고 버전은 TLS 1.2
 * ```
 *
 * **교집합이 비어서** 서버가 `handshake_failure` 알림을 보내고 연결이 끊긴다. 증상은
 * `SSLHandshakeException: (handshake_failure) Received fatal alert: handshake_failure`이고,
 * 우리 예외 메시지는 방어 셋 때문에 원인을 안 싣는다(`DartApiException: 호출에 실패했습니다`).
 *
 * ## 왜 DHE인가 — 정적 RSA가 아니라
 *
 * JDK 21의 `jdk.tls.disabledAlgorithms`에 **`TLS_RSA_*`가 들어 있다**(전방향 비밀성이 없어서다).
 * 그걸 되살리려면 JVM 전역 보안 설정을 낮춰야 하고, 그러면 KIS·Binance처럼 실 API 키가 오가는
 * 연결의 TLS까지 같이 느슨해진다. **DHE는 전방향 비밀성이 있고 JDK가 막지 않는다** — 목록에
 * 두 개만 더하면 된다.
 *
 * ## 왜 커넥터를 공용으로 두는가
 *
 * DART 클라이언트가 셋(`DartListClient`·`DartCorpCodeClient`·`DartElestockClient`)이고 전부
 * 같은 호스트를 부른다. 세 파일에 같은 설정을 복사하면 **한 곳만 고쳐지는 사고**가 난다.
 *
 * ## 왜 이걸 테스트가 못 잡았나
 *
 * 클라이언트 테스트는 전부 JDK 내장 `HttpServer` 루프백 스텁이라 **실제 TLS를 한 번도 안 탄다.**
 * 선행 실측(S0·S1)은 `curl`로 했는데 OpenSSL은 아직 정적 RSA를 허용해서 통과했다.
 * 코드가 아니라 **라이브러리 기본값과 상대 서버 설정의 조합**이라 리뷰로도 안 보였다.
 * 운영 첫 수집에서 502로 드러났다.
 *
 * 다른 수집처는 멀쩡하다 — 공공데이터포털(`apis.data.go.kr`)은 TLS 1.3을 쓴다. OpenDART만 유별나다.
 */
object DartHttpConnector {

    /**
     * Netty 기본 목록에 더할 DHE 암호군. **GCM만 넣는다** — CBC도 서버가 받지만 GCM이 있으면
     * 쓸 이유가 없다. 순서는 의미 없다(협상은 서버 선호가 결정한다).
     */
    private val DHE_CIPHERS = listOf(
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
    )

    /**
     * 기본 암호군 + DHE. **기본을 덮어쓰지 않고 더한다** — 다른 8개를 지우면 OpenDART가
     * 언젠가 ECDHE를 켰을 때 오히려 못 붙는다.
     */
    fun create(): ClientHttpConnector {
        val ciphers = SslContextBuilder.forClient().build().cipherSuites() + DHE_CIPHERS
        val sslContext = SslContextBuilder.forClient().ciphers(ciphers).build()
        return ReactorClientHttpConnector(HttpClient.create().secure { it.sslContext(sslContext) })
    }
}
