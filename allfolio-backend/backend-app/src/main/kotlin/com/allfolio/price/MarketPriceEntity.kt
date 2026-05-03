package com.allfolio.price

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * 실시간 WebSocket 수신 틱 데이터 저장 테이블
 *
 * 저장 전략:
 *   - MarketPriceBatchWriter: 100ms 단위로 메모리 버퍼 → 배치 INSERT
 *   - 같은 symbol 연속 tick: 모두 저장 (분석/백테스트용)
 *   - 오래된 데이터: 별도 파티션/삭제 정책 적용 예정 (현재 미구현)
 *
 * 인덱스:
 *   (symbol, timestamp) → 시계열 조회 최적화
 *   (exchange, timestamp) → 거래소별 조회
 */
@Entity
@Table(
    name = "market_price_tick",
    indexes = [
        Index(name = "idx_mpt_symbol_ts",   columnList = "symbol, tick_timestamp"),
        Index(name = "idx_mpt_exchange_ts",  columnList = "exchange, tick_timestamp"),
    ]
)
class MarketPriceTickEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 20)
    val exchange: String,       // "BINANCE" | "KIS" | "KIWOOM"

    @Column(nullable = false, length = 20)
    val symbol: String,         // "BTCUSDT" | "005930" 등

    @Column(nullable = false, precision = 30, scale = 10)
    val price: BigDecimal,

    @Column(nullable = false, precision = 30, scale = 10)
    val volume: BigDecimal = BigDecimal.ZERO,

    @Column(name = "tick_timestamp", nullable = false)
    val tickTimestamp: Instant,     // WebSocket 수신 타임스탬프

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now(),
)
