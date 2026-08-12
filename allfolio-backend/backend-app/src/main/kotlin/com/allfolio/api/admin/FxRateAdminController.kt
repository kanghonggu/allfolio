package com.allfolio.api.admin

import com.allfolio.fx.BackfillSummary
import com.allfolio.fx.FxRateBackfillService
import com.allfolio.fx.FxRateService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate

@RestController
@RequestMapping("/api/admin/fx")
class FxRateAdminController(
    private val fxRateService: FxRateService,
    private val backfillService: FxRateBackfillService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** GET /api/admin/fx/usdtkrw — 거래소 USDT 환율 조회 */
    @GetMapping("/usdtkrw")
    fun getUsdtKrw(): ResponseEntity<FxRateResponse> =
        ResponseEntity.ok(FxRateResponse(fxRateService.getUsdtToKrw()))

    /**
     * GET /api/admin/fx/usdkrw — 공식 원/미국달러 매매기준율 조회 (AF-99)
     *
     * `usdtkrw`만 있으면 USD·USDT 분리 후 **자산 평가 경로가 실제로 무슨 값을 쓰는지
     * 확인할 방법이 없다.** 두 응답이 갈리는지가 분리가 살아 있다는 운영 증거다.
     *
     * 하나은행 고시를 아직 수집하지 않았다면 `getUsdToKrw()`의 default가 USDT 환율이라
     * 두 값이 같게 나온다 — 그건 정상이고, 수집을 돌리면 갈린다.
     */
    @GetMapping("/usdkrw")
    fun getUsdKrw(): ResponseEntity<UsdRateResponse> =
        ResponseEntity.ok(UsdRateResponse(fxRateService.getUsdToKrw()))

    /** PUT /api/admin/fx/usdtkrw — 환율 갱신 (어드민 전용) */
    @PutMapping("/usdtkrw")
    fun setUsdtKrw(@RequestBody req: FxRateRequest): ResponseEntity<FxRateResponse> {
        fxRateService.setUsdtToKrw(req.rate)
        return ResponseEntity.ok(FxRateResponse(req.rate))
    }

    /** GET /api/admin/fx/crypto/{symbol} — 코인 KRW 시세 조회 (BTC|ETH, QA P3) */
    @GetMapping("/crypto/{symbol}")
    fun getCryptoKrw(@org.springframework.web.bind.annotation.PathVariable symbol: String): ResponseEntity<CryptoRateResponse> =
        ResponseEntity.ok(CryptoRateResponse(symbol.uppercase(), fxRateService.getCryptoToKrw(symbol)))

    /** PUT /api/admin/fx/crypto/{symbol} — 코인 KRW 시세 갱신 (어드민 전용) */
    @PutMapping("/crypto/{symbol}")
    fun setCryptoKrw(
        @org.springframework.web.bind.annotation.PathVariable symbol: String,
        @RequestBody req: FxRateRequest,
    ): ResponseEntity<CryptoRateResponse> {
        fxRateService.setCryptoToKrw(symbol, req.rate)
        return ResponseEntity.ok(CryptoRateResponse(symbol.uppercase(), req.rate))
    }

    /**
     * POST /api/admin/fx/backfill — ECOS 과거 환율 백필 (어드민 전용, AF-100)
     *
     * 예: POST /api/admin/fx/backfill?currency=USD&from=2020-01-01&to=2026-08-11
     * 멱등하다 — 같은 구간을 다시 돌리면 값만 덮는다.
     *
     * 다년 범위는 나눠 돌릴 것. 행마다 merge SELECT가 나가 한 트랜잭션이 길어진다.
     *
     * `currency`에 기본값을 두지 않는다 — 파라미터 이름을 오타내면(`currncy=JPY`) 기본값이
     * 조용히 USD 전 구간 백필을 돌린다. 손상은 없지만 수십 초짜리 엉뚱한 작업이 소리 없이 실행된다.
     * 백필을 돌리는 운영자가 통화를 모를 리 없어 기본값으로 얻는 것도 없다.
     *
     * 아래 두 예외만 여기서 갈아끼우는 이유는 **범위** 때문이다.
     * [DataIntegrityViolationException]은 전역에서 422로 매핑돼 있는데, 그걸 409로 바꾸면
     * 모든 다른 엔드포인트의 계약이 함께 바뀐다. [IllegalStateException]도 마찬가지로
     * 전역에서 502로 돌리면 순수한 내부 버그까지 "외부 API 탓"으로 위장된다.
     * 여기서는 둘 다 백필 고유의 의미가 있어(각각 경합·ECOS 응답 이상) 이 엔드포인트에만 가둔다.
     * `EcosApiException`은 code 필드를 응답에 실어야 해서 [ResponseStatusException]으로는
     * 옮길 수 없고, 이미 백필 전용 예외라 전역 핸들러에 둬도 범위가 새지 않는다.
     */
    @PostMapping("/backfill")
    fun backfill(
        @RequestParam currency: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<BackfillSummary> =
        try {
            ResponseEntity.ok(backfillService.backfill(currency, from, to))
        } catch (e: IllegalStateException) {
            // 0건 응답·범위 밖 행만 온 경우. 요청은 멀쩡했고 상류가 이상한 것이므로 502다.
            // 전역 폴백에 맡기면 500 + "서버 오류가 발생했습니다"로 뭉개져,
            // 통계표 코드를 고쳐야 하는지 재실행하면 되는지 운영자가 판단할 근거가 사라진다.
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message ?: "ECOS 응답을 신뢰할 수 없어 중단했습니다.")
        } catch (e: DataIntegrityViolationException) {
            // uk_fx_rate_daily 위반. 두 어드민이 겹치는 구간을 동시에 돌리면 난다.
            // 입력 잘못이 아니라 일시적 경합이라 422(전역 기본)가 아니라 409로 재실행을 유도한다.
            // 여기서 가로채면 전역 핸들러의 log.error("Data integrity violation", e)를 지나치게 되므로
            // 그 진단을 대신 남긴다 — 제약 이름이 있어야 "정말 uk_fx_rate_daily였나"를 확인할 수 있다.
            // 다만 ERROR가 아니라 WARN이다: 응답이 재실행을 안내하는 일시적 경합인데
            // ERROR는 보통 알림을 울려서, 사람을 깨울 일이 아닌 것으로 사람을 깨우게 된다.
            log.warn("[ECOS] 백필 제약 위반 currency={} {}~{}", currency, from, to, e)
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "환율 저장이 다른 백필과 충돌했습니다. 같은 구간을 다시 실행해주세요.",
            )
        }
}

data class FxRateRequest(val rate: BigDecimal)
data class FxRateResponse(val usdtKrw: BigDecimal)

/** 필드 이름을 `usdtKrw`와 나눠 둔다 — 응답만 보고 어느 환율인지 알 수 있어야 한다 */
data class UsdRateResponse(val usdKrw: BigDecimal)
data class CryptoRateResponse(val symbol: String, val krw: BigDecimal)
