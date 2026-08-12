package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference

/**
 * 공식 원/미국달러 매매기준율을 하나은행 고시에서 읽는다 (AF-99).
 *
 * `getUsdToKrw()`만 오버라이드하고 나머지는 위임한다.
 * 고시가 없거나 조회가 실패하면 default 동작(USDT 환율 근사)으로 떨어지므로,
 * **수집을 한 번도 안 돌린 상태에서도 오늘과 똑같이 굴러간다.**
 * (테이블 자체가 없을 때는 얘기가 다르다 — [getUsdToKrw]의 트랜잭션 주석을 볼 것.)
 *
 * 신선도 제한을 두지 않는다. 주말이면 금요일 최종고시를 쓰게 되는데 그게 실제 시장과 맞는다 —
 * 주말엔 환전도 그 값으로 된다. "N시간 넘으면 폴백" 같은 규칙을 두면 연휴에 정상인데도
 * 폴백이 돌아 환율이 튄다.
 *
 * 위임 대상을 구체 타입([RedisFxRateService])이 아니라 [FxRateService]로 받는다.
 * 이 클래스가 `@Primary`라 같은 인터페이스를 주입받아도 순환하지 않는다 —
 * Spring은 자기 자신을 후보에서 먼저 빼고(self-reference는 최후 폴백), 남는
 * 유일한 후보인 [RedisFxRateService]를 넣는다. 인터페이스로 받으면 테스트가
 * Redis 없이 스텁만으로 돌아간다.
 */
@Service
@Primary
class HanaFxRateService(
    private val delegate: FxRateService,
    private val quotes: HanaFxQuoteJpaRepository,
) : FxRateService by delegate {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * NAV 계산 한 번에 toKrw가 수십 번 불린다. 매번 DB를 치면 낭비라 짧게 캐싱한다.
     * Redis를 쓰지 않는 이유: 설계 원칙이 "Postgres가 진실, Redis는 가속"이고
     * 이 경로는 프로세스 내 캐시로 충분하다.
     *
     * **조회 실패는 절대 캐시하지 않는다.** AF-100의 [UnifiedAssetFxConverterAdapter]에서
     * "결과가 아닌 것"을 기억했다가 커넥션이 한 번 끊긴 것 때문에 폴백 값이 프로세스 수명 내내
     * 고정되던 함정이 있었다. 여기서는 성공한 조회만 캐시에 넣으므로, DB가 돌아오면
     * 다음 호출이 곧바로 고시값을 다시 읽는다.
     *
     * **이 캐시는 프로세스 안에만 있다 — 단일 인스턴스 전제다.**
     * `render.yaml`이 `plan: free`라 지금은 인스턴스가 하나뿐이라 맞지만, 스케일아웃하면
     * 인스턴스마다 최대 [TTL_MILLIS]만큼 다른 환율을 쓰게 된다. 오류도 로그도 안 나서
     * 인스턴스에 따라 평가액이 미세하게 갈리는 것 말고는 증상이 없다.
     * TTL이 60초라 AF-100의 무기한 캐시만큼 위험하진 않지만, 다중 인스턴스로 가는 날에는
     * 이 경로도 함께 점검할 것.
     */
    private val cached = AtomicReference<Pair<Long, UsdQuoteRef>?>(null)

    companion object {
        private const val CURRENCY = "USD"
        private const val TTL_MILLIS = 60_000L
    }

    /**
     * **조회 실패 폴백은 활성 트랜잭션 밖에서만 온전히 성립한다 — AF-100의 [UnifiedAssetFxConverterAdapter]와 같은 한계다.**
     * JPA 스펙상 쿼리에서 난 `PersistenceException`은 현재 트랜잭션을 rollback-only로 표시하고,
     * 여기서 예외를 잡아도 그 표시는 안 지워진다. 커밋 시점에 `UnexpectedRollbackException`으로 죽으니
     * 트랜잭션 안에서는 "우아한 폴백"이 아니라 지연된 500이다.
     * 실제 소비 지점인 `GetPortfolioUseCase`(readOnly)와 `GoalService`가 모두 `@Transactional`이라 해당한다.
     *
     * 두 경우로 갈린다:
     * - **테이블 없음** — `hana_fx_quote` 마이그레이션(`docs/superpowers/migrations/2026-08-12-hana-fx-quote.sql`)은
     *   `ddl-auto: none`이라 손으로 돌려야 한다. **마이그레이션보다 먼저 배포하면 포트폴리오 조회가 통째로 500이 된다.**
     *   배포 순서로 막는다 — 여기서 코드로 막지 않는다.
     * - **커넥션 순단** — 그 순간의 요청만 500. AF-100이 받아들인 것과 같은 잔여 위험이다.
     *
     * `Propagation.REQUIRES_NEW`로 격리하면 닫힌다. AF-100에서 이걸 접었던 이유(sync 한 번에 트랜잭션 수백 개)는
     * **여기엔 적용되지 않는다** — 캐시 덕에 이 쿼리는 프로세스당 60초에 한 번이라 추가 트랜잭션도 분당 한 개다.
     * 그럼에도 넣지 않은 건, 트랜잭션 프록시가 끼면 이 빈이 프록시로 감싸여 `@Primary` 자기-주입 해석이
     * 한 겹 더 복잡해지기 때문이고, 위 두 경우 모두 배포 절차로 이미 막히기 때문이다.
     * 다중 인스턴스나 잦은 커넥션 장애를 겪게 되면 이 판단을 다시 볼 것.
     *
     * 수집을 한 번도 안 돌렸을 뿐인 정상 상태(테이블은 있고 행이 없음)는 예외가 아니라 null이라 무관하다.
     */
    override fun getUsdToKrw(): BigDecimal =
        usdQuoteRef()?.rate ?: delegate.getUsdtToKrw()

    /**
     * 고시 한 건을 통째로 돌려준다 (AF-105). 없거나 조회에 실패하면 null.
     *
     * [getUsdToKrw]가 이 위에 올라타 있어 둘이 다른 값을 말할 수 없다.
     * 화면이 "이 환율로 계산했다"고 밝히는 근거가 실제 환산에 쓰인 값과 갈라지면
     * 신뢰를 만들려던 표기가 반대로 동작한다.
     */
    override fun usdQuoteRef(): UsdQuoteRef? {
        val now = System.currentTimeMillis()
        cached.get()?.let { (at, ref) -> if (now - at < TTL_MILLIS) return ref }

        val quote = runCatching {
            quotes.findTopByCurrencyOrderByBaseDateDescRoundNoDesc(CURRENCY)
        }.getOrElse { e ->
            log.error("[하나은행] 고시 조회 실패 — USDT 환율로 근사한다: {}", e.message)
            null
        } ?: return null

        val ref = UsdQuoteRef(
            rate = quote.baseRate,
            baseDate = quote.baseDate,
            roundNo = quote.roundNo,
        )
        cached.set(now to ref)
        return ref
    }
}
