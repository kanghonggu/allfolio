package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.CurrencyValue
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.NavCurrencyStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * PerformanceSnapshotService 테스트 공용 fake들.
 *
 * `PerformanceSnapshotDateTest`(날짜 계약)와 `PerformanceSnapshotCurrencyTest`(두 테이블
 * 계약)가 같은 가로채기 방식을 쓰므로 한 벌만 둔다 — 두 벌이면 한쪽만 고쳐진다.
 */

/** 실행 SQL과 바인딩 인자를 그대로 모아두는 fake. DataSource 없이 동작한다(super 호출 없음). */
class CapturingJdbcTemplate : JdbcTemplate() {
    val updates = mutableListOf<Pair<String, List<Any?>>>()
    val queries = mutableListOf<Pair<String, List<Any?>>>()

    override fun update(sql: String, vararg args: Any?): Int {
        updates += sql to args.toList()
        return 1
    }

    // 이전 NAV·최초 NAV 조회는 빈 결과로 — daily/cumulative가 0이 되어 산술이 끼어들지 않는다
    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        queries += sql to args.toList()
        return emptyList()
    }

    /** INSERT에 바인딩된 nav — (tenant_id, portfolio_id, date, nav, …) */
    fun insertedNav(): BigDecimal = updates.single().second[3] as BigDecimal

    /** INSERT에 바인딩된 date */
    fun insertedDate(): Any? = updates.single().second[2]
}

/**
 * KRW 1:1, USD 1300.5, 그 외(JPY 등)는 미지원이라 1 — 실제 CurrencyConverter의 동작이다.
 *
 * USD 환율에 소수를 둔 이유: `toKrw`는 원 단위로 반올림하고 `rateOf`는 반올림 전 환율을
 * 돌려준다. 둘을 정수로 맞춰 두면 합계 불변식 테스트가 반올림 차이를 아예 못 본다.
 */
class StubFxConverter : FxConverter {
    override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
        amount.multiply(rateOf(currency)).setScale(0, RoundingMode.HALF_UP)

    override fun rateOf(currency: String): BigDecimal = when (currency.trim().uppercase()) {
        "USD" -> BigDecimal("1300.5")
        else -> BigDecimal.ONE   // KRW와 미지원 통화
    }
}

/** replace() 호출을 그대로 모아두는 fake. */
class RecordingNavCurrencyStore : NavCurrencyStore {
    val calls = mutableListOf<Triple<UUID, LocalDate, List<CurrencyValue>>>()
    override fun replace(portfolioId: UUID, date: LocalDate, values: List<CurrencyValue>) {
        calls += Triple(portfolioId, date, values)
    }
}

/**
 * replace()가 항상 실패하는 fake — 통화 행 쓰기 실패 시 record()가 예외를 삼키지 않고
 * 그대로 전파하는지 [PerformanceSnapshotTransactionTest]가 못 박는 데 쓴다.
 */
class ThrowingNavCurrencyStore : NavCurrencyStore {
    override fun replace(portfolioId: UUID, date: LocalDate, values: List<CurrencyValue>) {
        throw RuntimeException("nav_currency_daily 쓰기 실패 (시뮬레이션)")
    }
}

fun snapshotService(
    jdbc: JdbcTemplate,
    store: NavCurrencyStore = RecordingNavCurrencyStore(),
    fx: FxConverter = StubFxConverter(),
) = PerformanceSnapshotService(jdbc, fx, store)
