package com.allfolio.esg.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EsgEngineTest {

    // ── scoreOf ──────────────────────────────────────────────

    @Test
    fun `CRYPTO 점수 - E20 S50 G40`() {
        val (e, s, g) = EsgEngine.scoreOf("CRYPTO")
        assertEquals(20, e)
        assertEquals(50, s)
        assertEquals(40, g)
    }

    @Test
    fun `STOCK 점수 - E60 S65 G65`() {
        val (e, s, g) = EsgEngine.scoreOf("STOCK")
        assertEquals(60, e)
        assertEquals(65, s)
        assertEquals(65, g)
    }

    @Test
    fun `REAL_ESTATE 점수 - E55 S70 G65`() {
        val (e, s, g) = EsgEngine.scoreOf("REAL_ESTATE")
        assertEquals(55, e)
        assertEquals(70, s)
        assertEquals(65, g)
    }

    @Test
    fun `JEONSE 점수 - E65 S80 G70`() {
        val (e, s, g) = EsgEngine.scoreOf("JEONSE")
        assertEquals(65, e)
        assertEquals(80, s)
        assertEquals(70, g)
    }

    @Test
    fun `VEHICLE 점수 - E35 S60 G55`() {
        val (e, s, g) = EsgEngine.scoreOf("VEHICLE")
        assertEquals(35, e)
        assertEquals(60, s)
        assertEquals(55, g)
    }

    @Test
    fun `GOLD 점수 - E45 S55 G55`() {
        val (e, s, g) = EsgEngine.scoreOf("GOLD")
        assertEquals(45, e)
        assertEquals(55, s)
        assertEquals(55, g)
    }

    @Test
    fun `CASH 점수 - E80 S75 G80`() {
        val (e, s, g) = EsgEngine.scoreOf("CASH")
        assertEquals(80, e)
        assertEquals(75, s)
        assertEquals(80, g)
    }

    @Test
    fun `ETC 점수 - E60 S60 G60`() {
        val (e, s, g) = EsgEngine.scoreOf("ETC")
        assertEquals(60, e)
        assertEquals(60, s)
        assertEquals(60, g)
    }

    @Test
    fun `알 수 없는 타입은 ETC 기본값 반환`() {
        val (e, s, g) = EsgEngine.scoreOf("UNKNOWN_TYPE")
        assertEquals(60, e)
        assertEquals(60, s)
        assertEquals(60, g)
    }
}
