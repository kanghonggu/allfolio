package com.allfolio.reconciliation.application.rules

import com.allfolio.reconciliation.domain.DiffType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class PositionComparatorTest {

    private fun kisId(symbol: String): UUID = AssetIdDeriver.derive("KIS", symbol)!!

    @Test
    fun `파생 규칙 - 브로커별 프리픽스가 원본 매퍼와 일치한다`() {
        assertEquals(UUID.nameUUIDFromBytes("KIS:005930".toByteArray()), AssetIdDeriver.derive("KIS", "005930"))
        assertEquals(UUID.nameUUIDFromBytes("toss-asset:005930".toByteArray()), AssetIdDeriver.derive("TOSS", "005930"))
        assertEquals(UUID.nameUUIDFromBytes("samsung-asset:KR7005930003".toByteArray()), AssetIdDeriver.derive("SAMSUNG", "KR7005930003"))
        assertEquals(UUID.nameUUIDFromBytes("binance-asset:BTC".toByteArray()), AssetIdDeriver.derive("BINANCE", "BTC"))
        assertEquals(null, AssetIdDeriver.derive("MANUAL", "005930"))
    }

    @Test
    fun `수량 일치 심볼은 diff가 없다`() {
        val diffs = PositionComparator.compare(
            externals = listOf(ExternalPosition("005930", setOf("KIS"), BigDecimal("10"))),
            internals = mapOf(kisId("005930") to BigDecimal("10.0000000000")),
        )
        assertTrue(diffs.isEmpty())
    }

    @Test
    fun `수량 불일치는 VALUE_MISMATCH로 기록한다`() {
        val diffs = PositionComparator.compare(
            externals = listOf(ExternalPosition("005930", setOf("KIS"), BigDecimal("10"))),
            internals = mapOf(kisId("005930") to BigDecimal("8")),
        )
        val d = diffs.single()
        assertEquals(DiffType.VALUE_MISMATCH, d.diffType)
        assertEquals("005930", d.symbol)
        assertEquals("quantity", d.fieldName)
        assertEquals(0, BigDecimal("8").compareTo(d.internalValue))
        assertEquals(0, BigDecimal("10").compareTo(d.externalValue))
        assertEquals(0, BigDecimal("2").compareTo(d.diffValue))
    }

    @Test
    fun `외부에만 있으면 MISSING_INTERNAL, 내부에만 있으면 MISSING_EXTERNAL`() {
        val orphanInternal = UUID.randomUUID()
        val diffs = PositionComparator.compare(
            externals = listOf(ExternalPosition("035720", setOf("KIS"), BigDecimal("5"))),
            internals = mapOf(orphanInternal to BigDecimal("3")),
        )
        assertEquals(2, diffs.size)
        val missingInternal = diffs.first { it.diffType == DiffType.MISSING_INTERNAL }
        assertEquals("035720", missingInternal.symbol)
        val missingExternal = diffs.first { it.diffType == DiffType.MISSING_EXTERNAL }
        assertEquals(orphanInternal.toString(), missingExternal.extras["assetId"])
    }

    @Test
    fun `수량 0 포지션은 양쪽 모두 비교에서 제외한다`() {
        val diffs = PositionComparator.compare(
            externals = listOf(ExternalPosition("005930", setOf("KIS"), BigDecimal.ZERO)),
            internals = mapOf(kisId("035720") to BigDecimal("0.0000000000")),
        )
        assertTrue(diffs.isEmpty())
    }

    @Test
    fun `같은 심볼을 여러 브로커로 보유하면 후보 assetId 수량을 합산해 비교한다`() {
        val diffs = PositionComparator.compare(
            externals = listOf(ExternalPosition("BTC", setOf("BINANCE", "TOSS"), BigDecimal("2"))),
            internals = mapOf(
                AssetIdDeriver.derive("BINANCE", "BTC")!! to BigDecimal("1.5"),
                AssetIdDeriver.derive("TOSS", "BTC")!! to BigDecimal("0.5"),
            ),
        )
        assertTrue(diffs.isEmpty())
    }
}
