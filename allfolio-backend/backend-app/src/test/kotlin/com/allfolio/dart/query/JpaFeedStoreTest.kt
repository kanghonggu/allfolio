package com.allfolio.dart.query

import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetLiquidityType
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ConfidenceLevel
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.allfolio.unifiedasset.infrastructure.entity.AssetEntity
import com.allfolio.unifiedasset.infrastructure.entity.DartDisclosureEntity
import com.allfolio.unifiedasset.infrastructure.entity.DartInsiderTradeEntity
import com.allfolio.unifiedasset.infrastructure.jpa.DartDisclosureJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.DartInsiderTradeJpaRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * [JpaFeedStore.findHeldStockCodes]가 실제 `ua_assets` 위에서 `type='STOCK'` 필터를 태우는지 본다.
 *
 * `DisclosureFeedServiceTest`는 `Store` 인터페이스를 인메모리로 가짜 세우므로 이 네이티브 쿼리
 * (`EntityManager.createNativeQuery`)는 거기서 전혀 실행되지 않는다 — `type = 'STOCK'` 조건을
 * 통째로 지워도 그 테스트는 계속 초록이다. 여기서 실제 H2 위에 `ua_assets` 행을 깔아
 * `type='STOCK'` 필터가 로컬 DB 실측 형태(REAL_ESTATE, symbol 29자리)를 걸러내는지 직접 본다.
 */
// backend-app의 application.yml이 spring.jpa.hibernate.ddl-auto=none을 못박고 있고, 그 파일이
// 같은 모듈 테스트 클래스패스에도 그대로 올라온다(unified-asset과 달리 이 모듈엔 그 설정이 있다).
// 오버라이드하지 않으면 H2에 ua_assets 테이블이 안 생겨 "Table not found"로 죽는다.
@DataJpaTest
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=create-drop"])
@ContextConfiguration(classes = [JpaFeedStoreTest.TestConfig::class])
class JpaFeedStoreTest {

    @Autowired private lateinit var em: EntityManager
    @Autowired private lateinit var disclosures: DartDisclosureJpaRepository
    @Autowired private lateinit var insiders: DartInsiderTradeJpaRepository

    private lateinit var store: JpaFeedStore
    private val userId = UUID.randomUUID()
    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    @BeforeEach
    fun setUp() {
        store = JpaFeedStore(em, disclosures, insiders)
    }

    private fun asset(
        type: AssetType,
        symbol: String?,
        quantity: String = "10",
        owner: UUID = userId,
    ) = AssetEntity(
        id = UUID.randomUUID(), userId = owner, accountId = UUID.randomUUID(),
        category = if (type == AssetType.STOCK) AssetCategory.FINANCIAL else AssetCategory.MANUAL,
        type = type, sourceType = AssetSourceType.MANUAL, name = "자산", symbol = symbol,
        quantity = BigDecimal(quantity), purchasePrice = BigDecimal.ZERO, currentValue = BigDecimal.ZERO,
        currency = "KRW", valuationMethod = ValuationMethod.USER_INPUT, confidenceLevel = ConfidenceLevel.LOW,
        lastUpdatedAt = now, createdAt = now, memo = null, subType = null, loanAmount = null,
        maturityDate = null,
        liquidityType = if (type == AssetType.STOCK) AssetLiquidityType.LIQUID else AssetLiquidityType.ILLIQUID,
    )

    private fun save(entity: AssetEntity) {
        em.persist(entity)
        em.flush()
    }

    @Test
    fun `STOCK만 나오고 29자리 symbol을 넣은 REAL_ESTATE는 섞이지 않는다`() {
        // 실측(2026-08-18): STOCK 6건은 symbol이 전부 6자리, REAL_ESTATE 1건은 symbol이 29자리다.
        save(asset(AssetType.STOCK, "005930"))
        save(asset(AssetType.REAL_ESTATE, "1111011100108870000000000000")) // 29자리 실측 형태

        val held = store.findHeldStockCodes(userId)

        assertThat(held).containsExactly("005930")
    }

    @Test
    fun `symbol이 빈 문자열이거나 null이면 빠진다`() {
        save(asset(AssetType.STOCK, ""))
        save(asset(AssetType.STOCK, null))
        save(asset(AssetType.STOCK, "005930"))

        assertThat(store.findHeldStockCodes(userId)).containsExactly("005930")
    }

    @Test
    fun `수량이 0이면 빠진다`() {
        save(asset(AssetType.STOCK, "005930", quantity = "0"))

        assertThat(store.findHeldStockCodes(userId)).isEmpty()
    }

    @Test
    fun `다른 사용자의 보유종목은 섞이지 않는다`() {
        save(asset(AssetType.STOCK, "005930", owner = UUID.randomUUID()))

        assertThat(store.findHeldStockCodes(userId)).isEmpty()
    }

    @Test
    fun `보유종목이 없으면 빈 목록이다`() {
        assertThat(store.findHeldStockCodes(userId)).isEmpty()
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(
        basePackageClasses = [
            AssetEntity::class, DartDisclosureEntity::class, DartInsiderTradeEntity::class,
        ],
    )
    @EnableJpaRepositories(
        basePackageClasses = [DartDisclosureJpaRepository::class, DartInsiderTradeJpaRepository::class],
    )
    class TestConfig
}
