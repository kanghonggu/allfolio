# 과거 크립토 시세 — 백필 소스 seam 추출

작성일: 2026-08-13
선행: [AF-100 과거 환율 백필](2026-08-11-historical-fx-backfill-design.md) · [BTC·ETH KRW 시세 수집](2026-08-12-crypto-krw-feed-design.md)

## 배경

`UnifiedAssetFxConverterAdapter.toKrwOn`이 BTC·ETH를 `estimatedNow`로 우회시킨다.

```kotlin
// BTC/ETH는 과거 시세를 가진 소스가 없다 — 현행 현재가 환산을 유지한다
if (code in CRYPTO) return estimatedNow(amount, code)
```

주석이 사실이라 정직한 우회였지만, 결과가 **디스크에 박힌다**.
`cash_flow.amount_krw`는 입력 시점에 한 번 계산돼 저장되고(`FxConverter:50` → `CashFlowController:137`)
다시 계산되지 않는다. 즉 **날짜를 소급한 크립토 현금흐름은 "오늘 시세"가 영구히 굳는다.**

그리고 그 값은 표시용이 아니다 — `ReturnsCalculator`(TWR·MWR)와 대시보드 순유입이 쓴다.
**틀리면 수익률이 틀린다.**

### 실제로 일어났다

2026-08-13 확인: `cash_flow`에 `flow_date=2026-08-01`인 BTC 0.5 · ETH 2.0 두 행이 있었고,
`amount_krw`가 당시 하드코딩 상수로 굳어 있었다(implied_rate 90,000,000 / 4,500,000).
Upbit 일봉 2026-08-01 종가(BTC 90,557,000 / ETH 2,660,000)로 정정했고 합계 −3,401,500원,
KRW 흐름 총액의 8.1%였다.

**이 설계는 그 사고의 재발을 막는다. 이미 저장된 행은 고치지 않는다** — `amount_krw`는
한 번 쓰고 마는 값이라 소급 재계산 경로가 없다. 기존 행은 수동 정정이 유일한 방법이고 이미 했다.

## 범위

포함:
- Upbit 일봉을 과거 크립토 시세 소스로 붙인다
- 백필 서비스에서 **가져오기(fetch)와 저장하기(merge)를 가른다** — 소스가 둘이 되므로
- `toKrwOn`이 BTC·ETH도 과거 시세를 조회하게 한다

제외:
- **과거 USDT** — 지금은 `canonical`이 USD로 접어 고시가를 쓴다. AF-99가 의식적으로 내린 결정이라
  뒤집으려면 그 근거부터 다시 다뤄야 한다. 이번 사고와도 무관하다
- **자동 수집 스케줄** — 백필은 어드민이 돌린다. AF-103 cron에 붙이는 건 별건
- **기존 `cash_flow` 행 소급 정정** — 위 참조. 이미 수동으로 끝냈다

## 1. 저장 — 마이그레이션이 필요 없다

`fx_rate_daily`를 그대로 쓴다.

```sql
CREATE TABLE IF NOT EXISTS fx_rate_daily (
    id UUID, base_date DATE, currency VARCHAR(10), rate_krw NUMERIC(18,6),
    source VARCHAR(20) NOT NULL DEFAULT 'ECOS', created_at TIMESTAMP,
    CONSTRAINT uk_fx_rate_daily UNIQUE (base_date, currency)
);
```

`source` 컬럼이 **정확히 이 용도로** 이미 있다. `'UPBIT'` 행을 넣으면 된다.
`NUMERIC(18,6)`은 정수부 12자리라 90,000,000(8자리)이 여유롭게 들어간다.
`(base_date, currency)` UNIQUE도 그대로 맞는다 — 한 날짜에 한 통화는 한 값이다.

## 2. seam — 무엇이 소스별이고 무엇이 공용인가

`FxRateBackfillService`는 지금 두 일을 한다. **가져오기만 소스별이고 저장하기는 공용이다.**

코드를 읽고 확인한 결과, 공용이라고 착각하기 쉬운 두 가지가 실은 ECOS 전용이다:

- **`series.unitDivisor` 정규화** — ECOS가 JPY를 100엔 단위로 고시해서 필요한 나눗셈이다.
  Upbit 일봉에는 그런 개념이 없다
- **`SOURCE` 상수(`'ECOS'`)** — 엔티티에 박아 넣는 값

따라서 seam은 **"소스가 1단위 기준으로 정규화된 값을 돌려주고, 자기 이름을 밝힌다"**로 긋는다.

```kotlin
/** 하루치 환율. 이미 1단위 기준으로 정규화돼 있다. */
data class DailyRate(val baseDate: LocalDate, val rateKrw: BigDecimal)

/** @param skipped 소스가 파싱 단계에서 버린 행 수 — BackfillSummary로 그대로 나간다 */
data class SourceFetch(val rates: List<DailyRate>, val skipped: Int)

interface HistoricalRateSource {
    /** fx_rate_daily.source에 들어갈 값 ('ECOS' | 'UPBIT') */
    val sourceName: String
    fun supports(currency: String): Boolean
    /** 실패는 예외로 알린다. 빈 리스트를 돌려주면 호출자가 기존 값을 덮지 않고 중단한다. */
    fun fetch(currency: String, from: LocalDate, to: LocalDate): SourceFetch
}
```

**소스로 옮기는 것** (ECOS 전용):
`seriesOf` 설정 조회 · `client.fetchDailyRates` 호출 · `EcosApiException` 로깅 ·
`unitDivisor` 나눗셈 · `EcosRate → DailyRate` 변환

**서비스에 남기는 것** (공용 방어):
소스 선택 · 0건 중단 · 요청 범위 밖 제거 · 같은 날짜 dedupe ·
기존 행 조회 · inserted/updated/unchanged 계수 · `saveAll` · `fxConverter.invalidate()` · `BackfillSummary`

이 방어들은 ECOS를 겪으며 생겼지만 **소스와 무관하게 옳다.** Upbit도 빈 응답·중복 날짜·범위 밖
날짜를 줄 수 있고, 그때 기존 값을 덮거나 UNIQUE 제약으로 배치를 죽이면 안 되는 건 똑같다.
복제하지 않고 한 벌만 둔다.

`EcosResponseParser`와 `EcosRate`는 **건드리지 않는다.** ECOS 응답 전용이 맞고,
소스가 `DailyRate`로 옮기면 된다.

## 3. Upbit 일봉 소스

실측(2026-08-13)으로 확인한 제약:

- `GET /v1/candles/days?market=KRW-{SYM}&to={ISO8601}&count={n}` — 무인증
- **요청당 최대 200건.** `count=201`을 보내도 200건만 온다(조용히 잘린다)
- 레이트리밋 `remaining-req: group=candles; min=600; sec=9`
- 종가는 `trade_price`, 날짜는 `candle_date_time_kst`
- 응답은 **최신순 내림차순**이고 `to`는 그 시각 **이전** 캔들을 준다

```
UpbitCandleClient   — HTTP만. to·count를 받아 본문 문자열을 돌려준다
UpbitCandleParser   — 순수. JSON → List<DailyRate>. 픽스처로 검증한다
UpbitCandleRateSource — 페이지네이션 + supports("BTC"|"ETH")
```

HTTP와 파싱을 가르는 이유는 앞선 두 PR과 같다. 그 자리에 테스트가 없어서
동작할 수 없는 Binance 클라이언트가 배포됐고, 이번 시리즈에서 파서 테스트가 실제로 회귀를 두 번 잡았다.

**페이지네이션**: `to`를 뒤로 밀며 200건씩 당기고 `from`에 닿으면 멈춘다.
200일 구간은 1회, 5년은 약 10회다. 분당 600회 예산 대비 무시할 수준이라 별도 스로틀을 두지 않는다.
**무한루프 방어**: 한 번 돌 때마다 `to`가 반드시 과거로 가야 한다. 안 그러면 중단하고 예외를 던진다 —
Upbit이 같은 페이지를 반복해 주면 조용히 영원히 도는 것보다 낫다.

## 4. 조회 경로 연결

```kotlin
private val HISTORICAL = setOf("USD", "BTC", "ETH")   // 기존: setOf("USD")
// 삭제: if (code in CRYPTO) return estimatedNow(amount, code)
```

`canonical()`은 `USDT → USD`만 접으므로 BTC·ETH는 그대로 통과한다(확인함).

**백필된 데이터가 없어도 안전하다.** `lookup`이 null을 주면 기존과 똑같이 `estimatedNow`로
떨어지고, 다만 이제 `[Fx] 과거 환율 없음` 경고가 남는다. 지금은 그 우회가 조용하다 —
**보이게 만드는 것 자체가 이 변경의 절반이다.**

## 5. 어드민 진입점

기존 `POST /api/admin/fx/backfill?currency=BTC&from=...&to=...`를 그대로 쓴다.
서비스가 `supports`로 소스를 고르므로 운영자는 통화만 바꾸면 된다.
`BackfillSummary` 반환 타입도 그대로다.

지원 소스가 없는 통화면 `IllegalArgumentException` — 지금 ECOS 시계열 설정이 없을 때와 같은 동작이다.

## 6. 테스트

- `UpbitCandleParser` — 기록된 실제 응답 픽스처. 종가·KST 날짜 추출, 빈 배열, JSON 아님,
  `trade_price` 없는 캔들은 건너뛰고 나머지는 살린다
- `UpbitCandleRateSource` — 스텁 서버로 **페이지네이션을 못 박는다**: 400일 구간이 정확히 2회
  요청하고 두 번째 `to`가 첫 페이지의 마지막 날짜여야 한다. `to`가 안 물러나면 중단하는지도
- `FxRateBackfillService` — 가짜 `HistoricalRateSource`로 소스 선택·0건 중단·범위 밖 제거·
  dedupe·계수를 네트워크 없이 검증한다. **기존 ECOS 테스트는 그대로 통과해야 한다**
- `UnifiedAssetFxConverterAdapter` — BTC가 `fx_rate_daily` 행이 있으면 그 값을, 없으면
  `estimated=true`로 현재가 폴백인지

## 알면서 남기는 것

**일봉 종가는 근사다.** 입금 시각이 09:00 KST여도 그날 종가를 쓴다. 2026-08-01 실측 일중 범위는
BTC 89.8M~90.8M, ETH 2.629M~2.696M — 약 ±1%다. 상수 대비 두 자릿수 개선이지만 정확하진 않고,
사후 재구성으로 정확해질 방법은 없다.

**백필은 사람이 돌려야 한다.** 아무도 안 돌린 구간의 크립토 현금흐름은 여전히 `estimatedNow`로
떨어진다. 이 PR은 그 경로를 **없애는 게 아니라 보이게** 한다. 자동 수집은 AF-103 cron에 붙일 수
있지만 별건이다.
