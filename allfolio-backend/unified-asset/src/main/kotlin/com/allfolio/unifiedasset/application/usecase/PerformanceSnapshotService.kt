package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.CurrencyValue
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.NavCurrencyStore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * NAV 스냅샷을 performance_daily에, 통화별 내역을 nav_currency_daily에 기록한다.
 *
 * 호출자는 넷이고, sync 완료는 그중 하나다:
 * - 마감 워크플로우 S030 (NavSnapshotAction → DailyNavScheduler) — 워크플로우가 정한 일자
 * - 계좌 sync 성공 직후 (SyncAccountUseCase)
 * - 수동 자산 등록 직후 (AccountController.createAsset)
 * - CSV 임포트 직후 (AccountController.importCsv)
 *
 * **넷 전부가 통화 내역까지 남겨야 한다 (AF-106).** 읽기 쪽(`JdbcNavFxHistorySource`)은
 * 어느 하루라도 통화 행이 없으면 그 구간의 분해를 통째로 포기한다. 계좌 sync 경로는 오늘
 * 날짜로 쓰고 화면 프리셋은 전부 오늘로 끝나므로, 빠진 호출자가 하나라도 있으면 기여도
 * 블록이 영원히 안 뜬다.
 */
@Service
class PerformanceSnapshotService(
    private val jdbc: JdbcTemplate,
    private val fx: FxConverter,
    private val navCurrencyStore: NavCurrencyStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * NAV 스냅샷을 performance_daily에, 통화별 내역을 nav_currency_daily에 기록한다.
     *
     * **총액이 아니라 통화별 내역을 받는다 (AF-106).** 타입이 호출자에게 통화 내역을
     * 강제하므로 새 호출자가 생겨도 빠뜨릴 수 없다. 총액은 여기서 계산하므로 두 테이블이
     * 같은 숫자에서 나온다.
     *
     * **둘은 한 트랜잭션이다 — try/catch로 감싸지 말 것.** 호출자 일부가 @Transactional
     * 안이고, Postgres는 트랜잭션 안 SQL 오류 뒤 그 트랜잭션을 abort 상태로 만들어서
     * 예외를 잡아도 커밋이 실패한다. 그리고 NAV만 있고 통화 내역이 없는 상태가 정확히
     * AF-106이 분해를 포기하는 상태라, 둘 다 없는 편이 깨끗하다.
     *
     * **[date]에 기본값을 두지 않는다.** `LocalDate.now()`를 기본 인자로 두면 호출자가
     * 빠뜨렸을 때 조용히 UTC 날짜로 돌아가는데, 컨테이너가 UTC라 자정 KST 실행이 전날에
     * 앉는다. 증상이 "하루 밀림"이라 눈에 안 띄고, wf_job_log.ymd와 영원히 어긋난다.
     * 호출자 넷이 각자 무슨 날짜인지 알고 있으므로 전부 명시적으로 넘긴다.
     *
     * tenant_id = portfolio_id = userId (unified-asset은 사용자=포트폴리오 단위)
     */
    fun record(userId: UUID, navByCurrency: Map<String, BigDecimal>, date: LocalDate) {
        val values = navByCurrency.map { (currency, valueNative) ->
            CurrencyValue(currency, valueNative, fx.rateOf(currency))
        }
        // 통화별로 한 번씩 환산해 합산한다 — Σ(v × rateOf)를 쓰지 않는 이유는 toKrw가
        // 통화마다 원 단위로 반올림하기 때문이다. 그쪽이 기존 저장값과 이어진다.
        val nav = navByCurrency.entries.fold(BigDecimal.ZERO) { acc, (currency, value) ->
            acc + fx.toKrw(value, currency)
        }

        // 전일 NAV 조회 (daily_return 계산용)
        val prevNav: BigDecimal? = jdbc.query(
            """SELECT nav FROM performance_daily
               WHERE portfolio_id = ? AND date < ?
               ORDER BY date DESC LIMIT 1""",
            { rs, _ -> rs.getBigDecimal("nav") },
            userId, date,
        ).firstOrNull()

        // 최초 NAV 조회 (cumulative_return 계산용)
        val firstNav: BigDecimal? = jdbc.query(
            """SELECT nav FROM performance_daily
               WHERE portfolio_id = ?
               ORDER BY date ASC LIMIT 1""",
            { rs, _ -> rs.getBigDecimal("nav") },
            userId,
        ).firstOrNull()

        val dailyReturn = if (prevNav != null && prevNav > BigDecimal.ZERO)
            nav.subtract(prevNav).divide(prevNav, 6, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        val cumulativeReturn = if (firstNav != null && firstNav > BigDecimal.ZERO)
            nav.subtract(firstNav).divide(firstNav, 6, RoundingMode.HALF_UP)
        else BigDecimal.ZERO

        // UPSERT: 같은 날 sync를 여러 번 해도 덮어씀
        jdbc.update(
            """INSERT INTO performance_daily
                   (tenant_id, portfolio_id, date, nav, daily_return, cumulative_return, created_at)
               VALUES (?, ?, ?, ?, ?, ?, NOW())
               ON CONFLICT (tenant_id, portfolio_id, date)
               DO UPDATE SET
                   nav               = EXCLUDED.nav,
                   daily_return      = EXCLUDED.daily_return,
                   cumulative_return  = EXCLUDED.cumulative_return""",
            userId, userId, date, nav, dailyReturn, cumulativeReturn,
        )
        log.info("Performance snapshot recorded: userId=$userId date=$date nav=$nav daily=${dailyReturn.setScale(4, RoundingMode.HALF_UP)} cum=${cumulativeReturn.setScale(4, RoundingMode.HALF_UP)}")

        // NAV 행을 먼저 쓰고 통화 행을 쓴다. 같은 트랜잭션이라 밖에서 순서가 보이진 않지만,
        // 로그가 이 순서로 읽히고 나중 독자가 "아직 안 찍힌 NAV를 참조하는 통화 행"을 보면 안 된다.
        navCurrencyStore.replace(userId, date, values)
    }
}
