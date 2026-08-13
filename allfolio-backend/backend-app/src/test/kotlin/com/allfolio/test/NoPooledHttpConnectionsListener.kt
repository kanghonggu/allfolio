package com.allfolio.test

import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestPlan
import reactor.netty.http.HttpResources
import reactor.netty.resources.ConnectionProvider

/**
 * 테스트 JVM 전체에서 **HTTP 커넥션 풀링을 끈다.** 테스트가 시작되기 전에 딱 한 번 돈다.
 *
 * `META-INF/services/org.junit.platform.launcher.TestExecutionListener`로 등록돼 있어
 * Gradle이든 IDE든 JUnit Platform 런처가 자동으로 집어 간다.
 *
 * ## 왜 있나
 *
 * [dedicatedConnector]가 막는 그 사고를, **커넥터를 깜빡한 테스트에서도** 막으려는 그물이다.
 * 스텁 서버를 띄우는 테스트를 새로 쓰는 사람이 커넥터를 안 넣으면 그 테스트는 다시 전역 풀을
 * 쓰게 되고, 증상은 몇 달에 한 번 남의 PR에서 터진다. 리뷰로 잡기에는 너무 조용한 종류라
 * 기본값 자체를 안전한 쪽으로 옮긴다.
 *
 * `ConnectionProvider.newConnection()`은 요청마다 새 커넥션을 열고 끝나면 닫는다. 풀에
 * 남는 커넥션이 없으니 **죽은 소켓을 물려받을 커넥션 자체가 존재하지 않는다.**
 *
 * ## 한계 — 이것만 믿으면 안 된다
 *
 * 스프링 컨텍스트가 닫힐 때 `ReactorResourceFactory.destroy()`가
 * `HttpResources.disposeLoopsAndConnectionsLater()`를 부른다. 그러면 여기서 넣어둔 provider가
 * 버려지고, 그 뒤 `HttpResources.get()`은 **다시 기본 풀**을 만든다. 이 저장소에서는 컨텍스트가
 * 캐시 한도(32) 아래라 실행 도중 닫히지 않고 JVM 종료 때 닫히지만, 컨텍스트가 늘어 캐시가
 * 밀려나기 시작하면 이 그물은 조용히 걷힌다.
 *
 * 그래서 스텁 서버를 쓰는 테스트는 **여전히 [dedicatedConnector]를 명시적으로 넣는다.**
 * 이건 최후의 방어선이지 유일한 방어선이 아니다. 실제로 도는지는
 * [GlobalHttpPoolDisabledTest]가 지킨다.
 */
class NoPooledHttpConnectionsListener : TestExecutionListener {

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        HttpResources.set(ConnectionProvider.newConnection())
    }
}
