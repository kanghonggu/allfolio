package com.allfolio.fx

import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.KrwConversion
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * unified-asset의 [FxConverter] 포트를 backend-app FX 인프라로 연결하는 어댑터.
 *
 * - [toKrw]   현재 환율 (Redis 캐시) — 자산 평가액용
 * - [toKrwOn] 지정일 환율 (fx_rate_daily) — 현금흐름용
 *
 * 폴백 정책을 여기 한 곳에 모아 둔다. 소비 지점이 "과거 없으면 현재로" 규칙을
 * 각자 구현하면 같은 로직이 복제되고 넷째 소비자가 생길 때 또 복제된다.
 */
@Component
class UnifiedAssetFxConverterAdapter(
    private val currencyConverter: CurrencyConverter,
    private val historicalRates: HistoricalFxRateJpaRepository,
) : FxConverter {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 확정된 과거 환율은 변하지 않으므로 무기한 캐싱해도 안전하다.
     * 거래 수백 건짜리 sync에서 날짜별 조회가 반복되는 것을 막는 용도이고,
     * 프로세스 재시작 시 비워져도 무방하다.
     *
     * "결과가 아닌 것"은 절대 기억하지 않는다 — 요청한 날짜의 행을 정확히 찾았을 때만 넣는다.
     * 조회 실패(예외)나 행 없음을 캐시하면 잠깐 끊긴 커넥션 하나가 그 날짜를 프로세스 수명 내내
     * 현재환율 폴백으로 고정시키고, 나중에 백필로 들어온 행도 영영 못 보게 된다.
     * 직전 영업일로 해소된 결과도 넣지 않는다 — 요청 날짜 키에 옛 base_date 값이 박히면
     * 그 날짜의 행이 나중에 들어와도 계속 옛 값을 확정치인 양 내놓는다.
     */
    private val cache = ConcurrentHashMap<String, ResolvedRate>()

    private data class ResolvedRate(val rateKrw: BigDecimal, val rateDate: LocalDate)

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")

        /**
         * 과거 시계열을 가진 통화. ECOS로 채울 수 있는 것만 여기 들어간다.
         *
         * **`ecos.series`(application.yml) 설정 맵과 손으로 맞춰야 하는 두 번째 진실 공급원이다.**
         * 백필은 `ecos.series`에 있는 통화를 채우지만 조회는 이 집합만 본다. JPY 시계열을 설정에
         * 추가해 백필하면 `fx_rate_daily`에 행은 쌓이는데 여기 없어서 영영 안 읽히고,
         * 아래 `log.error("지원하지 않는 통화")`로 빠져 현재 환율 폴백이 된다 —
         * 설정을 바꿨는데 아무 일도 안 일어나는, 원인이 가장 안 보이는 형태다.
         * 통화를 늘릴 때는 반드시 양쪽을 함께 고칠 것.
         * (지금은 `Currencies.SUPPORTED`에 USD 외 법정통화가 없어 무해하다.
         *  코드로 결합하지 않는 이유는 조회 가능 통화와 백필 가능 통화가 개념상 별개이기 때문이다 —
         *  설정에 넣기 전에 미리 코드를 넣어 두는 것도, 그 반대도 정당하다.)
         */
        private val HISTORICAL = setOf("USD")

        /** 과거 시세 소스가 없어 현재가로만 환산되는 통화 */
        private val CRYPTO = setOf("BTC", "ETH")
    }

    /**
     * 현재 환율 경로 — 자산 평가액용. **[canonical]을 거치지 않는다.**
     *
     * 여기서 `USDT → USD`로 접으면 [CurrencyConverter]에 닿기도 전에 USD가 되어
     * AF-99의 USD·USDT 분리가 통째로 무효가 된다. 거래소에 실제 USDT를 들고 있는 계정이
     * 공식 고시로 환산되는, 정확히 그 분리가 막으려던 상황이다.
     *
     * trim·uppercase는 남긴다. `Account.reconstruct`는 DB 값을 재정규화 없이 되살리므로
     * `Currencies.normalize`를 우회한 코드가 그대로 도달하는데, 정규화 없이 넘기면
     * `" usdt "`가 어느 갈래에도 안 맞아 1:1로 떨어지고 100 USDT가 100원이 된다.
     *
     * **[toKrwOn]과 규칙이 다른 것은 의도다** — 저쪽 KDoc을 읽고 나서 통일할 것.
     */
    override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
        currencyConverter.toKrw(amount, currency.trim().uppercase())

    /**
     * 지정일 환율 경로 — 현금흐름용. [canonical]을 거쳐 USDT를 USD 시계열로 근사한다.
     *
     * ECOS는 법정통화만 주므로 **과거 USDT 시계열은 존재하지 않는다.** 접지 않으면
     * `HISTORICAL`에 없는 통화로 떨어져 현재 환율 폴백이 되는데, 그건 체결일 환율을
     * 쓰겠다는 이 메서드의 존재 이유를 무너뜨린다. USD로 근사하는 편이 낫다.
     *
     * 즉 **두 경로가 의도적으로 다른 규칙을 쓴다** — 현재가는 USDT를 별개 자산으로,
     * 과거는 USD로 근사. 불일치로 보고 통일하지 말 것.
     */
    override fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate): KrwConversion {
        val code = canonical(currency)

        if (code == "KRW") return KrwConversion(amount, rateDate = null, estimated = false)

        // BTC/ETH는 과거 시세를 가진 소스가 없다 — 현행 현재가 환산을 유지한다
        if (code in CRYPTO) return estimatedNow(amount, code)

        if (code !in HISTORICAL) {
            log.error("[Fx] 지원하지 않는 통화 — 환산 없이 그대로 둔다 currency={} date={}", currency, date)
            return estimatedNow(amount, code)
        }

        val resolved = lookup(code, date)
            ?: return estimatedNow(amount, code).also {
                log.warn("[Fx] 과거 환율 없음 — 현재 환율로 환산 currency={} date={}", code, date)
            }

        return KrwConversion(
            amountKrw = (amount * resolved.rateKrw).setScale(0, RoundingMode.HALF_UP),
            rateDate = resolved.rateDate,
            estimated = false,
        )
    }

    /**
     * 캐시를 통째로 비운다. 백필이 **같은 날짜의 값을 정정**했을 때 [FxRateBackfillService]가 호출한다 —
     * 확정치라 무기한 캐싱하지만 "확정"은 ECOS가 준 값 기준이고, 그 값이 바뀌면 캐시는 낡은 값이 된다.
     *
     * 통화별로 가려 비우지 않는 이유: 캐시는 정확히 맞은 날짜만 담고(직전 영업일 해소분·미스는 안 담는다)
     * 백필은 어드민이 수동으로 한 번씩 돌리는 경로라, 다 비우고 다시 채우는 비용이 선별 로직보다 싸다.
     *
     * **이 캐시는 프로세스 안에만 있다 — 단일 인스턴스 전제다.**
     * `render.yaml`이 `plan: free`라 지금은 인스턴스가 하나뿐이라 맞지만, 스케일아웃하면
     * 백필 요청을 받지 않은 인스턴스는 정정 전 환율을 무기한 캐싱한 채로 남는다.
     * 증상이 없어서(오류도, 로그도 안 난다) 값이 틀린 줄도 모른다 —
     * 다중 인스턴스로 가는 날에는 캐시 무효화를 인스턴스 간에 전파하거나(Redis pub/sub 등)
     * 이 캐시에 TTL을 붙여야 한다.
     */
    fun invalidate() {
        val size = cache.size
        cache.clear()
        log.info("[Fx] 과거 환율 캐시 무효화 entries={}", size)
    }

    /**
     * 반드시 [canonical]을 거친 코드를 넘긴다. [CurrencyConverter]는 uppercase만 하고 trim을 안 해서
     * " btc " 같은 원본 값을 넘기면 어느 갈래에도 안 맞고 1:1로 떨어진다 — 0.5 BTC가 0.5원이 된다.
     */
    private fun estimatedNow(amount: BigDecimal, code: String) =
        KrwConversion(currencyConverter.toKrw(amount, code), rateDate = null, estimated = true)

    /**
     * 과거 시계열 조회용 통화 코드. USDT는 USD 시계열로 근사한다.
     *
     * **[toKrwOn]에서만 쓴다.** 현재 환율 경로가 이걸 거치면 AF-99의 분리가 무효가 된다.
     *
     * 화이트리스트 검증을 겸하는 `Currencies.normalize`와 달리 여기서는 별칭 치환만 한다.
     */
    private fun canonical(currency: String): String =
        when (val code = currency.trim().uppercase()) {
            "USDT" -> "USD"
            else -> code
        }

    private fun lookup(code: String, date: LocalDate): ResolvedRate? {
        // 오늘 이후는 아직 확정 전이라 캐시에 넣지 않는다
        if (!date.isBefore(LocalDate.now(KST))) return query(code, date)

        val key = "$code@$date"
        cache[key]?.let { return it }

        // computeIfAbsent 밖에서 조회한다 — 맵 락을 쥔 채로 DB I/O를 하지 않는다
        val resolved = query(code, date) ?: return null
        if (resolved.rateDate == date) cache[key] = resolved
        return resolved
    }

    /**
     * 조회 실패를 삼키고 null을 돌려준다 — 호출자는 현재 환율 폴백으로 떨어진다.
     * 환율 테이블 하나 때문에 동기화·현금흐름 기록 전체가 실패하는 것보다 낫다는 판단이다.
     *
     * **이 폴백은 활성 트랜잭션 밖에서만 성립한다 — 중요한 한계다.**
     * JPA 스펙상 쿼리에서 난 `PersistenceException`은 현재 트랜잭션을 rollback-only로 표시한다.
     * 여기서 예외를 잡아도 그 표시는 지워지지 않아서, 실행은 태연히 이어지다가 커밋 시점에
     * `UnexpectedRollbackException`으로 죽는다. 즉 트랜잭션 안에서는 "우아한 폴백"이 아니라
     * 지연된 500이다.
     *
     * 소비 지점 셋 중 `RecordCashFlowUseCase`는 비트랜잭션이라 의도대로 동작하지만,
     * `SyncAccountUseCase.execute`와 `RecordInternalFlowUseCase`의 두 메서드는 `@Transactional`이라
     * 해당하지 않는다. 마이그레이션을 건너뛰고 배포했거나(테이블 없음) Neon 커넥션이 순간 끊기면
     * 이 경로들은 폴백이 아니라 500이 된다.
     *
     * `Propagation.REQUIRES_NEW`로 격리하면 닫히지만 조회마다 트랜잭션을 여는 비용이 붙는다.
     * 마이그레이션 선행이 이미 배포 필수 절차라(테이블은 있다) 남은 위험은 순간적인 커넥션 장애뿐이고,
     * 그 대가로 sync 한 번에 트랜잭션 수백 개를 여는 건 지금 균형이 맞지 않는다고 판단했다.
     */
    private fun query(code: String, date: LocalDate): ResolvedRate? =
        runCatching {
            historicalRates
                .findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(code, date)
                ?.let { ResolvedRate(it.rateKrw, it.baseDate) }
        }.getOrElse { e ->
            log.error("[Fx] 과거 환율 조회 실패 currency={} date={}: {}", code, date, e.message)
            null
        }
}
