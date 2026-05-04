package com.allfolio.unifiedasset.application.usecase

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class DividendReportServiceTest {

    @Mock
    lateinit var jdbc: JdbcTemplate

    private val userId = UUID.randomUUID()

    // Mock JdbcTemplate은 Collection 반환 메서드에 기본으로 빈 리스트를 반환한다.
    // DividendReportService의 모든 query 호출은 runCatching으로 감싸여 있어
    // 예외 또는 빈 결과 모두 안전하게 처리된다.

    @Test
    fun `배당 내역 없으면 totalDividend 0 반환`() {
        val svc = DividendReportService(jdbc)
        val result = svc.report(userId, "YTD")
        assertEquals(BigDecimal.ZERO, result.totalDividend)
        assertEquals(0, result.receiptCount)
        assertTrue(result.monthlySeries.isEmpty())
        assertTrue(result.bySymbol.isEmpty())
        assertTrue(result.recentHistory.isEmpty())
    }

    @Test
    fun `period YTD - period 필드가 YTD로 설정됨`() {
        val svc = DividendReportService(jdbc)
        val result = svc.report(userId, "YTD")
        assertEquals("YTD", result.period)
    }

    @Test
    fun `period 전체 - period 필드가 전체로 설정됨`() {
        val svc = DividendReportService(jdbc)
        val result = svc.report(userId, "전체")
        assertEquals("전체", result.period)
    }
}
