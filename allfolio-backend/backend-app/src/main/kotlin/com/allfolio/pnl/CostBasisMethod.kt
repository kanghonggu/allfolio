package com.allfolio.pnl

/**
 * 포지션 원가 계산 방식
 *
 * AVG_COST: 매수 평균단가 — (Σ lots.price × lots.qty) / Σ lots.qty
 * FIFO:     선입선출 — 가장 오래된 lot의 단가를 원가로 사용 (실현손익 계산 기준)
 *
 * 기본값: AVG_COST (기존 동작 유지)
 */
enum class CostBasisMethod {
    AVG_COST,
    FIFO,
}
