package com.allfolio.api.scheduler

import com.allfolio.api.admin.BenchmarkIndexAdminController
import com.allfolio.api.admin.CommodityAdminController
import com.allfolio.api.admin.DartAdminController
import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.api.admin.MarketIndexAdminController
import com.allfolio.api.admin.MarketRateAdminController
import com.allfolio.api.admin.RealAssetValuationAdminController
import com.allfolio.api.admin.WatchValuationAdminController
import com.allfolio.dart.DartRunResult
import com.allfolio.dart.corp.CorpMapSummary
import com.allfolio.fx.BackfillSummary
import com.allfolio.fx.hana.HanaCollectSummary
import com.allfolio.market.benchmark.BenchmarkCollectSummary
import com.allfolio.market.commodity.CommodityCollectSummary
import com.allfolio.market.index.DomesticIndexCollectSummary
import com.allfolio.market.index.IndexSlot
import com.allfolio.market.index.OverseasIndexCollectSummary
import com.allfolio.market.index.OverseasSchedule
import com.allfolio.market.rate.RateCollectSummary
import com.allfolio.realasset.RealAssetValuationSummary
import com.allfolio.realasset.watch.WatchValuationCollectSummary
import com.allfolio.workflow.application.WfRunSummary
import com.allfolio.workflow.application.WfStepExecutor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import com.allfolio.api.admin.RtmsCollectAdminController
import com.allfolio.market.realestate.RtmsCollectSummary
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 외부 스케줄러(GitHub Actions) 전용 트리거 (AF-103).
 *
 * Render 무료 플랜에는 크론 잡이 없고, 무료 웹 서비스는 15분 유휴 시 잠들어
 * 인스턴스 안의 `@Scheduled`만으로는 주기 실행이 성립하지 않는다.
 * 외부에서 깨워야 하므로, 그 신호를 곧 트리거로 쓴다.
 *
 * **어드민 JWT를 안 쓰는 이유**: 15분 만료라 CI가 들고 있을 수 없다.
 * CI가 매번 로그인하게 하면 어드민 비밀번호가 시크릿에 들어가고, 유출 시 전권이 넘어간다.
 * 수집 트리거만 가능한 토큰은 유출돼도 할 수 있는 일이 "멱등한 수집을 여러 번 도는 것"뿐이다.
 *
 * 이 경로는 SecurityConfig에서 permitAll이다 — 인증은 여기서 한다.
 */
@RestController
@RequestMapping("/api/internal/scheduler")
class SchedulerTriggerController(
    private val fxAdmin: FxRateAdminController,
    private val indexAdmin: MarketIndexAdminController,
    private val rateAdmin: MarketRateAdminController,
    private val commodityAdmin: CommodityAdminController,
    private val rtmsAdmin: RtmsCollectAdminController,
    private val benchmarkAdmin: BenchmarkIndexAdminController,
    private val dartAdmin: DartAdminController,
    private val realAssetAdmin: RealAssetValuationAdminController,
    private val watchAdmin: WatchValuationAdminController,
    private val stepExecutor: WfStepExecutor,
    @Value("\${scheduler.trigger-token:}") private val configuredToken: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 토큰은 Render 대시보드와 GitHub 시크릿 사이를 손으로 옮긴다. 한쪽에 개행이나 공백이
    // 딸려 들어가면 401 + "토큰 불일치"가 나는데, 진짜 틀린 토큰과 구분이 안 되는 배포 함정이다.
    private val expectedToken: ByteArray = configuredToken.trim().toByteArray(StandardCharsets.UTF_8)

    /**
     * POST /api/internal/scheduler/fx/hana-collect — 하나은행 고시환율 수집 트리거
     *
     * **`force`를 노출하지 않는다.** 스케줄 실행은 항상 `force = false`여야 한다.
     * AF-99의 2% 급변동 가드가 걸리면 422가 나가고 워크플로 잡이 실패하는데, 그게 의도한 동작이다 —
     * 진짜 크게 움직인 날은 사람이 값을 보고 판단해야 하고 Actions의 실패 표시가 그 신호다.
     * 스케줄러가 조용히 force로 뚫으면 파싱 오류로 튄 값이 그대로 저장된다.
     *
     * **날짜도 노출하지 않는다.** [FxRateAdminController.collectHana]가 null을 KST 오늘로 해석한다.
     * Render 컨테이너는 UTC라 이 기본값 처리가 없으면 09:10 KST 실행이 "어제"를 조회한다.
     *
     * 어드민 컨트롤러에 위임하는 이유는 그쪽의 예외→상태 매핑(422 안전장치 / 502 은행 응답 이상 /
     * 409 경합)이 Actions 로그를 읽는 사람에게 그대로 필요해서다. 복제하면 두 벌이 갈라지고,
     * 공용 헬퍼로 뽑으면 "이 엔드포인트에서만 이렇게 하는 이유"를 적은 주석들이 근거를 잃는다.
     * 컨트롤러가 컨트롤러를 주입받는 게 낯설다는 건 알지만 대안 둘 다 이보다 나쁘다.
     * **이 위임을 "정리"하지 말 것.**
     */
    @PostMapping("/fx/hana-collect")
    fun collectHanaFx(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<HanaCollectSummary> {
        authorize(token)
        return fxAdmin.collectHana(null, false)
    }

    /**
     * POST /api/internal/scheduler/index/domestic?slot=CLOSE — 국내 지수 수집 트리거 (AF-101)
     *
     * **`slot`은 워크플로가 반드시 실어 보낸다 — 기본값을 두지 않는다.** 지수는 하루 세 지점을
     * 같은 엔드포인트로 찍고, 어느 지점인지는 오직 이 값으로만 구분된다. 기본값을 두면 cron 한 줄이
     * 슬롯을 빠뜨렸을 때 세 실행이 전부 한 슬롯을 덮어쓰는데, 저장된 값 자체는 그럴듯해서
     * 나중에 차트가 이상해질 때까지 아무도 눈치채지 못한다. 400으로 즉시 터지는 편이 낫다.
     *
     * 시각을 노출하지 않는 이유는 하나은행 트리거와 같다 —
     * [MarketIndexAdminController.collect]가 UTC 현재 시각을 넣어주고, 그 변환은 한 곳에만 있어야 한다.
     *
     * 어드민 컨트롤러에 위임하는 이유도 같다: [KisIndexException] → 502 매핑이 Actions 로그를
     * 읽는 사람에게 그대로 필요하다. **이 위임을 "정리"하지 말 것** — 위 [collectHanaFx]의 설명 참조.
     */
    @PostMapping("/index/domestic")
    fun collectDomesticIndex(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
        @RequestParam slot: IndexSlot,
    ): ResponseEntity<DomesticIndexCollectSummary> {
        authorize(token)
        return indexAdmin.collect(slot)
    }

    /**
     * POST /api/internal/scheduler/index/overseas?schedule=US — 해외 지수 수집 트리거 (AF-110)
     *
     * 국내 트리거([collectDomesticIndex])와 같은 구조다. 기본값을 두지 않는 것, 시각을 노출하지
     * 않는 것, 어드민에 위임하는 것 모두 근거가 그쪽 KDoc에 있다 —
     * **이 위임도 "정리"하지 말 것**([collectHanaFx] 참조).
     *
     * 해외에서만 다른 것: `schedule`은 하루 중 지점이 아니라 **어느 시장군**이다. 기본값을 두면
     * cron 한 줄이 값을 빠뜨렸을 때 국내처럼 "엉뚱한 슬롯을 덮어쓰는" 게 아니라 **한쪽 시장군이
     * 통째로 수집되지 않는다.** 빠진 아시아 3종은 실패가 아니라 "없는 데이터"로 보여서
     * `failures`에도 안 남고 잡도 초록으로 끝난다. 400이 훨씬 낫다.
     *
     * 타입이 [OverseasSchedule]인 이유(오타를 500이 아니라 400으로 만든다)는 그 enum의 KDoc에 있다.
     */
    @PostMapping("/index/overseas")
    fun collectOverseasIndex(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
        @RequestParam schedule: OverseasSchedule,
    ): ResponseEntity<OverseasIndexCollectSummary> {
        authorize(token)
        return indexAdmin.collectOverseas(schedule)
    }

    /**
     * POST /api/internal/scheduler/fx/backfill?currency=USD&from=2020-01-01&to=2026-08-12
     * — ECOS 과거 환율 백필 트리거 (AF-100)
     *
     * **이 토큰의 권한을 넓히는 게 왜 괜찮은가.** 백필이 하는 일은 ECOS가 준 값으로
     * `fx_rate_daily`를 쓰는 것뿐이고 멱등하다 — 같은 구간을 몇 번 돌려도 같은 행이 남는다.
     * 유출된 토큰으로 할 수 있는 최악이 "이미 있는 환율을 같은 값으로 다시 쓰는 것"이라,
     * 이 토큰에 이미 걸려 있는 수집 트리거들과 폭발 반경이 같다. 그래서 어드민 JWT를
     * CI에 넣는 것보다 이쪽이 낫다(어드민 토큰은 15분 만료라 CI가 들고 있을 수도 없다).
     *
     * **현금흐름 재계산의 `apply` 경로는 여기 붙이지 말 것.** 그건 저장된 금융 이력을
     * 다시 쓴다 — 멱등한 재수집과 달리 되돌릴 수 없고, 잘못 돌면 사용자의 과거 수익률이
     * 통째로 바뀐다. 손상의 종류가 다르므로 어드민 전용으로 남는다. 이 KDoc이 그 경계다.
     *
     * **세 파라미터 모두 기본값을 두지 않는다.** [FxRateAdminController.backfill]이
     * `currency`에 기본값을 안 주는 이유(파라미터 이름 오타가 조용히 USD 전 구간을 돌린다)가
     * 여기서도 그대로다. `from`/`to`도 같다 — 워크플로 입력이 빠진 채 기본 구간이 돌면
     * 운영자가 요청했다고 믿는 구간과 실제로 채워진 구간이 갈라진다. 400으로 죽는 편이 낫다.
     *
     * 어드민 컨트롤러에 위임하는 이유는 위 두 트리거와 같다: 그쪽의 예외→상태 매핑
     * (502 ECOS 응답 이상 / 409 경합 / 400 잘못된 요청)이 Actions 로그와
     * `scripts/fx-backfill.sh`의 구간별 실패 정책에 그대로 필요하다.
     * **이 위임을 "정리"하지 말 것** — [collectHanaFx]의 설명 참조.
     */
    @PostMapping("/fx/backfill")
    fun backfillFx(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
        @RequestParam currency: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<BackfillSummary> {
        authorize(token)
        return fxAdmin.backfill(currency, from, to)
    }

    /**
     * POST /api/internal/scheduler/rate — 금리 수집 트리거 (AF-102)
     *
     * **날짜를 노출하지 않는다.** [MarketRateAdminController.collect]가 null을 KST 오늘 기준
     * 최근 2주로 해석한다. 워크플로가 날짜를 계산해 실어 보내면 러너의 UTC 시계가 그대로
     * 데이터에 새겨지고, GitHub cron이 밀린 날 구간이 어긋난다.
     *
     * 백필 구간을 여기 노출하지 않는 이유: 초기 백필은 사람이 한 번 부르는 일회성 작업이고,
     * 스케줄러가 할 수 있어야 하는 일이 아니다. 어드민 엔드포인트에 있다.
     *
     * 어드민 컨트롤러에 위임하는 이유는 위 트리거들과 같다 — 502(전량 실패)와 500(전 종목 0건 =
     * 통계표·항목 코드가 틀렸다)의 구분이 Actions 로그를 읽는 사람에게 그대로 필요하다.
     * 500에는 원인이 하나 더 있다: `market-rate.ecos`·`market-rate.fred`가 둘 다 비면
     * 요청 대상이 0건이라 역시 500이다.
     * 그 상태는 AF-102가 코드를 채우면서 끝났고 cron도 그때 켰다 — 지금 둘 다 비는 건
     * 설정 사고뿐이다.
     * **이 위임을 "정리"하지 말 것.**
     */
    @PostMapping("/rate")
    fun collectRate(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<RateCollectSummary> {
        authorize(token)
        return rateAdmin.collect(null, null)
    }

    /**
     * POST /api/internal/scheduler/commodity — 원자재 수집 트리거 (AF-108)
     *
     * **날짜를 노출하지 않는다.** [CommodityAdminController.collect]가 끝점을 KST 오늘로 잡고,
     * 시작점은 종목의 주기가 정한다(일간 14일 · 월간 400일). 워크플로가 날짜를 계산해 실어 보내면
     * 러너의 UTC 시계가 그대로 데이터에 새겨지고, GitHub cron이 밀린 날 구간이 어긋난다.
     *
     * **창이 금리(2주 하나)와 달리 둘인 이유는 월간 계열이다** — 관측일이 그 달 1일인데 공표는
     * 실측 76일 뒤였고(2026-08-16 기준) 그 지연은 다음 공표까지 자란다. 근거는
     * [com.allfolio.market.commodity.CommodityCollectService]의 창 상수 KDoc에 있다.
     *
     * 백필 구간을 여기 노출하지 않는 이유는 금리 트리거와 같다 — 초기 백필은 사람이 한 번 부르는
     * 일회성 작업이고, 어드민 엔드포인트에 있다.
     *
     * 어드민 컨트롤러에 위임하는 이유도 같다: 502(전량 실패 — 상류 장애이거나 마이그레이션 미적용)와
     * 500(전 종목 0건 = 시리즈 ID가 틀렸다 / 설정이 비었다)의 구분이 Actions 로그를 읽는 사람에게
     * 그대로 필요하다. **이 위임을 "정리"하지 말 것.**
     */
    @PostMapping("/commodity")
    fun collectCommodity(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<CommodityCollectSummary> {
        authorize(token)
        return commodityAdmin.collect(null, null)
    }

    /**
     * POST /api/internal/scheduler/rtms — 국토부 실거래가 수집 트리거 (A1 v3)
     *
     * **시군구를 파라미터로 받는다.** 다른 트리거들과 달리 대상이 설정이 아니라 요청에 있다 —
     * 보유 부동산이 아직 0건이라 자동 대상 선정을 할 것이 없고, R2 선택 UI에 보여 줄 단지
     * 목록이 있으려면 백필을 먼저 돌릴 수 있어야 한다. 자동 선정은 R3에서 붙인다.
     *
     * **개월 수 기본값이 3인 이유**는 재수집 정책과 같다([RtmsCollectService.FRESH_MONTHS]):
     * 신고 기한이 계약 후 30일이고 해제는 그보다 더 늦게 붙는다. 매일 도는 크론이 그 셋만
     * 다시 받으면 되고, 더 거슬러 올라가는 것은 일회성 백필이라 어드민 엔드포인트에 있다.
     *
     * 어드민 컨트롤러에 위임하는 이유는 위 트리거들과 같다: **502(전량 실패 = 상류 장애)와
     * 400(파라미터 문제 — 법정동 코드 형식·조합 수 초과)의 구분이 Actions 로그를 읽는
     * 사람에게 그대로 필요하다.** 여기 400의 성격이 다른 트리거의 500과 다른데,
     * 대상이 설정이 아니라 요청에 있기 때문이다. **이 위임을 "정리"하지 말 것.**
     */
    @PostMapping("/rtms")
    fun collectRtms(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
        @RequestParam sgg: String,
        @RequestParam(defaultValue = "3") months: Int,
    ): ResponseEntity<RtmsCollectSummary> {
        authorize(token)
        return rtmsAdmin.collect(sgg, months, null)
    }

    /**
     * POST /api/internal/scheduler/benchmark-index — 벤치마크 지수 수집 트리거 (AF-107)
     *
     * **날짜를 노출하지 않는다.** [BenchmarkIndexAdminController.collect]가 끝점을 KST 오늘로 잡고
     * 시작점을 거기서 14일 뺀 날로 잡는다. 워크플로가 날짜를 계산해 실어 보내면 러너의 UTC 시계가
     * 그대로 데이터에 새겨지고, GitHub cron이 밀린 날 구간이 어긋난다.
     *
     * 백필 구간을 여기 노출하지 않는 이유는 금리·원자재 트리거와 같다 — 초기 1년 백필은 사람이
     * 한 번 부르는 일회성 작업이고, 어드민 엔드포인트에 있다.
     *
     * 어드민 컨트롤러에 위임하는 이유도 같다: 502(전량 실패 = 상류 장애)와 500(우리 설정 문제 —
     * 목록이 빔 / 전 지수 0건 / `type`이 `BenchmarkType`에 없음)의 구분이 Actions 로그를 읽는
     * 사람에게 그대로 필요하다. **여기 500의 원인 목록이 원자재와 다르다** — `benchmark_daily`는
     * 이미 있는 표라 마이그레이션 부재가 원인이 될 수 없다.
     * **이 위임을 "정리"하지 말 것** — 위 [collectHanaFx]의 설명 참조.
     */
    @PostMapping("/benchmark-index")
    fun collectBenchmarkIndex(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<BenchmarkCollectSummary> {
        authorize(token)
        return benchmarkAdmin.collect(null, null)
    }

    /**
     * POST /api/internal/scheduler/dart/collect — 공시 수집 트리거
     *
     * 날짜를 노출하지 않는다. [DartAdminController.collect]가 null을 KST 오늘로 해석하고,
     * Render 컨테이너는 UTC라 이 기본값 처리가 없으면 19:00 KST 실행이 "어제"를 조회한다.
     */
    @PostMapping("/dart/collect")
    fun collectDart(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<DartRunResult> {
        authorize(token)
        return dartAdmin.collect(null)
    }

    /** POST /api/internal/scheduler/dart/corp-map — corp_code 매핑 갱신 (주 1회) */
    @PostMapping("/dart/corp-map")
    fun refreshDartCorpMap(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<CorpMapSummary> {
        authorize(token)
        return dartAdmin.refreshCorpMap()
    }

    /**
     * POST /api/internal/scheduler/real-asset/valuate — 실물자산 자동 평가 트리거 (A1)
     *
     * **날짜를 노출하지 않는다.** [RealAssetValuationAdminController.valuate]가 KST 오늘로 잡는다.
     * 워크플로가 날짜를 계산해 실어 보내면 러너의 UTC 시계가 그대로 데이터에 새겨진다.
     *
     * **여기는 "수집"이 아니라 "평가"다 — 다른 트리거들과 성격이 갈리는 지점이 셋 있다.**
     *  1. **상류를 안 부른다.** 우리 DB(`market_commodity_quote`)만 읽으므로 502가 나올 자리가
     *     없다. 전량 실패는 우리 문제(마이그레이션 미적용·코드 오류)이고 500으로 나간다.
     *  2. **평일이 아니라 매일 돈다.** 금 시세가 D+1 공표라 금요일 종가는 토요일에야 올라온다 —
     *     평일에만 돌리면 그 값이 월요일 저녁까지 반영되지 않는다. 그래서 cron에 요일 필터가 없다.
     *  3. **대상 0건이 정상이다.** 평가 대상은 설정이 아니라 사용자가 등록한 자산이라,
     *     아무도 실물자산을 안 넣었으면 0건이 맞다. 이걸 실패로 내면 배포 첫날부터 매일 빨개진다.
     *
     * 어드민 컨트롤러에 위임하는 이유는 형제 트리거들과 같다 — 상태 코드가 담는 "운영자를 어디로
     * 보낼지"가 Actions 로그를 읽는 사람에게 그대로 필요하다. **이 위임을 "정리"하지 말 것.**
     */
    @PostMapping("/real-asset/valuate")
    fun valuateRealAssets(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<RealAssetValuationSummary> {
        authorize(token)
        return realAssetAdmin.valuate(null)
    }

    /**
     * POST /api/internal/scheduler/watch/collect — 시계 평가 복제 트리거 (W5)
     *
     * **평가(19:30)보다 먼저 돌아야 한다.** 이 배치가 채우는 `watch_valuation_cache`를
     * `WatchPriceSource`가 읽는다 — 순서가 뒤집히면 평가가 하루 묵은 캐시를 쓴다.
     * 워크플로가 19:20 KST인 이유가 그것이다.
     *
     * **상류를 부르는데도 502를 안 낸다** — 폴백이 직전 값을 쓰므로 하루 못 받은 것은
     * 장애가 아니다. 자세한 근거는 [WatchValuationAdminController.collect].
     */
    @PostMapping("/watch/collect")
    fun collectWatchValuations(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<WatchValuationCollectSummary> {
        authorize(token)
        return watchAdmin.collect()
    }

    /**
     * POST /api/internal/scheduler/closing — 일별 마감 워크플로우 트리거
     *
     * **어드민 컨트롤러에 위임하지 않는다 — 이 파일의 다른 트리거와 다른 유일한 자리다.**
     * [com.allfolio.api.admin.ClosingAdminController.runDay]는 `X-User-Id`를 받아 그 값을
     * 실행자로 `wf_job_log.executor`에 찍는다. 크론에는 어드민 신원이 없고, 실존 인물의 id를
     * 자동 실행에 찍으면 "이 마감을 누가 돌렸나"에 거짓으로 답하게 된다. 위임해서 얻는 것은
     * 409 매핑 한 줄뿐인데 그건 GlobalExceptionHandler가 이미 해 준다.
     * 그래서 [WfStepExecutor.runDaily]를 직접 부른다 — 기본 실행자가 SYSTEM이다.
     *
     * **날짜를 노출하지 않는다.** 다른 트리거와 같은 이유다 — 컨테이너가 UTC라 클라이언트가
     * 날짜를 정하면 하루씩 밀린다. [closingDate]가 KST로 옮겨 정한다.
     *
     * 응답으로 WfRunSummary를 그대로 싣는다. 어느 단계가 게이트에서 스킵됐는지가
     * Actions 잡 요약에서 읽히는 것이 이 엔드포인트의 관측 수단 전부다.
     */
    @PostMapping("/closing")
    fun runClosing(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): WfRunSummary {
        authorize(token)
        return stepExecutor.runDaily(closingDate(Instant.now()))
    }

    /**
     * 설정 토큰이 비어 있으면 503으로 닫는다 — 이 메서드에서 가장 중요한 분기다.
     * 빈 값을 "토큰 불필요"로 해석하면 SCHEDULER_TOKEN을 빠뜨린 순간 엔드포인트가 완전 공개된다.
     * 설정 누락의 기본값은 "열림"이 아니라 "닫힘"이어야 한다.
     */
    private fun authorize(token: String?) {
        if (configuredToken.isBlank()) {
            log.warn("[Scheduler] scheduler.trigger-token 미설정 — 트리거 엔드포인트를 닫는다")
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "스케줄러 토큰이 설정되지 않았습니다.",
            )
        }
        // 상수 시간 비교. 길이는 새지만 내용은 새지 않는다.
        val presented = token?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        if (!MessageDigest.isEqual(presented, expectedToken)) {
            log.warn("[Scheduler] 트리거 토큰 불일치 — 거부")
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다.")
        }
    }

    companion object {
        private const val TOKEN_HEADER = "X-Scheduler-Token"

        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 마감 일자 — UTC 순간을 KST 날짜로 옮긴다.
         *
         * 크론이 UTC 15:00에 뛰면 KST로는 **다음 날** 00:00이다. 그 하루가 이 함수의 존재
         * 이유이고, `LocalDate.now()`를 쓰면 정확히 그 하루를 잃는다.
         * 함수로 뽑은 이유는 테스트가 시각을 고정할 수 있게 하려는 것이다 —
         * 크론 표현식 자체는 `.github/` 아래 YAML이라 값으로 검증할 방법이 없다.
         */
        internal fun closingDate(now: Instant): LocalDate = now.atZone(KST).toLocalDate()
    }
}
