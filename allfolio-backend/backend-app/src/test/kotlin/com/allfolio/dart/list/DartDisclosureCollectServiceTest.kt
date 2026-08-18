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

    private class FakeRuns(
        /** true면 save()가 던진다 — 델타가 그래도 반환되는지(Critical #1)를 검증하는 용도 */
        private val throwOnSave: Boolean = false,
    ) : DartDisclosureCollectService.RunLog {
        val saved = mutableListOf<DartCollectionRunEntity>()
        override fun save(run: DartCollectionRunEntity) {
            if (throwOnSave) throw IllegalStateException("Neon 연결 끊김(가짜)")
            saved += run
        }
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
        // apiCalls는 이전까지 어떤 테스트도 단언하지 않았다 — apiCalls++ 또는
        // run.apiCalls = apiCalls를 지워도 나머지 전부가 초록이었다(품질 리뷰 발견)
        assertThat(summary.apiCalls).isEqualTo(3)
        with(runs.saved.single()) {
            assertThat(apiCalls).isEqualTo(3)
            assertThat(pagesFetched).isEqualTo(3)
            assertThat(newCount).isEqualTo(3)
        }
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
        // 그대로 통과시키기만 하는 필드들 — 이전까지 어느 테스트도 단언하지 않아
        // stockCode/corpCls를 맞바꿔도(둘 다 String?이라 컴파일도 통과) 초록으로 남았다(품질 리뷰 발견)
        with(byId["A1"]!!) {
            assertThat(corpCode).isEqualTo("00126380")
            assertThat(corpName).isEqualTo("삼성전자")
            assertThat(stockCode).isEqualTo("005930")
            assertThat(corpCls).isEqualTo("Y")
            assertThat(rceptDt).isEqualTo(endDe)
            assertThat(flrNm).isEqualTo("삼성전자")
            assertThat(rm).isEqualTo("유")
        }
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
    fun `실패 전까지의 진척도가 기록된다`() {
        // catch 블록이 run.apiCalls·run.pagesFetched를 안 채우면, 12페이지 중 5페이지째에서
        // 죽어도 pages_fetched=0·api_calls=0으로 남는다 — "바로 죽었다"와 "여러 페이지 돌다
        // 죽었다"를 사고 조사에서 구분할 수 없다(품질 리뷰 발견)
        val client = object : ListPort {
            override fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int): DartListPage {
                if (pageNo == 3) throw DartApiException("OpenDART status=020")
                return DartListPage(listOf(row("R$pageNo", "유상증자결정")), totalPage = 5, emptyResult = false)
            }
        }
        val runs = FakeRuns()

        runCatching { DartDisclosureCollectService(client, FakeStore(), runs).collect(bgnDe, endDe, now) }

        with(runs.saved.single()) {
            assertThat(status).isEqualTo("FAILED")
            assertThat(pagesFetched).isEqualTo(2)
            assertThat(apiCalls).isEqualTo(2)
        }
    }

    @Test
    fun `감사 로그 저장이 실패해도 이미 커밋된 델타는 호출자에게 반환된다`() {
        // Critical — store.insertIgnoringConflicts는 @Transactional이라 이 메서드가 반환된
        // 시점에 dart_disclosure 행은 이미 커밋돼 있다. 그 뒤 runLog.save가 던진다고 해서
        // collect() 자체가 예외로 끝나면, 이미 커밋된 행의 델타를 호출자(elestock 호출부)가
        // 영원히 못 받는다 — 다음 재수집도 ON CONFLICT DO NOTHING이 그 행을 조용히 걸러낸다
        val client = FakeClient(listOf(DartListPage(listOf(row("A1", "유상증자결정")), 1, false)))
        val store = FakeStore()
        val runs = FakeRuns(throwOnSave = true)

        val summary = service(client, store, runs).collect(bgnDe, endDe, now)

        assertThat(summary.newRceptNos).containsExactly("A1")
        assertThat(summary.newCount).isEqualTo(1)
        // 감사 로그는 못 남았다 — save()가 던졌으니 saved에는 아무것도 안 쌓인다.
        // 이건 받아들이는 대가지, 놓친 게 아니다(saveRunLog KDoc 참고)
        assertThat(runs.saved).isEmpty()
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
