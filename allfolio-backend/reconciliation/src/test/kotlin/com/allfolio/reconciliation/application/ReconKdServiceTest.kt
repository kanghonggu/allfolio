package com.allfolio.reconciliation.application

import com.allfolio.reconciliation.domain.KdValueType
import com.allfolio.reconciliation.infrastructure.entity.ReconKdEntity
import com.allfolio.reconciliation.infrastructure.jpa.ReconKdJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

class ReconKdServiceTest {

    private val userId = UUID.randomUUID()

    private class InMemoryKdRepo : ReconKdJpaRepository by mock(ReconKdJpaRepository::class.java) {
        val rows = mutableListOf<ReconKdEntity>()
        override fun <S : ReconKdEntity> save(entity: S): S {
            rows.removeAll { it.id == entity.id }; rows += entity; return entity
        }
        override fun findByUserIdAndUseYnTrue(userId: UUID): List<ReconKdEntity> =
            rows.filter { it.userId == userId && it.useYn }
        override fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<ReconKdEntity> =
            rows.filter { it.userId == userId }
        override fun findById(id: UUID): Optional<ReconKdEntity> =
            Optional.ofNullable(rows.find { it.id == id })
    }

    private fun cmd(start: LocalDate, allow: String = "2") = RegisterKdCommand(
        kdCode = "KD-QTY", targetSymbol = "005930", targetField = "quantity",
        valueType = KdValueType.ABS, allowValue = BigDecimal(allow), reason = "단수차", apldStrtDt = start,
    )

    @Test
    fun `같은 kdCode 재등록 시 기존 열린 행을 마감하고 신규 행을 연다`() {
        val repo = InMemoryKdRepo()
        val svc = ReconKdService(repo)
        svc.register(userId, cmd(LocalDate.of(2026, 1, 1), allow = "2"))
        svc.register(userId, cmd(LocalDate.of(2026, 7, 1), allow = "5"))

        assertEquals(2, repo.rows.size)
        val closed = repo.rows.first { it.apldEndDt != ReconKdService.OPEN_END }
        assertEquals(LocalDate.of(2026, 6, 30), closed.apldEndDt)
        val open = repo.rows.first { it.apldEndDt == ReconKdService.OPEN_END }
        assertEquals(0, BigDecimal("5").compareTo(open.allowValue))
    }

    @Test
    fun `타인 KD 비활성화는 404로 거부한다`() {
        val repo = InMemoryKdRepo()
        val svc = ReconKdService(repo)
        val mine = svc.register(userId, cmd(LocalDate.of(2026, 1, 1)))

        assertThrows(NoSuchElementException::class.java) { svc.deactivate(UUID.randomUUID(), mine.id) }
        svc.deactivate(userId, mine.id)
        assertFalse(repo.rows.single().useYn)
    }

    @Test
    fun `허용값 0 이하는 거부한다`() {
        val svc = ReconKdService(InMemoryKdRepo())
        assertThrows(IllegalArgumentException::class.java) {
            svc.register(userId, cmd(LocalDate.of(2026, 1, 1), allow = "0"))
        }
    }
}
