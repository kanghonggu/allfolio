package com.allfolio.market.index

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest

/**
 * application.yml의 `market-index` 블록이 실제로 바인딩되는지 확인한다 (AF-101).
 *
 * 이 테스트가 있어야 하는 이유: YAML 오타는 컴파일 오류가 아니다.
 * 키 하나가 틀리면 [MarketIndexProperties.domestic]이 조용히 빈 리스트가 되고,
 * 수집 배치는 "0건 성공"으로 끝나 아무도 눈치채지 못한다.
 *
 * 값을 여기서 주입하지 않고 진짜 application.yml을 읽게 두는 것도 같은 이유다 —
 * properties로 덮어쓰면 검증 대상이 사라진다.
 */
@SpringBootTest(
    classes = [
        MarketIndexPropertiesTest.TestApplication::class,
        MarketIndexProperties::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
class MarketIndexPropertiesTest {

    @Autowired
    private lateinit var properties: MarketIndexProperties

    @Test
    fun `application yml의 국내 지수 목록이 바인딩된다`() {
        assertThat(properties.domestic.map { it.code }).containsExactly("KOSPI", "KOSDAQ", "KOSPI200")
        assertThat(properties.domestic.map { it.kisIscd }).containsExactly("0001", "1001", "2001")
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication
}
