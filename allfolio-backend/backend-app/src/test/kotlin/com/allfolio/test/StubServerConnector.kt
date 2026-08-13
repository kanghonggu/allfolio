package com.allfolio.test

import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider

/**
 * 루프백 스텁 서버에 붙는 테스트용 **전용 HTTP 커넥터**. 커넥션을 풀에 넣지 않는다.
 *
 * ## 왜 필요한가
 *
 * `WebClient.builder().build()`의 기본 커넥터는 reactor-netty의 **JVM 전역 커넥션 풀**
 * (`reactor.netty.http.HttpResources`)이다. 그런데 Gradle 테스트 태스크는 모듈 전체가
 * **JVM 하나**를 쓰고, 그 JVM에서 JDK `HttpServer` 스텁 수십 개가 임시 포트에 떴다가
 * 테스트마다 죽는다.
 *
 * 죽은 서버의 커넥션을 풀에서 지우는 일은 **네티 이벤트 루프에서 비동기로** 일어난다.
 * 루프가 바쁘면 그 정리가 늦고, 그 사이 커널이 같은 임시 포트를 새 스텁 서버에 다시 내주면
 * 풀은 **이미 닫힌 소켓**을 내준다. 요청은 죽은 소켓으로 나가고
 * `Connection prematurely closed BEFORE response`(또는 `Connection reset`)가 된다.
 *
 * 이래서 이 계열의 실패는 **클래스를 격리해 돌리면 재현되지 않는다** — 스텁이 적고 이벤트
 * 루프가 한가하기 때문이다. 전체 모듈 실행에서만, 그것도 드물게 나온다.
 *
 * ## 측정
 *
 * 임시 포트를 강제로 재사용시키고 이벤트 루프에 부하를 준 채 `UpbitCandleClient`로 1000회:
 *
 * | 커넥터 | 죽은 소켓으로 나간 실패 | 그중 위 메시지 |
 * | --- | --- | --- |
 * | 공유 전역 풀 | 15 | 1 |
 * | 이 전용 커넥터 | **0** | 0 |
 *
 * `reactor.netty.pool.maxIdleTime=1ms`로 풀을 살려두는 쪽도 재봤으나 오히려 나빠져(130회)
 * 버렸다. 재시도·sleep·비활성화가 아니라 **원인을 없애는** 쪽이다.
 *
 * ## 무엇을 잃나
 *
 * 커넥션 재사용을 잃는다. 이 테스트들이 검증하는 건 페이지네이션·파싱·헤더·마스킹이지
 * 커넥션 재사용이 아니므로 잃는 게 없다. 운영 코드는 그대로 전역 풀을 쓴다 — 실제 거래소·
 * 은행 API는 오래 사는 서버라 재사용이 이득이다.
 */
fun dedicatedConnector(): ClientHttpConnector =
    ReactorClientHttpConnector(HttpClient.create(ConnectionProvider.newConnection()))
