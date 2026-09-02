package com.allfolio.dart

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * S13 재분류. 값은 2026-08-18~09-01 운영 실측에서 실제로 걸린 이름들이다.
 */
class DartReclassifyServiceTest {

    /** 페이지를 실제로 나눠 주는 가짜. 커서 처리를 검증해야 해서 리스트를 그냥 돌려주면 안 된다 */
    private class FakeStore(rows: List<DartReclassifyService.Row>) : DartReclassifyService.Store {
        private val sorted = rows.sortedBy { it.rceptNo }
        val applied = mutableListOf<DartReclassifyService.Update>()
        var pageCalls = 0

        override fun page(after: String?, limit: Int): List<DartReclassifyService.Row> {
            pageCalls++
            return sorted.filter { after == null || it.rceptNo > after }.take(limit)
        }

        override fun update(rows: List<DartReclassifyService.Update>): Int {
            applied += rows
            return rows.size
        }
    }

    private fun row(no: String, name: String, tier: Short?) =
        DartReclassifyService.Row(no, name, tier)

    @Test
    fun `바뀌는 행만 센다 — 그대로인 행은 전이에도 갱신에도 안 들어간다`() {
        val store = FakeStore(
            listOf(
                // 그대로 — 이미 T1이고 지금도 T1
                row("00000000000001", "유상증자결정", 1),
                // 승격 — S13에서 넣은 키워드
                row("00000000000002", "주요사항보고서(타법인주식및출자증권양수결정)", null),
                // 강등 — 행정적 정지
                row("00000000000003", "주권매매거래정지해제              (액면병합 주권 변경상장)", 3),
            ),
        )

        val result = DartReclassifyService(store).run(apply = true)

        assertThat(result.scanned).isEqualTo(3)
        assertThat(result.changed).isEqualTo(2)
        assertThat(result.updated).isEqualTo(2)
        assertThat(result.transitions).containsOnly(
            org.assertj.core.api.Assertions.entry("none→1", 1),
            org.assertj.core.api.Assertions.entry("3→none", 1),
        )
        assertThat(store.applied.map { it.rceptNo })
            .containsExactly("00000000000002", "00000000000003")
    }

    @Test
    fun `강등된 행은 is_material도 함께 내려간다`() {
        // material_tier만 고치고 is_material을 두면 두 컬럼이 서로 다른 말을 한다 —
        // 피드 인덱스가 is_material 조건이라 화면에는 계속 남는다.
        val store = FakeStore(
            listOf(row("00000000000001", "주권매매거래정지              (주식의 병합, 분할 등 전자등록 변경, 말소)", 3)),
        )

        DartReclassifyService(store).run(apply = true)

        assertThat(store.applied).singleElement().satisfies({
            assertThat(it.materialTier).isNull()
            assertThat(it.isMaterial).isFalse()
        })
    }

    @Test
    fun `드라이런은 아무것도 쓰지 않지만 규모는 그대로 알려 준다`() {
        val store = FakeStore(
            listOf(row("00000000000001", "타인에대한채무보증결정", null)),
        )

        val result = DartReclassifyService(store).run(apply = false)

        assertThat(result.applied).isFalse()
        assertThat(result.changed).isEqualTo(1)
        assertThat(result.updated).isZero()
        assertThat(result.transitions).containsEntry("none→3", 1)
        assertThat(store.applied).isEmpty()
    }

    @Test
    fun `페이지 경계를 넘어서도 커서가 진행한다`() {
        // 🔴 커서를 안 넘기면 첫 페이지를 무한히 다시 읽는다. 실측 9,684행이 PAGE_SIZE의
        // 아홉 배가 넘으므로 이 회귀는 운영에서 곧바로 무한 루프가 된다.
        val rows = (1..DartReclassifyService.PAGE_SIZE + 250).map {
            row("%014d".format(it), "타인에대한채무보증결정", null)
        }

        val result = DartReclassifyService(FakeStore(rows)).run(apply = false)

        assertThat(result.scanned).isEqualTo(rows.size)
        assertThat(result.changed).isEqualTo(rows.size)
    }

    @Test
    fun `정규화를 거친다 — 아래아가 든 원문도 잡는다`() {
        // 저장된 report_nm은 원문이라 아래아(U+318D)가 들어 있다. normalize를 빼면
        // 이 재분류가 오히려 멀쩡한 행을 미해당으로 떨어뜨린다.
        val store = FakeStore(listOf(row("00000000000001", "단일판매ㆍ공급계약체결", null)))

        val result = DartReclassifyService(store).run(apply = false)

        assertThat(result.transitions).containsEntry("none→1", 1)
    }
}
