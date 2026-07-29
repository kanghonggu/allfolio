package com.allfolio.unifiedasset.application.usecase

/** 배제 사유 1건. */
data class ExclusionEntry(val listName: String, val reason: String)

/**
 * v1 내장 배제 프리셋 — 심볼 → 배제 정보 (R2 #42).
 * 실제 회사를 배제로 단정하지 않도록 예시(placeholder) 심볼로만 시드한다.
 * 실제 배제리스트 큐레이션·사용자 리스트·CSV 반입은 후속(SCR-RPT-11).
 */
object EsgExclusionPreset {
    val entries: Map<String, ExclusionEntry> = mapOf(
        "EXCL-COAL-01" to ExclusionEntry("예시 프리셋", "석탄"),
        "EXCL-WEAPON-01" to ExclusionEntry("예시 프리셋", "논란무기"),
    )

    fun lookup(symbol: String?): ExclusionEntry? = symbol?.let { entries[it] }
}
