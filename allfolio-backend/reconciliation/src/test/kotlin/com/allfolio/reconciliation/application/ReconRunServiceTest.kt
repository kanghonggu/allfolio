package com.allfolio.reconciliation.application

import com.allfolio.reconciliation.domain.DiffType
import com.allfolio.reconciliation.domain.ReconTrigger
import com.allfolio.reconciliation.domain.RunStatus
import com.allfolio.reconciliation.domain.RunType
import com.allfolio.reconciliation.domain.SummaryStatus
import com.allfolio.reconciliation.domain.KdValueType
import com.allfolio.reconciliation.infrastructure.entity.ReconKdEntity
import com.allfolio.reconciliation.infrastructure.entity.ReconResultDetailEntity
import com.allfolio.reconciliation.infrastructure.entity.ReconResultSummaryEntity
import com.allfolio.reconciliation.infrastructure.entity.ReconRunEntity
import com.allfolio.reconciliation.infrastructure.jpa.ReconKdJpaRepository
import com.allfolio.reconciliation.infrastructure.jpa.ReconResultDetailJpaRepository
import com.allfolio.reconciliation.infrastructure.jpa.ReconResultSummaryJpaRepository
import com.allfolio.reconciliation.infrastructure.jpa.ReconRunJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class ReconRunServiceTest {

    private val userId = UUID.randomUUID()
    private val runDate = LocalDate.of(2026, 7, 31)

    private class RecordingRunRepo : ReconRunJpaRepository by mock(ReconRunJpaRepository::class.java) {
        val saved = mutableListOf<ReconRunEntity>()
        override fun <S : ReconRunEntity> save(entity: S): S { saved += entity; return entity }
    }

    private class RecordingSummaryRepo : ReconResultSummaryJpaRepository by mock(ReconResultSummaryJpaRepository::class.java) {
        val saved = mutableListOf<ReconResultSummaryEntity>()
        override fun <S : ReconResultSummaryEntity> save(entity: S): S { saved += entity; return entity }
    }

    private class RecordingDetailRepo : ReconResultDetailJpaRepository by mock(ReconResultDetailJpaRepository::class.java) {
        val saved = mutableListOf<ReconResultDetailEntity>()
        override fun <S : ReconResultDetailEntity> saveAll(entities: MutableIterable<S>): MutableList<S> {
            saved += entities; return entities.toMutableList()
        }
    }

    private fun rule(
        code: String,
        kind: RuleKind = RuleKind.VALIDATION,
        result: () -> RuleResult,
    ) = object : ReconRule {
        override val code = code
        override val kind = kind
        override fun execute(ctx: ReconContext): RuleResult = result()
    }

    private class FakeKdRepo(private val kds: List<ReconKdEntity> = emptyList()) :
        ReconKdJpaRepository by mock(ReconKdJpaRepository::class.java) {
        override fun findByUserIdAndUseYnTrue(userId: UUID): List<ReconKdEntity> = kds
    }

    private class FakeLock(private val acquirable: Boolean = true) : ReconLockPort {
        var released = false
        override fun tryAcquire(userId: UUID): String? = if (acquirable) "token" else null
        override fun release(userId: UUID, token: String) { released = true }
    }

    private fun service(
        rules: List<ReconRule>,
        runRepo: RecordingRunRepo = RecordingRunRepo(),
        summaryRepo: RecordingSummaryRepo = RecordingSummaryRepo(),
        detailRepo: RecordingDetailRepo = RecordingDetailRepo(),
        kds: List<ReconKdEntity> = emptyList(),
        lock: FakeLock = FakeLock(),
    ): Triple<ReconRunService, RecordingSummaryRepo, RecordingDetailRepo> {
        // 미스텁 mock: external_as_of 조회는 서비스가 runCatching으로 감싸 null 허용
        val jdbc = mock(JdbcTemplate::class.java)
        return Triple(ReconRunService(rules, runRepo, summaryRepo, detailRepo, FakeKdRepo(kds), lock, jdbc), summaryRepo, detailRepo)
    }

    @Test
    fun `룰별 summary가 기록되고 diff는 detail로 적재된다`() {
        val diff = RuleDiff(symbol = "AAPL", fieldName = "quantity", diffType = DiffType.VALUE_MISMATCH,
            internalValue = BigDecimal.ONE, externalValue = BigDecimal.TEN, diffValue = BigDecimal(-9))
        val (svc, summaries, details) = service(listOf(
            rule("PASS_RULE") { RuleResult(checkedCnt = 5, diffs = emptyList()) },
            rule("DIFF_RULE") { RuleResult(checkedCnt = 3, diffs = listOf(diff)) },
        ))

        val run = svc.execute(userId, runDate, RunType.ALL, ReconTrigger.MANUAL)

        assertEquals(RunStatus.COMPLETED, run.status)
        assertNotNull(run.finishedAt)
        assertEquals(2, summaries.saved.size)
        val pass = summaries.saved.first { it.ruleCode == "PASS_RULE" }
        assertEquals(SummaryStatus.PASSED, pass.status)
        assertEquals(5, pass.checkedCnt)
        val diffSummary = summaries.saved.first { it.ruleCode == "DIFF_RULE" }
        assertEquals(SummaryStatus.DIFF_FOUND, diffSummary.status)
        assertEquals(1, diffSummary.diffCnt)
        assertEquals(1, details.saved.size)
        assertEquals("AAPL", details.saved[0].symbol)
    }

    @Test
    fun `한 룰의 예외는 해당 summary만 FAILED로 격리한다`() {
        val (svc, summaries, _) = service(listOf(
            rule("BOOM") { throw IllegalStateException("query failed") },
            rule("OK") { RuleResult(1, emptyList()) },
        ))

        val run = svc.execute(userId, runDate, RunType.ALL, ReconTrigger.MANUAL)

        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals(SummaryStatus.FAILED, summaries.saved.first { it.ruleCode == "BOOM" }.status)
        assertTrue(summaries.saved.first { it.ruleCode == "BOOM" }.errorMsg!!.contains("query failed"))
        assertEquals(SummaryStatus.PASSED, summaries.saved.first { it.ruleCode == "OK" }.status)
    }

    @Test
    fun `전 룰 실패면 run이 FAILED가 된다`() {
        val (svc, _, _) = service(listOf(rule("B1") { throw RuntimeException("x") }))
        val run = svc.execute(userId, runDate, RunType.ALL, ReconTrigger.MANUAL)
        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun `runType이 VALIDATION이면 RECONCILIATION 룰은 실행하지 않는다`() {
        var reconRan = false
        val (svc, summaries, _) = service(listOf(
            rule("V", RuleKind.VALIDATION) { RuleResult(1, emptyList()) },
            rule("R", RuleKind.RECONCILIATION) { reconRan = true; RuleResult(1, emptyList()) },
        ))

        svc.execute(userId, runDate, RunType.VALIDATION, ReconTrigger.MANUAL)

        assertEquals(listOf("V"), summaries.saved.map { it.ruleCode })
        assertTrue(!reconRan)
    }

    @Test
    fun `KD 허용치 이내 diff는 kdId가 기록되고 흡수 건수가 분리 집계된다`() {
        val kd = ReconKdEntity(
            userId = userId, kdCode = "KD-QTY", targetSymbol = "AAPL", targetField = "quantity",
            valueType = KdValueType.ABS, allowValue = BigDecimal("5"), reason = "수수료 단수차",
            apldStrtDt = runDate.minusDays(1),
        )
        val small = RuleDiff(symbol = "AAPL", fieldName = "quantity", diffType = DiffType.VALUE_MISMATCH,
            internalValue = BigDecimal("8"), externalValue = BigDecimal("10"), diffValue = BigDecimal("2"))
        val big = RuleDiff(symbol = "AAPL", fieldName = "quantity", diffType = DiffType.VALUE_MISMATCH,
            internalValue = BigDecimal("1"), externalValue = BigDecimal("10"), diffValue = BigDecimal("9"))
        val (svc, summaries, details) = service(
            rules = listOf(rule("R") { RuleResult(2, listOf(small, big)) }),
            kds = listOf(kd),
        )

        svc.execute(userId, runDate, RunType.ALL, ReconTrigger.MANUAL)

        val summary = summaries.saved.single()
        assertEquals(SummaryStatus.DIFF_FOUND, summary.status)
        assertEquals(2, summary.diffCnt)
        assertEquals(1, summary.kdAbsorbedCnt)
        assertEquals(2, details.saved.size)
        assertEquals(kd.id, details.saved.first { it.diffValue == BigDecimal("2") }.kdId)
        assertEquals(null, details.saved.first { it.diffValue == BigDecimal("9") }.kdId)
    }

    @Test
    fun `락 획득 실패 시 SyncInProgressException — run을 만들지 않는다`() {
        val runRepo = RecordingRunRepo()
        val lock = FakeLock(acquirable = false)
        val svc = service(listOf(rule("R") { RuleResult(1, emptyList()) }), runRepo = runRepo, lock = lock).first

        org.junit.jupiter.api.Assertions.assertThrows(SyncInProgressException::class.java) {
            svc.execute(userId, runDate, RunType.ALL, ReconTrigger.MANUAL)
        }
        assertTrue(runRepo.saved.isEmpty())
    }

    @Test
    fun `룰이 예외를 던져도 락은 해제된다`() {
        val lock = FakeLock()
        val svc = service(listOf(rule("B") { throw RuntimeException("x") }), lock = lock).first
        svc.execute(userId, runDate, RunType.ALL, ReconTrigger.MANUAL)
        assertTrue(lock.released)
    }

    @Test
    fun `detail은 룰당 100건으로 절단하되 diff_cnt는 전체를 집계한다`() {
        val manyDiffs = (1..150).map {
            RuleDiff(symbol = "S$it", diffType = DiffType.RULE_VIOLATION)
        }
        val (svc, summaries, details) = service(listOf(rule("MANY") { RuleResult(150, manyDiffs) }))

        svc.execute(userId, runDate, RunType.ALL, ReconTrigger.MANUAL)

        assertEquals(150, summaries.saved.single().diffCnt)
        assertEquals(100, details.saved.size)
    }
}
