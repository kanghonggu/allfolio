package com.allfolio.dart.list

import com.allfolio.dart.DartApiException
import com.allfolio.unifiedasset.infrastructure.entity.DartCollectionRunEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DartDisclosureCollectServiceTest {

    private val endDe = LocalDate.of(2026, 8, 18)
    private val bgnDe = endDe.minusDays(1)
    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    private class FakeClient(private val pages: List<DartListPage>) : ListPort {
        val requestedPages = mutableListOf<Int>()
        override fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int): DartListPage {
            requestedPages += pageNo
            return pages.getOrElse(pageNo - 1) { DartListPage(emptyList(), pages.size, false) }
        }
    }

    private class FakeStore : DartDisclosureCollectService.Store {
        val inserted = mutableListOf<DisclosureInsert>()
        var existing = mutableSetOf<String>()
        override fun insertIgnoringConflicts(rows: List<DisclosureInsert>, collectedAt: LocalDateTime): List<String> {
            val fresh = rows.filter { it.rceptNo !in existing }
            inserted += fresh
            existing += fresh.map { it.rceptNo }
            return fresh.map { it.rceptNo }
        }
    }

    private class FakeRuns : DartDisclosureCollectService.RunLog {
        val saved = mutableListOf<DartCollectionRunEntity>()
        override fun save(run: DartCollectionRunEntity) { saved += run }
    }

    private fun row(rceptNo: String, reportNm: String, stockCode: String? = "005930") = DartListRow(
        rceptNo = rceptNo, corpCode = "00126380", corpName = "삼성전자",
        stockCode = stockCode, corpCls = "Y", reportNm = reportNm,
        rceptDt = endDe, flrNm = "삼성전자", rm = "유",
    )

    private fun service(client: FakeClient, store: FakeStore, runs: FakeRuns) =
        DartDisclosureCollectService(client, store, runs)

    @Test
    fun `전 페이지를 순회한다`() {
        val client = FakeClient(listOf(
            DartListPage(listOf(row("A1", "유상증자결정")), totalPage = 3, emptyResult = false),
            DartListPage(listOf(row("A2", "반기보고서 (2026.06)")), 3, false),
            DartListPage(listOf(row("A3", "기업설명회(IR)개최")), 3, false),
        ))
        val store = FakeStore(); val runs = FakeRuns()

        val summary = service(client, store, runs).collect(bgnDe, endDe, now)

        assertThat(client.requestedPages).containsExactly(1, 2, 3)
        assertThat(summary.pagesFetched).isEqualTo(3)
        assertThat(summary.newCount).isEqualTo(3)
        // newRceptNos가 곧 Task 11의 elestock 호출 대상이다 — 개수만 보고 내용을 안 보면
        // "빈 목록을 반환해도 통과"하는 구멍이 생긴다
        assertThat(summary.newRceptNos).containsExactlyInAnyOrder("A1", "A2", "A3")
    }

    @Test
    fun `화이트리스트 판정 결과를 함께 저장한다`() {
        val client = FakeClient(listOf(DartListPage(listOf(
            row("A1", "단일판매ㆍ공급계약체결              "),
            row("A2", "반기보고서 (2026.06)"),
            row("A3", "기업설명회(IR)개최"),
        ), 1, false)))
        val store = FakeStore()

        service(client, store, FakeRuns()).collect(bgnDe, endDe, now)

        val byId = store.inserted.associateBy { it.rceptNo }
        assertThat(byId["A1"]!!.materialTier).isEqualTo(1)
        assertThat(byId["A1"]!!.reportNmNorm).isEqualTo("단일판매·공급계약체결")
        assertThat(byId["A2"]!!.materialTier).isEqualTo(5)
        assertThat(byId["A3"]!!.isMaterial).isFalse()
        assertThat(byId["A3"]!!.materialTier).isNull()
    }

    @Test
    fun `걸러낸 건도 저장한다`() {
        // 설계 원칙 4 — 무엇을 걸렀는지 되짚을 수 없으면 튜닝이 불가능하다
        val client = FakeClient(listOf(DartListPage(listOf(row("A3", "기업설명회(IR)개최")), 1, false)))
        val store = FakeStore()

        service(client, store, FakeRuns()).collect(bgnDe, endDe, now)

        assertThat(store.inserted).hasSize(1)
    }

    @Test
    fun `정정공시 접두어를 기록한다`() {
        val client = FakeClient(listOf(DartListPage(listOf(
            row("A1", "[기재정정]반기보고서 (2026.06)"),
        ), 1, false)))
        val store = FakeStore()

        service(client, store, FakeRuns()).collect(bgnDe, endDe, now)

        with(store.inserted.single()) {
            assertThat(isCorrection).isTrue()
            assertThat(reportNmNorm).isEqualTo("반기보고서 (2026.06)")
            assertThat(materialTier).isEqualTo(5)  // 접두어를 떼야 잡힌다
        }
    }

    @Test
    fun `공휴일이면 성공으로 기록하고 0건을 보고한다`() {
        val client = FakeClient(listOf(DartListPage(emptyList(), 0, emptyResult = true)))
        val runs = FakeRuns()

        val summary = service(client, FakeStore(), runs).collect(bgnDe, endDe, now)

        assertThat(summary.newCount).isZero()
        assertThat(summary.emptyResult).isTrue()
        assertThat(runs.saved.single().status).isEqualTo("SUCCESS")
    }

    @Test
    fun `실패하면 FAILED로 기록하고 예외를 올린다`() {
        val client = object : ListPort {
            override fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int): DartListPage =
                throw DartApiException("OpenDART status=020")
        }
        val runs = FakeRuns()

        runCatching { DartDisclosureCollectService(client, FakeStore(), runs).collect(bgnDe, endDe, now) }

        with(runs.saved.single()) {
            assertThat(status).isEqualTo("FAILED")
            assertThat(errorMsg).contains("020")
        }
    }

    @Test
    fun `이미 있는 건은 델타에서 빠진다`() {
        val client = FakeClient(listOf(DartListPage(listOf(row("A1", "유상증자결정")), 1, false)))
        val store = FakeStore().apply { existing += "A1" }

        val summary = service(client, store, FakeRuns()).collect(bgnDe, endDe, now)

        assertThat(summary.newCount).isZero()
        assertThat(summary.newRceptNos).isEmpty()
    }
}
