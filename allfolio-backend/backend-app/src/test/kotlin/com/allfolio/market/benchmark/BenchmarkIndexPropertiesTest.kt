package com.allfolio.market.benchmark

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest

/**
 * `application.yml`의 `benchmark-index` 블록이 실제로 바인딩되는지 확인한다.
 * `CommodityPropertiesYamlTest`(#178)를 그대로 따른다 — 이유도 같다.
 *
 * **이 테스트가 YAML 오타를 잡는 유일한 그물이다.** `idx-nm`이나 `idx-csf`가 한 글자만 틀려도
 * 응답 필터가 전 행을 걸러 **조용히 0건**이 되고, 요약은 `emptySeries=[KOSPI]` —
 * "정상적으로 빈 계열"처럼 보인다. 그래서 "비어 있지 않다"가 아니라 **문자열 값을 그대로** 단언한다.
 */
@SpringBootTest(
    classes = [
        BenchmarkIndexPropertiesTest.TestApplication::class,
        BenchmarkIndexProperties::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
class BenchmarkIndexPropertiesTest {

    @Autowired
    private lateinit var properties: BenchmarkIndexProperties

    /**
     * **KOSPI 한 종뿐인 것이 지금의 결정이다.** SPX·BTC는 Yahoo에 그대로 둔다 —
     * 옮겨도 KIS·Upbit 약관이 미결이라 얻는 게 없고 이력만 짧아진다.
     * 이 수가 늘었다면 `BenchmarkSyncService`에서 그 종목도 빼야 한다(두 소스가 같은 행을 덮는다).
     */
    @Test
    fun `KOSPI 한 종이 바인딩된다`() {
        assertThat(properties.fsc).hasSize(1)
        assertThat(properties.types).containsExactly("KOSPI")
    }

    /**
     * 실측(2026-08-17)으로 확정한 좌표 그대로다. 여기서 단언이 깨지면 **누가 설정을 고쳤다는
     * 뜻이지 테스트가 낡았다는 뜻이 아니다** — 포털에서 다시 확인하고 나서 고칠 것.
     *
     * **`idx-csf`가 값의 정확성을 좌우한다.** `idx-nm`은 유일하지 않아서(`"IT 서비스"`가
     * KOSPI시리즈·KOSDAQ시리즈에 둘 다 있다) 시리즈를 안 적으면 엉뚱한 지수가 그럴듯한 값으로
     * 저장된다. `type`은 `benchmark_daily.index_type`(=`BenchmarkType.name`)과 글자가 같아야 한다.
     */
    @Test
    fun `코스피 좌표가 확인한 값과 일치한다`() {
        val kospi = properties.fsc.single()

        assertThat(kospi.type).isEqualTo("KOSPI")
        assertThat(kospi.idxNm).isEqualTo("코스피")
        assertThat(kospi.idxCsf).isEqualTo("KOSPI시리즈")
    }

    /**
     * `idx-csf`를 빼먹으면 응답 필터가 전 행을 걸러 조용히 0건이 된다 —
     * 런타임에 흘리면 매일 "빈 계열" 한 줄이 쌓일 뿐이라 기동을 실패시킨다.
     */
    @Test
    fun `idx-csf가 비면 기동하지 않는다`() {
        val broken = BenchmarkIndexProperties().apply {
            fsc = listOf(
                BenchmarkIndexProperties.BenchmarkIndexItem().apply {
                    type = "KOSPI"
                    idxNm = "코스피"
                },
            )
        }

        assertThatThrownBy { broken.validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("KOSPI: idx-csf가 비어 있습니다")
    }

    /**
     * type이 겹치면 저장 키가 `(index_type, date)`라 뒤 항목이 앞 항목을 덮어쓸 뿐
     * 제약조건도 안 걸리고, 요약은 초록인 채 어느 쪽 값이 남았는지 알 수 없다.
     */
    @Test
    fun `type이 중복이면 기동하지 않는다`() {
        val broken = BenchmarkIndexProperties().apply {
            fsc = listOf(
                BenchmarkIndexProperties.BenchmarkIndexItem().apply {
                    type = "KOSPI"; idxNm = "코스피"; idxCsf = "KOSPI시리즈"
                },
                BenchmarkIndexProperties.BenchmarkIndexItem().apply {
                    type = "KOSPI"; idxNm = "코스닥"; idxCsf = "KOSDAQ시리즈"
                },
            )
        }

        assertThatThrownBy { broken.validate() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("KOSPI: type이 중복됩니다")
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication
}
