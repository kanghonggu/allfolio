package com.allfolio.config

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import javax.sql.DataSource

/**
 * application.yml의 HikariCP 설정이 실제로 DataSource에 바인딩되는지 검증한다.
 *
 * 과거 이 블록은 `spring.hikari`에 있었고(= 바인딩 prefix가 `spring.datasource.hikari`이므로)
 * 전량 무시된 채 HikariCP 기본값으로 동작했다. 이 테스트는 그 회귀를 막는다.
 *
 * HikariCP 풀은 첫 getConnection() 시점에 지연 초기화되므로, 설정값 확인에는 실제 DB가 필요 없다.
 */
@SpringBootTest(classes = [HikariPoolConfigTest.TestApplication::class])
class HikariPoolConfigTest {

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `application_yml의 hikari 설정이 HikariDataSource에 실제로 적용된다`() {
        val hikari = dataSource as HikariDataSource

        // 진단용 출력 — 실패 시 실효값을 바로 확인할 수 있도록 남겨둔다.
        println(
            """
            |=== 실효 HikariCP 설정 ===
            |poolName          = ${hikari.poolName}
            |maximumPoolSize   = ${hikari.maximumPoolSize}
            |minimumIdle       = ${hikari.minimumIdle}
            |connectionTimeout = ${hikari.connectionTimeout}
            |idleTimeout       = ${hikari.idleTimeout}
            |maxLifetime       = ${hikari.maxLifetime}
            |keepaliveTime     = ${hikari.keepaliveTime}
            """.trimMargin(),
        )

        // poolName은 미설정 시 null(풀 기동 후 "HikariPool-N")이므로
        // 바인딩이 실제로 일어났는지 가리는 가장 확실한 지표다.
        assertThat(hikari.poolName).isEqualTo("allfolio-pool")
        assertThat(hikari.maximumPoolSize).isEqualTo(10)
        assertThat(hikari.minimumIdle).isEqualTo(10)
        assertThat(hikari.connectionTimeout).isEqualTo(30_000)
        assertThat(hikari.idleTimeout).isEqualTo(600_000)
        assertThat(hikari.maxLifetime).isEqualTo(1_800_000)

        // Neon scale-to-zero를 무력화하지 않도록 keepalive는 꺼둔 상태를 유지한다.
        assertThat(hikari.keepaliveTime).isEqualTo(0)

        // JDBC4 isValid()를 쓰도록 test query는 두지 않는다.
        assertThat(hikari.connectionTestQuery).isNull()
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration(DataSourceAutoConfiguration::class)
    class TestApplication
}
