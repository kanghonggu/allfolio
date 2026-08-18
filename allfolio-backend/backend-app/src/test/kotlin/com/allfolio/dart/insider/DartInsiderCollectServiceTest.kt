package com.allfolio.dart.insider

import com.allfolio.dart.DartApiException
import com.allfolio.unifiedasset.infrastructure.entity.DartDisclosureEntity
import com.allfolio.unifiedasset.infrastructure.entity.DartInsiderTradeEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class DartInsiderCollectServiceTest {

    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    private fun disclosure(rceptNo: String, corpCode: String, tier: Short?, stockCode: String? = "005930") =
        DartDisclosureEntity(
            rceptNo = rceptNo, corpCode = corpCode, corpName = "회사", stockCode = stockCode,
            corpCls = "Y", reportNm = "임원ㆍ주요주주특정증권등소유상황보고서",
            reportNmNorm = "임원·주요주주특정증권등소유상황보고서",
            rceptDt = LocalDate.of(2026, 8, 18), flrNm = "홍길동", rm = null,
            isMaterial = tier != null, materialTier = tier, isCorrection = false, collectedAt = now,
        )

    private fun elestock(rceptNo: String, corpCode: String = "C1", repror: String = "홍길동") = ElestockRow(
        rceptNo = rceptNo, corpCode = corpCode, repror = repror,
        officerPosition = "상무", isRegistered = false, majorHolderType = null,
        reportDate = LocalDate.of(2026, 8, 18), ownedQty = 1000L, changeQty = 10L,
        ownedRate = BigDecimal("0.01"), changeRate = BigDecimal("0.00"),
    )

    private class FakeClient(private val byCorp: Map<String, List<ElestockRow>>) : ElestockPort {
        val called = mutableListOf<String>()
        override fun fetch(corpCode: String): List<ElestockRow> {
            called += corpCode
            return byCorp[corpCode] ?: emptyList()
        }
    }

    private class FakeStore(val disclosures: List<DartDisclosureEntity>) : DartInsiderCollectService.Store {
        val saved = mutableListOf<DartInsiderTradeEntity>()
        var existingKeys = mutableSetOf<Pair<String, String>>()
        override fun findDisclosures(rceptNos: Collection<String>) =
            disclosures.filter { it.rceptNo in rceptNos }
        override fun findExistingKeys(rceptNos: Collection<String>) =
            existingKeys.filter { it.first in rceptNos }.toSet()
        override fun saveAll(rows: List<DartInsiderTradeEntity>) { saved += rows }
    }

    @Test
    fun `Tier 4 공시의 회사만 호출한다`() {
        val store = FakeStore(listOf(
            disclosure("R1", "C1", tier = 4),
            disclosure("R2", "C2", tier = 1),   // 유상증자 — 대상 아님
        ))
        val client = FakeClient(mapOf("C1" to listOf(elestock("R1"))))

        DartInsiderCollectService(client, store).collect(listOf("R1", "R2"), now)

        assertThat(client.called).containsExactly("C1")
    }

    @Test
    fun `회사 전체 이력 중 델타에 있는 건만 저장한다`() {
        // elestock은 기간 파라미터가 없어 약 2년치가 통째로 온다. 실측 최대 3,395행.
        val store = FakeStore(listOf(disclosure("R_NEW", "C1", tier = 4)))
        val client = FakeClient(mapOf("C1" to listOf(
            elestock("R_OLD_2024"), elestock("R_NEW"), elestock("R_OLD_2025"),
        )))

        val summary = DartInsiderCollectService(client, store).collect(listOf("R_NEW"), now)

        assertThat(store.saved.map { it.rceptNo }).containsExactly("R_NEW")
        assertThat(summary.inserted).isEqualTo(1)
    }

    @Test
    fun `같은 회사를 한 번만 호출한다`() {
        val store = FakeStore(listOf(
            disclosure("R1", "C1", tier = 4),
            disclosure("R2", "C1", tier = 4),
        ))
        val client = FakeClient(mapOf("C1" to listOf(elestock("R1"), elestock("R2", repror = "김철수"))))

        DartInsiderCollectService(client, store).collect(listOf("R1", "R2"), now)

        assertThat(client.called).containsExactly("C1")
        assertThat(store.saved).hasSize(2)
    }

    @Test
    fun `이미 저장된 조합은 다시 넣지 않는다`() {
        // uq_insider (rcept_no, repror) — 재실행해도 중복이 쌓이면 안 된다
        val store = FakeStore(listOf(disclosure("R1", "C1", tier = 4)))
            .apply { existingKeys += ("R1" to "홍길동") }
        val client = FakeClient(mapOf("C1" to listOf(elestock("R1"))))

        val summary = DartInsiderCollectService(client, store).collect(listOf("R1"), now)

        assertThat(store.saved).isEmpty()
        assertThat(summary.inserted).isZero()
    }

    @Test
    fun `공시의 stock_code를 그대로 물려준다`() {
        val store = FakeStore(listOf(disclosure("R1", "C1", tier = 4, stockCode = "494120")))
        val client = FakeClient(mapOf("C1" to listOf(elestock("R1"))))

        DartInsiderCollectService(client, store).collect(listOf("R1"), now)

        assertThat(store.saved.single().stockCode).isEqualTo("494120")
    }

    @Test
    fun `한 회사가 실패해도 나머지는 진행한다`() {
        val store = FakeStore(listOf(
            disclosure("R1", "C1", tier = 4),
            disclosure("R2", "C2", tier = 4),
        ))
        val client = object : ElestockPort {
            override fun fetch(corpCode: String): List<ElestockRow> =
                if (corpCode == "C1") throw DartApiException("elestock status=020")
                else listOf(elestock("R2", corpCode = "C2"))
        }

        val summary = DartInsiderCollectService(client, store).collect(listOf("R1", "R2"), now)

        assertThat(store.saved.map { it.rceptNo }).containsExactly("R2")
        assertThat(summary.failures).hasSize(1)
        assertThat(summary.calls).isEqualTo(2)
    }

    @Test
    fun `델타가 비면 호출하지 않는다`() {
        val client = FakeClient(emptyMap())

        val summary = DartInsiderCollectService(client, FakeStore(emptyList())).collect(emptyList(), now)

        assertThat(client.called).isEmpty()
        assertThat(summary.calls).isZero()
    }
}
