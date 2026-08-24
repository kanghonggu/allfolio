package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 스냅샷 서비스는 목이 아니라 fake로 받는다 — 인자 셋을 한꺼번에 보려면 Mockito 매처를
 * 섞어야 하고, Kotlin의 non-null 파라미터에 `capture()`를 물리면 그 자체가 NPE다.
 * 이 모듈엔 mockito-kotlin이 없다.
 */
private class RecordingSnapshotService : PerformanceSnapshotService(
    CapturingJdbcTemplate(), StubFxConverter(), RecordingNavCurrencyStore(),
) {
    val calls = mutableListOf<Triple<UUID, Map<String, BigDecimal>, LocalDate>>()

    override fun record(userId: UUID, navByCurrency: Map<String, BigDecimal>, date: LocalDate) {
        calls += Triple(userId, navByCurrency, date)
    }
}

class DeleteAssetUseCaseTest {

    private val assetRepository = mock(AssetRepository::class.java)
    private val snapshotService = RecordingSnapshotService()

    private val useCase = DeleteAssetUseCase(assetRepository, snapshotService)

    private val userId = UUID.randomUUID()
    private val accountId = UUID.randomUUID()

    private fun asset(
        userId: UUID = this.userId,
        accountId: UUID = this.accountId,
        sourceType: AssetSourceType = AssetSourceType.MANUAL,
        currentValue: BigDecimal = BigDecimal("1000"),
    ) = Asset.create(
        userId          = userId,
        accountId       = accountId,
        category        = AssetCategory.MANUAL,
        type            = AssetType.REAL_ESTATE,
        sourceType      = sourceType,
        name            = "역삼동 아파트",
        symbol          = null,
        quantity        = BigDecimal.ONE,
        purchasePrice   = BigDecimal("900"),
        currentValue    = currentValue,
        currency        = "KRW",
        valuationMethod = ValuationMethod.USER_INPUT,
    )

    @Test
    fun `수동으로 등록한 자산은 그 자산만 지운다`() {
        val target = asset()
        `when`(assetRepository.findById(target.id)).thenReturn(target)
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())

        useCase.execute(userId, accountId, target.id)

        verify(assetRepository).delete(target.id)
    }

    @Test
    fun `남의 자산은 id를 알아도 못 지운다`() {
        val someoneElse = asset(userId = UUID.randomUUID())
        `when`(assetRepository.findById(someoneElse.id)).thenReturn(someoneElse)

        assertThrows<NoSuchElementException> {
            useCase.execute(userId, accountId, someoneElse.id)
        }
        verify(assetRepository, never()).delete(someoneElse.id)
    }

    @Test
    fun `다른 계좌에 달린 자산은 이 계좌 경로로 못 지운다`() {
        val otherAccountAsset = asset(accountId = UUID.randomUUID())
        `when`(assetRepository.findById(otherAccountAsset.id)).thenReturn(otherAccountAsset)

        assertThrows<NoSuchElementException> {
            useCase.execute(userId, accountId, otherAccountAsset.id)
        }
        verify(assetRepository, never()).delete(otherAccountAsset.id)
    }

    @Test
    fun `없는 자산을 지우면 찾을 수 없다고 한다`() {
        val missing = UUID.randomUUID()
        `when`(assetRepository.findById(missing)).thenReturn(null)

        assertThrows<NoSuchElementException> { useCase.execute(userId, accountId, missing) }
    }

    /**
     * 동기화 계좌는 `deleteByAccountId` 후 `saveAll`로 통째로 교체된다(SyncAccountUseCase).
     * 지워 봐야 다음 동기화가 되살리므로, 지워지는 척하는 대신 거절한다.
     */
    @Test
    fun `동기화가 되살릴 자산은 지울 수 없다`() {
        val synced = asset(sourceType = AssetSourceType.EXCHANGE_API)
        `when`(assetRepository.findById(synced.id)).thenReturn(synced)

        assertThrows<IllegalArgumentException> {
            useCase.execute(userId, accountId, synced.id)
        }
        verify(assetRepository, never()).delete(synced.id)
    }

    @Test
    fun `CSV로 올린 자산은 지울 수 있다`() {
        val imported = asset(sourceType = AssetSourceType.CSV)
        `when`(assetRepository.findById(imported.id)).thenReturn(imported)
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())

        useCase.execute(userId, accountId, imported.id)

        verify(assetRepository).delete(imported.id)
    }

    @Test
    fun `삭제한 뒤의 잔여 자산으로 NAV 스냅샷을 다시 기록한다`() {
        val target = asset()
        val survivor = asset(currentValue = BigDecimal("700"))
        `when`(assetRepository.findById(target.id)).thenReturn(target)
        // 삭제 후 조회 — 지운 자산은 빠져 있다
        `when`(assetRepository.findByUserId(userId)).thenReturn(listOf(survivor))

        useCase.execute(userId, accountId, target.id)

        val (recordedUser, nav, _) = snapshotService.calls.single()
        assertEquals(userId, recordedUser)
        assertEquals(mapOf("KRW" to BigDecimal("700")), nav)
    }

    @Test
    fun `거절된 삭제는 스냅샷을 건드리지 않는다`() {
        val synced = asset(sourceType = AssetSourceType.EXCHANGE_API)
        `when`(assetRepository.findById(synced.id)).thenReturn(synced)

        assertThrows<IllegalArgumentException> { useCase.execute(userId, accountId, synced.id) }

        assertTrue(snapshotService.calls.isEmpty())
    }

    /**
     * 스냅샷 날짜는 **KST 오늘**이다. `LocalDate.now()`로 단언하면 UTC로 구현해도 통과하므로
     * 두 존이 갈리는 시각(UTC 22:00 = KST 익일 07:00)을 고정해 넣는다.
     */
    @Test
    fun `스냅샷 날짜는 UTC가 아니라 KST 오늘이다`() {
        val target = asset()
        `when`(assetRepository.findById(target.id)).thenReturn(target)
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())
        useCase.clock = Clock.fixed(Instant.parse("2026-08-24T22:00:00Z"), ZoneId.of("UTC"))

        useCase.execute(userId, accountId, target.id)

        assertEquals(LocalDate.of(2026, 8, 25), snapshotService.calls.single().third)
    }

    @Test
    fun `execute 는 단일 트랜잭션으로 실행된다`() {
        val method = DeleteAssetUseCase::class.java.getDeclaredMethod(
            "execute", UUID::class.java, UUID::class.java, UUID::class.java,
        )

        assertNotNull(method.getAnnotation(Transactional::class.java))
    }
}
