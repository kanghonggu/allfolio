package com.allfolio.reconciliation.application.rules

import com.allfolio.reconciliation.application.ReconContext
import com.allfolio.reconciliation.application.ReconRule
import com.allfolio.reconciliation.application.RuleDiff
import com.allfolio.reconciliation.application.RuleKind
import com.allfolio.reconciliation.application.RuleResult
import com.allfolio.reconciliation.domain.DiffType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 검증 룰 4종 (P2 #13, v2 스펙 §3). 원천 데이터는 읽기 전용 네이티브 쿼리로만 참조.
 * 임계값은 companion 상수 — 조정 필요해지면 @ConfigurationProperties로 승격.
 */

/** ua_assets 음수 수량 탐지. */
@Component
class NegativeQuantityRule(private val jdbc: JdbcTemplate) : ReconRule {
    override val code = "NEGATIVE_QUANTITY"
    override val kind = RuleKind.VALIDATION

    override fun execute(ctx: ReconContext): RuleResult {
        val total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ua_assets WHERE user_id = ?", Int::class.java, ctx.userId,
        ) ?: 0
        val diffs = jdbc.query(
            "SELECT COALESCE(symbol, name) AS symbol, quantity FROM ua_assets WHERE user_id = ? AND quantity < 0",
            { rs, _ ->
                RuleDiff(
                    symbol = rs.getString("symbol"), fieldName = "quantity",
                    diffType = DiffType.RULE_VIOLATION,
                    externalValue = rs.getBigDecimal("quantity"),
                )
            },
            ctx.userId,
        )
        return RuleResult(checkedCnt = total, diffs = diffs)
    }
}

/**
 * 동기화 대상 계좌의 오류/미동기화/장기 미동기화 탐지.
 * provider 집합은 unified-asset DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS 복제
 * (모듈 코드 의존 금지 원칙 — 데이터 계약으로 간주, 변경 시 양쪽 동기화 필요).
 */
@Component
class StaleSyncRule(private val jdbc: JdbcTemplate) : ReconRule {
    override val code = "STALE_SYNC"
    override val kind = RuleKind.VALIDATION

    override fun execute(ctx: ReconContext): RuleResult {
        data class Row(val name: String, val provider: String, val status: String, val lastSyncedAt: java.sql.Timestamp?)

        val providers = SYNC_ELIGIBLE_PROVIDERS.joinToString(",") { "'$it'" }
        val rows = jdbc.query(
            "SELECT account_name, provider, status, last_synced_at FROM ua_accounts WHERE user_id = ? AND provider IN ($providers)",
            { rs, _ -> Row(rs.getString("account_name"), rs.getString("provider"), rs.getString("status"), rs.getTimestamp("last_synced_at")) },
            ctx.userId,
        )
        val now = java.time.LocalDateTime.now()
        val diffs = rows.mapNotNull { row ->
            val reason = violationReason(row.status, row.lastSyncedAt?.toLocalDateTime(), now)
                ?: return@mapNotNull null
            RuleDiff(
                fieldName = "lastSyncedAt", diffType = DiffType.RULE_VIOLATION,
                extras = mapOf(
                    "accountName" to row.name, "provider" to row.provider,
                    "status" to row.status, "lastSyncedAt" to (row.lastSyncedAt?.toString() ?: "-"),
                    "reason" to reason,
                ),
            )
        }
        return RuleResult(checkedCnt = rows.size, diffs = diffs)
    }

    companion object {
        const val MAX_AGE_HOURS = 26L
        val SYNC_ELIGIBLE_PROVIDERS = setOf("KIS", "BINANCE", "UPBIT", "BITHUMB", "COINONE", "BYBIT", "OKX", "WALLET", "STOCK")

        /** 순수 판정 — 위반이면 사유, 정상이면 null. */
        fun violationReason(status: String, lastSyncedAt: java.time.LocalDateTime?, now: java.time.LocalDateTime): String? = when {
            status == "ERROR" -> "동기화 실패 상태"
            lastSyncedAt == null -> "한 번도 동기화되지 않음"
            lastSyncedAt.isBefore(now.minusHours(MAX_AGE_HOURS)) -> "${MAX_AGE_HOURS}시간 이상 미동기화"
            else -> null
        }
    }
}

/** trade_raw 중복 거래 후보 — 동일 (asset, type, qty, price, executed_at)이 2건 이상. */
@Component
class DuplicateTradeRule(private val jdbc: JdbcTemplate) : ReconRule {
    override val code = "DUPLICATE_TRADE"
    override val kind = RuleKind.VALIDATION

    override fun execute(ctx: ReconContext): RuleResult {
        val since = ctx.runDate.minusDays(LOOKBACK_DAYS).atStartOfDay()
        val total = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM trade_raw t
            JOIN portfolios p ON t.portfolio_id = p.id
            WHERE p.user_id = ? AND p.deleted_at IS NULL AND t.executed_at >= ?
            """.trimIndent(),
            Int::class.java, ctx.userId, since,
        ) ?: 0
        val diffs = jdbc.query(
            """
            SELECT t.asset_id, t.trade_type, t.quantity, t.price, t.executed_at, COUNT(*) AS cnt
            FROM trade_raw t
            JOIN portfolios p ON t.portfolio_id = p.id
            WHERE p.user_id = ? AND p.deleted_at IS NULL AND t.executed_at >= ?
            GROUP BY t.asset_id, t.trade_type, t.quantity, t.price, t.executed_at
            HAVING COUNT(*) > 1
            """.trimIndent(),
            { rs, _ ->
                RuleDiff(
                    fieldName = "trade", diffType = DiffType.RULE_VIOLATION,
                    externalValue = BigDecimal(rs.getInt("cnt")),
                    extras = mapOf(
                        "assetId" to rs.getString("asset_id"),
                        "tradeType" to rs.getString("trade_type"),
                        "quantity" to rs.getBigDecimal("quantity").toPlainString(),
                        "price" to rs.getBigDecimal("price").toPlainString(),
                        "executedAt" to rs.getTimestamp("executed_at").toString(),
                        "count" to rs.getInt("cnt").toString(),
                    ),
                )
            },
            ctx.userId, since,
        )
        return RuleResult(checkedCnt = total, diffs = diffs)
    }

    companion object {
        const val LOOKBACK_DAYS = 7L
    }
}

/** 거래가 있는 포트폴리오의 기준일 position_daily 스냅샷 부재 탐지. */
@Component
class SnapshotMissingRule(private val jdbc: JdbcTemplate) : ReconRule {
    override val code = "SNAPSHOT_MISSING"
    override val kind = RuleKind.VALIDATION

    override fun execute(ctx: ReconContext): RuleResult {
        data class Row(val portfolioId: String, val name: String)

        val cutoff = ctx.runDate.plusDays(1).atStartOfDay()
        val withTrades = jdbc.query(
            """
            SELECT DISTINCT p.id, p.name FROM portfolios p
            JOIN trade_raw t ON t.portfolio_id = p.id
            WHERE p.user_id = ? AND p.deleted_at IS NULL AND t.executed_at < ?
            """.trimIndent(),
            { rs, _ -> Row(rs.getString("id"), rs.getString("name")) },
            ctx.userId, cutoff,
        )
        val diffs = withTrades.mapNotNull { row ->
            val cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM position_daily WHERE portfolio_id = CAST(? AS uuid) AND date = ?",
                Int::class.java, row.portfolioId, ctx.runDate,
            ) ?: 0
            if (cnt > 0) return@mapNotNull null
            RuleDiff(
                fieldName = "snapshot", diffType = DiffType.RULE_VIOLATION,
                extras = mapOf("portfolioId" to row.portfolioId, "portfolioName" to row.name, "date" to ctx.runDate.toString()),
            )
        }
        return RuleResult(checkedCnt = withTrades.size, diffs = diffs)
    }
}
