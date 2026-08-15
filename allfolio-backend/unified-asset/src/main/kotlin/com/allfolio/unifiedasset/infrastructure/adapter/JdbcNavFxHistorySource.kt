package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.report.domain.returns.NavFxPoint
import com.allfolio.unifiedasset.application.usecase.NavFxHistorySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** nav_currency_daily 행 한 건 */
data class CurrencyRow(val currency: String, val valueNative: BigDecimal, val fxRate: BigDecimal)

/**
 * performance_daily + nav_currency_daily → [NavFxPoint] (AF-106).
 *
 * 사용자 단위: portfolio_id = userId ([JdbcNavHistorySource]와 같은 규약).
 */
@Component
class JdbcNavFxHistorySource(private val jdbc: JdbcTemplate) : NavFxHistorySource {

    override fun navFxSeries(userId: UUID, from: LocalDate, to: LocalDate): List<NavFxPoint> {
        val navByDate = jdbc.query(
            """SELECT date, nav FROM performance_daily
               WHERE portfolio_id = ? AND date BETWEEN ? AND ?""",
            { rs, _ -> rs.getDate("date").toLocalDate() to rs.getBigDecimal("nav") },
            userId, from, to,
        ).toMap()

        val rowsByDate = jdbc.query(
            """SELECT date, currency, value_native, fx_rate FROM nav_currency_daily
               WHERE portfolio_id = ? AND date BETWEEN ? AND ?""",
            { rs, _ ->
                rs.getDate("date").toLocalDate() to CurrencyRow(
                    rs.getString("currency"),
                    rs.getBigDecimal("value_native"),
                    rs.getBigDecimal("fx_rate"),
                )
            },
            userId, from, to,
        ).groupBy({ it.first }, { it.second })

        return assemble(navByDate, rowsByDate)
    }

    override fun currenciesIn(userId: UUID, from: LocalDate, to: LocalDate): List<String> =
        jdbc.query(
            """SELECT DISTINCT currency FROM nav_currency_daily
               WHERE portfolio_id = ? AND date BETWEEN ? AND ?""",
            { rs, _ -> rs.getString("currency") },
            userId, from, to,
        )

    companion object {
        /**
         * `navAtPriorFx = nav + Σ_c v_c(당일)·(r_c(전일) − r_c(당일))`
         *
         * **`Σ v_c·r_c(전일)`로 직접 구하지 말 것.** toKrw가 자산별 가격을 원 단위로
         * 반올림한 뒤 수량을 곱하므로 `Σ v_c·r_c`는 performance_daily.nav와 정확히
         * 같지 않다. 직접 구하면 환율이 하나도 안 움직인 날에도 환율 기여가 0이 아니게
         * 되고, 250구간을 곱하면 눈에 보일 만큼 쌓인다. 권위 있는 nav에 환율 차이만
         * 얹으면 그 항이 상쇄된다.
         *
         * **날짜를 빼지 않는다.** `performance_daily`의 모든 날에 점을 만들고, 통화 행이
         * 없는 날은 `navAtPriorFx = null`로 둔다. 빼면 `attribute()`가 `calculate()`와
         * 다른 구간 집합을 돌게 되어 항등식이 입력 단계에서 깨지고, 입출금이 없으면
         * 체인링킹이 접혀 안 보인다 — **입금 있는 계정에서만 틀린다.**
         */
        fun assemble(
            navByDate: Map<LocalDate, BigDecimal>,
            rowsByDate: Map<LocalDate, List<CurrencyRow>>,
        ): List<NavFxPoint> {
            val dates = navByDate.keys.sorted()
            return dates.mapIndexed { idx, date ->
                val nav = navByDate.getValue(date)
                if (idx == 0) return@mapIndexed NavFxPoint(date, nav, null)

                val rows = rowsByDate[date]
                val priorRows = rowsByDate[dates[idx - 1]]
                if (rows == null || priorRows == null) return@mapIndexed NavFxPoint(date, nav, null)

                // 전일에 없던 통화는 당일 환율을 쓴다 — 차이가 0이 되어 환율 기여가 없다.
                // 전일에 보유가 없었으므로 그게 맞다.
                val priorRates = priorRows.associate { it.currency to it.fxRate }
                val delta = rows.fold(BigDecimal.ZERO) { acc, row ->
                    val prior = priorRates[row.currency] ?: row.fxRate
                    acc + row.valueNative * (prior - row.fxRate)
                }
                NavFxPoint(date, nav, nav + delta)
            }
        }
    }
}
