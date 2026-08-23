package com.allfolio.realasset

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.infrastructure.jpa.AssetJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.MarketCommodityQuoteJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.RtmsDealCacheJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan

/**
 * 평가 스냅샷 배치(G5)는 `List<ValuationSource>`를 주입받는다. 그 구조에는 조용한 실패가 둘 있다:
 * 빈이 하나도 없으면 **단위 테스트는 전부 초록인데 서버가 안 뜨고**(스프링은 필수 컬렉션 주입에
 * 후보가 없으면 기동을 실패시킨다), `@Component`가 빠지면 컴파일도 단위 테스트도 멀쩡한 채
 * 평가만 "대상 0건"으로 끝난다. 둘 다 실제 컨텍스트로만 보인다.
 * (`CommodityCollectSourceWiringTest`가 같은 이유로 존재한다.)
 *
 * **`classes`에 어댑터를 나열하지 않고 `@ComponentScan`을 쓰는 것이 요점이다.** 나열하면
 * 애너테이션이 없어도 빈으로 등록돼 이 테스트가 검사하려는 바로 그것을 건너뛴다.
 *
 * **어댑터가 늘면 여기 한 줄 더 적을 것** — 시계(W5)·부동산(R3)이 그렇게 붙는다.
 */
@SpringBootTest(
    classes = [ValuationSourceWiringTest.TestApplication::class],
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
class ValuationSourceWiringTest {

    // 레포는 이 테스트의 관심사가 아니다 — 배선만 본다. 진짜 쿼리는 리포지터리 테스트가 문다.
    // **패키지 전체를 스캔하므로 이 패키지의 빈이 늘면 그 의존도 여기 세워 줘야 한다** —
    // 안 그러면 배선 테스트가 "어댑터가 빠졌다"가 아니라 "레포 빈이 없다"로 빨개져 신호가 흐려진다.
    @MockBean private lateinit var quoteRepository: MarketCommodityQuoteJpaRepository

    // 부동산(R3)이 붙으면서 이 패키지에 RtmsSource가 늘었다 — 그 의존도 세워 준다.
    // 위 주석이 예고한 그대로다.
    @MockBean private lateinit var rtmsDeals: RtmsDealCacheJpaRepository

    @MockBean private lateinit var assetJpa: AssetJpaRepository

    @MockBean private lateinit var assetRepository: AssetRepository

    @Autowired private lateinit var sources: List<ValuationSource>

    @Test
    fun `금 평가 어댑터가 빈으로 등록된다`() {
        assertThat(sources).hasAtLeastOneElementOfType(KrxGoldSource::class.java)
    }

    @Test
    fun `부동산 평가 어댑터가 빈으로 등록된다`() {
        assertThat(sources).hasAtLeastOneElementOfType(RtmsSource::class.java)
    }

    /**
     * **어댑터만 추가하고 조회 목록을 빠뜨리면 그 자산은 조용히 평가되지 않는다.**
     * `JpaValuableAssetStore.VALUABLE_TYPES`가 그 목록이고, 이 테스트가 둘의 어긋남을 문다.
     */
    @Test
    fun `담당 어댑터가 있는 유형은 조회 목록에도 있다`() {
        val supported = AssetType.entries.filter { t -> sources.any { it.supports(t) } }

        assertThat(JpaValuableAssetStore.VALUABLE_TYPES)
            .describedAs("어댑터가 맡는 유형은 배치가 읽어 와야 한다")
            .containsAll(supported)
    }

    /** 어댑터가 늘어도 금은 계속 금만 맡아야 한다 — 유형이 겹치면 배치가 어느 쪽을 쓸지 모른다 */
    @Test
    fun `자산 유형마다 담당 어댑터가 하나씩이다`() {
        AssetType.entries.forEach { type ->
            assertThat(sources.filter { it.supports(type) })
                .describedAs("%s를 맡는 어댑터", type)
                .hasSizeLessThanOrEqualTo(1)
        }
    }

    @SpringBootConfiguration
    @ComponentScan(basePackageClasses = [KrxGoldSource::class])
    class TestApplication
}
