package com.allfolio.dart

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.BindHandler
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment

/**
 * application.yml의 `dart` 블록이 실제로 바인딩되는지 확인한다 (D1).
 * `MarketRatePropertiesYamlTest`(AF-102) · `CommodityPropertiesYamlTest`(AF-108)를 그대로 따른다 —
 * 이유도 같다.
 *
 * **계획서가 제안한 변이(`page-count` → `pageCount`)는 아무것도 못 잡는다.** 실측: YAML/properties
 * 소스에서 스프링 relaxed binding은 kebab-case와 camelCase를 동일한 키로 취급한다 — 애초에 오타가
 * 아니다. 진짜 오타(`page-count` → `page-cnt`, 다른 키)로 다시 실측하니 이번엔 값 단언 테스트
 * ([dart 블록이 확인한 값으로 바인딩된다])조차 통과했다: `baseUrl`·`pageCount`·`timeoutSeconds`
 * 기본값이 전부 yml에 쓸 값과 우연히 같아서(운영 URL, 100, 30), 키가 통째로 틀려도 필드가
 * 기본값으로 조용히 떨어지고 그 기본값이 마침 단언과 같다.
 *
 * 그래서 미결(unbound) 키 검사를 **테스트에서만** 별도로 한다.
 * 운영 클래스에 `ignoreUnknownFields = false`를 걸지 않는 이유는 [DartProperties] KDoc에 있다.
 */
@SpringBootTest(
    classes = [
        DartPropertiesYamlTest.TestApplication::class,
        DartProperties::class,
    ],
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
class DartPropertiesYamlTest {

    @Autowired
    private lateinit var properties: DartProperties

    @Autowired
    private lateinit var environment: Environment

    @Test
    fun `application yml의 dart 블록이 바인딩된다`() {
        assertThat(properties.baseUrl).isEqualTo("https://opendart.fss.or.kr/api")
        assertThat(properties.pageCount).isEqualTo(100)
        assertThat(properties.timeoutSeconds).isEqualTo(30)
    }

    /**
     * **위 테스트가 못 잡는 것을 여기서 잡는다.** `page-count`를 `page-cnt`로 잘못 적으면
     * `pageCount` 필드는 기본값(100)으로 조용히 떨어져 값 단언 테스트를 통과하지만,
     * `NoUnboundElementsBindHandler`는 `dart.page-cnt`가 [DartProperties]의 어떤 필드에도
     * 매핑되지 않는다는 것을 보고 `UnboundConfigurationPropertiesException`을 던진다.
     * 지금 이 테스트는 (정상 yml이므로) 예외가 **없어야** 통과한다 — 오타가 들어오는 순간 빨개진다.
     *
     * 이 검사를 [DartProperties] 자체에 `ignoreUnknownFields = false`로 걸지 않는 이유는
     * 클래스 KDoc에 적었다 — 운영 환경변수 하나가 기동을 죽이는 대가를 치르지 않고
     * 여기, 테스트에서만 같은 그물을 친다.
     */
    @Test
    fun `dart 블록에 알 수 없는 키가 없다`() {
        assertThatCode {
            Binder.get(environment)
                .bind("dart", Bindable.of(DartProperties::class.java), NoUnboundElementsBindHandler(BindHandler.DEFAULT))
        }.doesNotThrowAnyException()
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication
}
