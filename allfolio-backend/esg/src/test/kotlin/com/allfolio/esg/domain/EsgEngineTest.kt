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

    // ── rating ────────────────────────────────────────────────

    @Test
    fun `총점 85 이상 - A+`() = assertEquals("A+", EsgEngine.rating(bd("85")))

    @Test
    fun `총점 75 - A`() = assertEquals("A", EsgEngine.rating(bd("75")))

    @Test
    fun `총점 65 - B+`() = assertEquals("B+", EsgEngine.rating(bd("65")))

    @Test
    fun `총점 55 - B`() = assertEquals("B", EsgEngine.rating(bd("55")))

    @Test
    fun `총점 45 - C+`() = assertEquals("C+", EsgEngine.rating(bd("45")))

    @Test
    fun `총점 44 - C`() = assertEquals("C", EsgEngine.rating(bd("44")))

    // ── calculate ─────────────────────────────────────────────

    @Test
    fun `자산 없으면 EsgException`() {
        assertThrows(EsgException::class.java) {
            EsgEngine.calculate(emptyList())
        }
    }

    @Test
    fun `CASH 단일 자산 - 총점 78_5 등급 A`() {
        // E=80, S=75, G=80 → total = 80×0.35 + 75×0.30 + 80×0.35 = 28 + 22.5 + 28 = 78.5
        val result = EsgEngine.calculate(listOf(
            EsgEngine.AssetInput("CASH", bd("1000000"))
        ))
        assertEquals(bd("80.00"), result.environmental)
        assertEquals(bd("75.00"), result.social)
        assertEquals(bd("80.00"), result.governance)
        assertEquals(0, bd("78.50").compareTo(result.total))
        assertEquals("A", result.rating)
    }

    @Test
    fun `CRYPTO 단일 자산 - 총점 36_0 등급 C`() {
        // E=20, S=50, G=40 → total = 20×0.35 + 50×0.30 + 40×0.35 = 7 + 15 + 14 = 36
        val result = EsgEngine.calculate(listOf(
            EsgEngine.AssetInput("CRYPTO", bd("1000000"))
        ))
        assertEquals(0, bd("36.00").compareTo(result.total))
        assertEquals("C", result.rating)
    }

    @Test
    fun `두 자산 동일 비중 - 가중 평균 계산`() {
        // CASH(1000): E=80, S=75, G=80
        // CRYPTO(1000): E=20, S=50, G=40
        // 가중: E=(80+20)/2=50, S=(75+50)/2=62.5, G=(80+40)/2=60
        // total = 50×0.35 + 62.5×0.30 + 60×0.35 = 17.5 + 18.75 + 21.0 = 57.25 → B
        val result = EsgEngine.calculate(listOf(
            EsgEngine.AssetInput("CASH",   bd("1000")),
            EsgEngine.AssetInput("CRYPTO", bd("1000")),
        ))
        assertEquals(0, bd("50.00").compareTo(result.environmental))
        assertEquals(0, bd("57.25").compareTo(result.total))
        assertEquals("B", result.rating)
    }

    @Test
    fun `비중이 다른 두 자산 - 큰 자산이 점수에 더 많이 반영`() {
        // CASH(9000, 90%): E=80
        // CRYPTO(1000, 10%): E=20
        // E_portfolio = 80×0.9 + 20×0.1 = 72 + 2 = 74
        val result = EsgEngine.calculate(listOf(
            EsgEngine.AssetInput("CASH",   bd("9000")),
            EsgEngine.AssetInput("CRYPTO", bd("1000")),
        ))
        assertEquals(0, bd("74.00").compareTo(result.environmental))
    }

    private fun bd(s: String) = BigDecimal(s)
}
