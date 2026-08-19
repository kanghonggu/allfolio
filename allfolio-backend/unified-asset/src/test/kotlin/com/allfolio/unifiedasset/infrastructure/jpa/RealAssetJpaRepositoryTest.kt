package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.RealAssetEntity
import com.allfolio.unifiedasset.infrastructure.entity.RealAssetValuationEntity
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 실물자산 두 표(A1). **평가 배치의 대상 선정과 멱등성이 여기 걸려 있다.**
 *
 * 서비스 단위 테스트는 `Store` 포트를 인메모리로 구현하므로 쿼리의 **의미를 손으로 다시 적는다** —
 * `findByIsActiveTrue`가 사실 `findAll`이어도 그쪽은 전부 초록이다.
 * (`MarketCommodityQuoteJpaRepositoryTest`가 같은 이유로 존재한다.)
 */
@DataJpaTest
@ContextConfiguration(classes = [RealAssetJpaRepositoryTest.TestConfig::class])
class RealAssetJpaRepositoryTest {

    @Autowired private lateinit var assets: RealAssetJpaRepository

    @Autowired private lateinit var valuations: RealAssetValuationJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    private val user = UUID.randomUUID()

    /**
     * 비활성 자산이 딸려 오면 사용자가 **판 금을 계속 평가받는다.** 오류는 안 나고 행만 늘어
     * 순자산이 안 줄어드는 증상으로 나온다 — 숫자만 봐서는 못 잡는다.
     */
    @Test
    fun `배치 대상은 활성 자산만이다`() {
        val active = saveAsset(name = "금 10g")
        saveAsset(name = "판 금", isActive = false)

        assertThat(assets.findByIsActiveTrue().map { it.id }).containsExactly(active.id)
    }

    /** 조회 API가 남의 자산을 주면 안 된다. user_id 조건이 빠지면 여기서 잡힌다 */
    @Test
    fun `사용자 조회는 자기 자산만 준다`() {
        val mine = saveAsset(name = "내 금")
        saveAsset(name = "남의 금", userId = UUID.randomUUID())

        assertThat(assets.findByUserIdAndIsActiveTrue(user).map { it.id }).containsExactly(mine.id)
    }

    /**
     * **UNIQUE 제약이 엔티티에 선언돼 있지 않으면 H2에 제약이 아예 안 생겨 중복 삽입이
     * 조용히 커밋된다** — AF-100에서 실제로 물린 함정이다. 배치 재시도의 멱등성이 이 제약에 걸려 있다.
     */
    @Test
    fun `같은 자산 같은 평가일은 두 번 못 들어간다`() {
        val asset = saveAsset(name = "금 10g")
        saveValuation(asset.id, LocalDate.of(2026, 8, 18))

        assertThatThrownBy { saveValuation(asset.id, LocalDate.of(2026, 8, 18)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    /** 날짜 필터가 빠지면 배치가 남의 날짜 스냅샷을 "오늘 것"으로 알고 덮는다 */
    @Test
    fun `평가일 조회는 그날 것만 준다`() {
        val asset = saveAsset(name = "금 10g")
        saveValuation(asset.id, LocalDate.of(2026, 8, 17))
        saveValuation(asset.id, LocalDate.of(2026, 8, 18))

        val found = valuations.findByValuedOn(LocalDate.of(2026, 8, 18))

        assertThat(found).hasSize(1)
        assertThat(found.single().valuedOn).isEqualTo(LocalDate.of(2026, 8, 18))
    }

    /**
     * 조회 API(G7)가 보는 것이 이 쿼리다. `NOT EXISTS`를 지워 전체 행을 돌려주게 만들어도
     * 호출부가 `associateBy`로 접으면 오류 없이 **묵은 스냅샷이 최신인 척** 화면에 뜬다.
     */
    @Test
    fun `최신 조회는 자산마다 가장 최근 한 건씩만 준다`() {
        val gold = saveAsset(name = "금 10g")
        val bar = saveAsset(name = "골드바")
        saveValuation(gold.id, LocalDate.of(2026, 8, 14), krw = 1_000_000L)
        saveValuation(gold.id, LocalDate.of(2026, 8, 18), krw = 1_983_500L) // 이게 나와야 한다
        saveValuation(gold.id, LocalDate.of(2026, 8, 17), krw = 1_500_000L)
        saveValuation(bar.id, LocalDate.of(2026, 8, 18), krw = 500_000L)
        // 요청 밖 자산 — id 필터가 빠지면 이게 딸려 온다
        val other = saveAsset(name = "남의 금", userId = UUID.randomUUID())
        saveValuation(other.id, LocalDate.of(2026, 8, 18), krw = 9L)

        val found = valuations.findLatestByAssetIds(listOf(gold.id, bar.id))

        assertThat(found.map { it.realAssetId to it.valuedOn }).containsExactlyInAnyOrder(
            gold.id to LocalDate.of(2026, 8, 18),
            bar.id to LocalDate.of(2026, 8, 18),
        )
        assertThat(found.single { it.realAssetId == gold.id }.valuationKrw).isEqualTo(1_983_500L)
        assertThat(found.map { it.realAssetId }).doesNotContain(other.id)
    }

    /**
     * **1돈 = 3.75g.** 스케일이 좁으면 여기서 잘려 1돈이 3g 또는 4g이 된다 —
     * 평가액이 조용히 7% 틀리고, 숫자만 봐서는 못 잡는다.
     */
    @Test
    fun `수량 소수 네 자리가 보존된다`() {
        val asset = saveAsset(name = "금 1돈", quantity = "3.7500")

        entityManager.clear()

        assertThat(assets.findById(asset.id).get().quantity).isEqualByComparingTo("3.7500")
    }

    /** `confidence`만 nullable이다 — 나머지는 평가가 성립한 이상 전부 채워진다 */
    @Test
    fun `신뢰도는 비운 채 저장할 수 있다`() {
        val asset = saveAsset(name = "금 10g")
        saveValuation(asset.id, LocalDate.of(2026, 8, 18), confidence = null)

        assertThat(valuations.findAll().single().confidence).isNull()
    }

    private fun saveAsset(
        name: String,
        userId: UUID = user,
        quantity: String = "10.0000",
        isActive: Boolean = true,
    ): RealAssetEntity {
        val entity = RealAssetEntity(
            id = UUID.randomUUID(),
            userId = userId,
            assetType = "GOLD",
            subType = "KRX_ACCOUNT",
            name = name,
            sourceRef = "GOLD_KRX",
            quantity = BigDecimal(quantity),
            purity = BigDecimal("1.0000"),
            acquiredAt = LocalDate.of(2026, 8, 1),
            acquiredCostKrw = 2_000_000L,
            includeInTwr = true,
            isActive = isActive,
            createdAt = NOW,
            updatedAt = NOW,
        )
        assets.saveAndFlush(entity)
        entityManager.clear()
        return entity
    }

    private fun saveValuation(
        assetId: UUID,
        valuedOn: LocalDate,
        krw: Long = 1_983_500L,
        confidence: String? = "HIGH",
    ) {
        valuations.saveAndFlush(
            RealAssetValuationEntity(
                id = UUID.randomUUID(),
                realAssetId = assetId,
                valuedOn = valuedOn,
                unitPrice = BigDecimal("198350.0000"),
                priceUnit = "KRW/g",
                valuationKrw = krw,
                priceAsOf = LocalDate.of(2026, 8, 14),
                stalenessDays = 4,
                priceBasis = "TRADE",
                confidence = confidence,
                createdAt = NOW,
            ),
        )
        entityManager.clear()
    }

    private companion object {
        private val NOW: Instant = Instant.parse("2026-08-18T10:30:00Z")
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = [RealAssetEntity::class])
    @EnableJpaRepositories(basePackageClasses = [RealAssetJpaRepository::class])
    class TestConfig
}
