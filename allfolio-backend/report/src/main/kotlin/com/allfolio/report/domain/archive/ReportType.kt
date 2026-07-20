package com.allfolio.report.domain.archive

/** 기관급 리포트 7종 (리포트명세서 R-01~R-07) */
enum class ReportType {
    MONTHLY_REPORT,     // R-01 월간 운용보고서
    RETURNS,            // R-02 수익률
    DIVIDEND_INTEREST,  // R-03 배당·이자
    COST,               // R-04 비용
    HOLDINGS,           // R-05 월말 보유 명세
    CASHFLOW,           // R-06 현금흐름
    ESG_SCREENING,      // R-07 투자배제·ESG
}
