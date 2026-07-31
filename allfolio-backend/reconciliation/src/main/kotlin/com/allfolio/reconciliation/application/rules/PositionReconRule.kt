package com.allfolio.reconciliation.application.rules

import com.allfolio.reconciliation.application.ReconContext
import com.allfolio.reconciliation.application.ReconRule
import com.allfolio.reconciliation.application.RuleKind
import com.allfolio.reconciliation.application.RuleResult
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/**
 * 포지션 직접 비교 대사 (P2 #14/15, v2 스펙 §4).
 *
 * 외부: ua_assets(브로커 동기화 결과)를 user×symbol로 집계 — trade_raw 파이프라인이 있는
 *       provider(KIS/TOSS/SAMSUNG/BINANCE)만 대상(수동 계좌는 내부 포지션이 없어 제외).
 * 내부: position_daily(FIFO 재계산)를 기준일 이하 최근 스냅샷 date 기준 asset_id로 집계.
 * 매칭: 외부 심볼 × provider → 기대 assetId 파생(AssetIdDeriver, 단방향 해시라 역해석 불가).
 */
@Component
class PositionReconRule(private val jdbc: JdbcTemplate) : ReconRule {
    override val code = "POSITION_RECON"
    override val kind = RuleKind.RECONCILIATION

    override fun execute(ctx: ReconContext): RuleResult {
        val providers = AssetIdDeriver.RECONCILABLE_PROVIDERS.joinToString(",") { "'$it'" }

        data class ExtRow(val symbol: String, val provider: String, val qty: BigDecimal)
        val extRows = jdbc.query(
            """
            SELECT UPPER(TRIM(a.symbol)) AS symbol, acc.provider, SUM(a.quantity) AS qty
            FROM ua_assets a
            JOIN ua_accounts acc ON a.account_id = acc.id
            WHERE a.user_id = ? AND a.symbol IS NOT NULL AND acc.provider IN ($providers)
            GROUP BY UPPER(TRIM(a.symbol)), acc.provider
            """.trimIndent(),
            { rs, _ -> ExtRow(rs.getString("symbol"), rs.getString("provider"), rs.getBigDecimal("qty")) },
            ctx.userId,
        )
        val externals = extRows.groupBy { it.symbol }.map { (symbol, rows) ->
            ExternalPosition(
                symbol = symbol,
                providers = rows.map { it.provider }.toSet(),
                quantity = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.qty },
            )
        }

        // 기준일 이하 최근 스냅샷 date 기준 내부 포지션 (유저 포트폴리오 합산)
        val internals = jdbc.query(
            """
            SELECT pd.asset_id, SUM(pd.quantity) AS qty
            FROM position_daily pd
            JOIN portfolios p ON pd.portfolio_id = p.id
            WHERE p.user_id = ? AND p.deleted_at IS NULL
              AND pd.date = (
                  SELECT MAX(pd2.date) FROM position_daily pd2
                  JOIN portfolios p2 ON pd2.portfolio_id = p2.id
                  WHERE p2.user_id = ? AND p2.deleted_at IS NULL AND pd2.date <= ?
              )
            GROUP BY pd.asset_id
            """.trimIndent(),
            { rs, _ -> rs.getObject("asset_id", UUID::class.java) to rs.getBigDecimal("qty") },
            ctx.userId, ctx.userId, ctx.runDate,
        ).toMap()

        val diffs = PositionComparator.compare(externals, internals)
        return RuleResult(checkedCnt = externals.size + internals.size, diffs = diffs)
    }
}
