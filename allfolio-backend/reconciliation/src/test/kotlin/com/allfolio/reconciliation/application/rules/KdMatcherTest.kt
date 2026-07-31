package com.allfolio.reconciliation.application.rules

import com.allfolio.reconciliation.application.RuleDiff
import com.allfolio.reconciliation.domain.DiffType
import com.allfolio.reconciliation.domain.KdValueType
import com.allfolio.reconciliation.infrastructure.entity.ReconKdEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class KdMatcherTest {

    private val userId = UUID.randomUUID()
    private val runDate = LocalDate.of(2026, 7, 31)

    private fun kd(
        symbol: String? = "005930",
        field: String? = "quantity",
        type: KdValueType = KdValueType.ABS,
        allow: String = "2",
        start: LocalDate = runDate.minusDays(30),
        end: LocalDate = LocalDate.of(9999, 12, 31),
        useYn: Boolean = true,
    ) = ReconKdEntity(
        userId = userId, kdCode = "KD-1", targetSymbol = symbol, targetField = field,
        valueType = type, allowValue = BigDecimal(allow), reason = "브로커 수수료 단수차",
        apldStrtDt = start, apldEndDt = end, useYn = useYn,
    )

    private fun diff(
        symbol: String? = "005930",
        field: String? = "quantity",
        internal: String? = "8",
        external: String? = "10",
        diffValue: String? = "2",
    ) = RuleDiff(
        symbol = symbol, fieldName = field, diffType = DiffType.VALUE_MISMATCH,
        internalValue = internal?.let(::BigDecimal), externalValue = external?.let(::BigDecimal),
        diffValue = diffValue?.let(::BigDecimal),
    )

    @Test
    fun `ABS - 허용치 이내면 흡수한다`() {
        val kd = kd(type = KdValueType.ABS, allow = "2")
        assertEquals(kd.id, KdMatcher.match(listOf(kd), diff(diffValue = "2"), runDate)?.id)
        assertEquals(kd.id, KdMatcher.match(listOf(kd), diff(diffValue = "-2"), runDate)?.id)
        assertNull(KdMatcher.match(listOf(kd), diff(internal = "7", external = "10", diffValue = "3"), runDate))
    }

    @Test
    fun `RATIO - 내부값 대비 비율로 판정하고 내부 0이면 매칭하지 않는다`() {
        val kd = kd(type = KdValueType.RATIO, allow = "0.25")
        assertEquals(kd.id, KdMatcher.match(listOf(kd), diff(diffValue = "2"), runDate)?.id) // 2/8 = 0.25
        assertNull(KdMatcher.match(listOf(kd), diff(diffValue = "3"), runDate))              // 3/8 > 0.25
        assertNull(KdMatcher.match(listOf(kd), diff(internal = "0", diffValue = "2"), runDate))
        assertNull(KdMatcher.match(listOf(kd), diff(internal = null, diffValue = "2"), runDate))
    }

    @Test
    fun `와일드카드 - target null은 모든 심볼·필드에 매칭된다`() {
        val anySymbol = kd(symbol = null)
        assertEquals(anySymbol.id, KdMatcher.match(listOf(anySymbol), diff(symbol = "035720"), runDate)?.id)
        val anyField = kd(field = null)
        assertEquals(anyField.id, KdMatcher.match(listOf(anyField), diff(field = "snapshot", diffValue = "1"), runDate)?.id)
    }

    @Test
    fun `심볼·필드 불일치는 매칭하지 않는다`() {
        assertNull(KdMatcher.match(listOf(kd(symbol = "035720")), diff(symbol = "005930"), runDate))
        assertNull(KdMatcher.match(listOf(kd(field = "price")), diff(field = "quantity"), runDate))
    }

    @Test
    fun `유효기간 경계 - 기간 밖이나 use_yn false는 매칭하지 않는다`() {
        assertNull(KdMatcher.match(listOf(kd(start = runDate.plusDays(1))), diff(), runDate))
        assertNull(KdMatcher.match(listOf(kd(end = runDate.minusDays(1))), diff(), runDate))
        assertEquals(kd().kdCode, KdMatcher.match(listOf(kd(start = runDate, end = runDate)), diff(), runDate)?.kdCode)
        assertNull(KdMatcher.match(listOf(kd(useYn = false)), diff(), runDate))
    }

    @Test
    fun `diffValue가 없으면(MISSING 계열) 매칭하지 않는다`() {
        assertNull(KdMatcher.match(listOf(kd()), diff(diffValue = null), runDate))
    }
}
