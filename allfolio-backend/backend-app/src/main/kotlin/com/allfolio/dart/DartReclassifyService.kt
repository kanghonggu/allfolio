package com.allfolio.dart

import org.springframework.stereotype.Service

/**
 * 이미 저장된 `dart_disclosure` 행의 `material_tier`·`is_material`을 **현재 화이트리스트로**
 * 다시 판정한다 (S13).
 *
 * 화이트리스트를 고치면 그 효과는 **새로 수집되는 행에만** 적용된다 — 판정이 수집 시점에
 * 한 번 일어나 컬럼에 박히기 때문이다. S13 튜닝 시점에 이미 9,684행이 옛 기준으로 분류돼
 * 있었고, 그중 약 1,000행의 판정이 바뀐다. 그대로 두면 지난 2주 피드가 옛 기준으로 남는다.
 *
 * **판정 로직을 SQL로 옮겨 쓰지 않는다.** [DartWhitelist]를 그대로 부른다 — SQL로 다시
 * 적으면 두 벌이 생기고, 다음 튜닝 때 한쪽만 고쳐도 아무도 모른다.
 *
 * **기본은 드라이런이다.** 소급 정정은 규모를 먼저 재는 것이 이 저장소의 원칙이고,
 * [ReclassifyResult.transitions]가 "무엇이 어디로 가는가"를 그대로 보여 준다. 예상한
 * 전이만 있는지 눈으로 확인한 뒤 `apply=true`로 다시 부른다.
 */
@Service
class DartReclassifyService(private val store: Store) {

    interface Store {
        /** `rcept_no` 오름차순 한 페이지. [after]가 null이면 처음부터 */
        fun page(after: String?, limit: Int): List<Row>

        /** 바뀐 행만 받는다. 반환은 실제로 갱신된 행 수 */
        fun update(rows: List<Update>): Int
    }

    /** 판정에 필요한 것만. `report_nm_norm`이 아니라 **원문**을 읽는다 — 정규화까지 다시 돈다 */
    data class Row(val rceptNo: String, val reportNm: String, val materialTier: Short?)

    data class Update(val rceptNo: String, val materialTier: Short?, val isMaterial: Boolean)

    fun run(apply: Boolean): ReclassifyResult {
        var scanned = 0
        var changed = 0
        var updated = 0
        val transitions = sortedMapOf<String, Int>()
        var after: String? = null

        while (true) {
            val page = store.page(after, PAGE_SIZE)
            if (page.isEmpty()) break
            scanned += page.size
            after = page.last().rceptNo

            val diffs = page.mapNotNull { row ->
                val tier = DartWhitelist.tierOf(DartReportName.normalize(row.reportNm))
                if (tier == row.materialTier) return@mapNotNull null
                transitions.merge(label(row.materialTier, tier), 1, Int::plus)
                Update(row.rceptNo, tier, DartWhitelist.isMaterial(tier))
            }
            changed += diffs.size

            if (apply && diffs.isNotEmpty()) updated += store.update(diffs)

            if (page.size < PAGE_SIZE) break
        }

        return ReclassifyResult(
            applied = apply,
            scanned = scanned,
            changed = changed,
            updated = updated,
            transitions = transitions,
        )
    }

    private fun label(from: Short?, to: Short?) = "${from ?: "none"}→${to ?: "none"}"

    companion object {
        /** `JdbcDisclosureStore.CHUNK_SIZE`와 같은 근거 — 바인드 파라미터 상한에 여유를 둔다 */
        internal const val PAGE_SIZE = 1000
    }
}

/**
 * [ReclassifyResult.updated]는 [changed]와 다를 수 있다 — 드라이런이면 0이다. 둘이 갈리면
 * 갱신이 일부만 먹었다는 뜻이므로 따로 센다.
 */
data class ReclassifyResult(
    val applied: Boolean,
    val scanned: Int,
    val changed: Int,
    val updated: Int,
    /** `"none→6"` → 703 처럼 전이별 건수. 예상과 대조하는 용도다 */
    val transitions: Map<String, Int>,
)
