# AF-99 — 하나은행 고시환율 수집기 서버 이식

작성일: 2026-08-12
노션: AF-99 (P1 가시화) · 관련 문서 「ALLFOLIO 시장 화면 설계 — 2026-08-11」
선행 완료: [AF-100 과거 환율 백필](2026-08-11-historical-fx-backfill-design.md)

## 배경

로컬에서 손으로 돌리던 `hana_fx_scraper.py`를 서버로 옮긴다. 원본은 조회일자를 물어보고
엑셀·CSV로 떨구는 대화형 스크립트다. 서버에서는 사람이 없으므로, 원본이 화면에 찍던 경고
(`데이터를 가져오지 못했습니다`, `고시가 없는 날이라 가장 가까운 영업일 고시를 가져왔습니다`)를
전부 코드가 판단해야 한다. **이 태스크의 값어치는 스크래핑이 아니라 그 판단에 있다.**

두 가지를 얻는다.
- 시장 화면 환율 탭(AF-104)과 대시보드 출처 표기(AF-105)가 쓸 회차별 고시 데이터
- **자산 평가의 USD 환산을 공식 매매기준율로 전환** — 지금은 Binance USDT/KRW를 프록시로 쓴다

## 범위

포함:
- 하나은행 고시 조회·파싱·저장 (회차 단위)
- 안전장치 (USD 부재 / 행 수 급감 / 급변동 / 연속 실패)
- 어드민 수동 트리거
- 자산 평가의 **USD** 현재가를 하나은행 매매기준율로 전환

제외:
- **스케줄** — AF-103(Render Cron)이 붙인다. 이번엔 수동 트리거까지
- 시장 화면(AF-104), 대시보드 출처 표기(AF-105)
- 전일대비·등락률 계산 — 이력이 쌓인 뒤 화면 태스크에서
- **USDT 환산** — 아래 참조. Binance를 유지한다

## 원본 스크래퍼에서 그대로 가져오는 것

`~/Downloads/hana_fx_scraper.py`에서 확인한 사실들이다. 추정이 아니라 동작하는 코드에서 옮긴다.

- **엔드포인트**: `POST https://www.kebhana.com/cms/rate/wpfxd651_01i_01.do`
- **폼 파라미터**: `ajax=true`, `curCd=`, `tmpInqStrDt=YYYY-MM-DD`, `pbldDvCd`, `pbldSqn=`,
  `hid_key_data=`, `inqStrDt=YYYYMMDD`, `inqKindCd=1`, `hid_enc_data=`,
  `requestTarget=searchContentDiv`
- **`pbldDvCd`**: 조회일자가 오늘이면 `3`(현재고시), 과거면 `0`(최종고시)
- **헤더**: `User-Agent`와 `Referer`(`.../wpfxd651_01i.do`)가 필요하다
- **메타 파싱**: 본문 텍스트에서 `기준일 : YYYY년MM월DD일`, `(N회차)`
- **테이블**: 11개 컬럼 — 통화 · 현찰사실때(환율·스프레드) · 현찰파실때(환율·스프레드) ·
  송금보낼때 · 송금받을때 · 외화수표파실때 · 매매기준율 · 환가료율 · 미화환산율.
  컬럼 수가 11이 아닌 `<tr>`은 버린다
- **`(100)` 단위 통화**: 통화명에 `(100)`이 붙은 행(JPY·IDR·VND 등)은 **환율 컬럼만** 100으로 나눈다.
  스프레드·환가료율은 %, 미화환산율은 비율이라 제외한다. ECOS에서 확인한 "원/일본엔은 100엔 기준"과
  같은 규칙이다
- **통화 코드**: `"미국 USD"` 같은 통화명에서 대문자 3자리를 뽑는다. 못 뽑으면 그 행은 버린다

## 1. 데이터 모델

`fx_rate_daily`(AF-100, ECOS)와 **섞지 않는다.** 성격이 다르다 — 저쪽은 일별 확정 종가 한 건,
이쪽은 하루 안에 여러 회차 × 통화별 6개 환율이다.

```sql
CREATE TABLE IF NOT EXISTS hana_fx_quote (
    id            UUID          NOT NULL,
    base_date     DATE          NOT NULL,   -- 하나은행이 준 기준일. 조회일자가 아니다
    round_no      INT           NOT NULL,   -- 고시 회차
    currency      VARCHAR(10)   NOT NULL,   -- ISO 3자리
    base_rate     NUMERIC(18,4) NOT NULL,   -- 매매기준율
    cash_buy      NUMERIC(18,4),            -- 현찰 사실 때
    cash_sell     NUMERIC(18,4),            -- 현찰 파실 때
    remit_send    NUMERIC(18,4),            -- 송금 보낼 때
    remit_receive NUMERIC(18,4),            -- 송금 받을 때
    collected_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_hana_fx_quote PRIMARY KEY (id),
    CONSTRAINT uk_hana_fx_quote UNIQUE (base_date, round_no, currency)
);

CREATE INDEX IF NOT EXISTS idx_hana_fx_quote_latest
    ON hana_fx_quote (currency, base_date DESC, round_no DESC);
```

**키가 `(기준일, 회차, 통화)`인 것이 이 설계의 핵심이다.** 하나은행은 고시가 없는 날(주말·공휴일)에
조회하면 **가장 가까운 영업일 고시를 돌려준다** — 원본 스크래퍼도 `meta["기준일"] != inq_date`일 때
그 사실을 사용자에게 알린다. 조회일자를 키로 쓰면 연휴 사흘 동안 같은 고시가 세 번 들어간다.
응답이 말하는 기준일을 그대로 키로 삼으면 그 문제가 구조적으로 사라진다.

스프레드·환가료율·미화환산율은 저장하지 않는다. 화면 어디에도 쓰지 않는다(YAGNI).
나중에 필요해지면 컬럼을 늘리면 된다 — 수집기는 이미 파싱하고 있다.

## 2. 수집

위치: `backend-app`의 `com.allfolio.fx.hana`.

- `HanaFxClient` — POST 호출. 응답 HTML 문자열을 돌려준다
- `HanaFxParser` — HTML → `HanaQuoteSnapshot(baseDate, roundNo, rows)`. **HTTP와 분리한다** —
  이 기능에서 가장 잘 깨지는 부분이고 네트워크 없이 검증되어야 한다 (AF-100의 `EcosResponseParser`와 같은 이유)
- `HanaFxCollectService` — 조회 → 안전장치 판정 → 저장 → 요약 반환

**의존성 추가 필요**: `org.jsoup:jsoup`. 현재 `backend-app`에 HTML 파서가 없다
(Jackson·OkHttp·WebClient만 있다).

HTTP는 기존 패턴을 따른다 — `BinanceFxApiClient`·`EcosStatisticSearchClient`가 쓰는 `by lazy` WebClient,
`block(TIMEOUT)`, companion 상수. 인증키가 없으므로 AF-100에서 겪은 URL 유출 문제는 없다.

## 3. 안전장치

시장 화면 설계 문서가 정한 넷이다. 하나은행은 공식 API가 아니라 **마크업이 바뀌면 예외가 아니라
조용히 빈 테이블을 돌려준다.** 알림 인프라가 없으므로 WARN/ERROR 로그와 응답 요약으로 드러낸다
(AF-100에서 쓴 방식과 동일).

| 조건 | 처리 |
|---|---|
| 파싱 결과에 USD 없음 | 저장하지 않고 실패. USD가 없으면 평가 경로가 못 쓴다 |
| 행 수가 직전 수집의 50% 미만 | 저장하지 않고 실패. 부분 파싱을 정상으로 오인하지 않는다 |
| 직전 값 대비 2% 초과 변동 | 저장하지 않고 ERROR. 아래 `force` 참조 |
| 연속 실패 3회 | ERROR로 승격 |

"직전"의 기준은 **그 통화의 가장 최근 고시**(`base_date DESC, round_no DESC`)다. 행 수 비교는
직전 수집 회차의 통화 수를 쓴다.

**비교 대상이 없으면 통과시킨다.** 첫 수집에는 직전 값도 직전 행 수도 없다. 그때 막으면 수집을
영영 시작할 수 없다. USD 부재 가드는 비교가 필요 없으므로 첫 수집에도 그대로 작동한다 —
즉 첫 수집도 완전히 무방비는 아니다.

**연속 실패 카운트는 프로세스 메모리에 둔다.** 원자적 카운터 하나면 되고, 재시작하면 0으로
돌아간다. DB에 두면 테이블·마이그레이션이 하나 더 늘어나는데, 이 카운터가 하는 일은
"로그 레벨을 올린다"뿐이라 그만한 값어치가 없다. 재시작으로 초기화되는 것도 실질적 손해가 아니다 —
재시작 자체가 이미 조사할 사건이다.

### 2% 가드에 탈출구를 둔다

이 가드는 파싱 오류 — 엉뚱한 컬럼을 읽는 것 — 를 잡으려는 것이다. 그런데 **실제로 하루에 2% 넘게
움직이는 날이 있다.** 그때 그냥 막으면 다음 회차도 같은 이유로 막혀 환율이 영구히 얼어붙는다.
가드가 지키려던 것보다 큰 피해다.

그래서 어드민 엔드포인트에 `force=true`를 둔다. 운영자가 ERROR를 보고 실제 시장을 확인한 뒤
재실행하면 통과한다. 지금은 수동 트리거뿐이라 사람이 이미 루프 안에 있고, AF-103에서 스케줄을
붙일 때는 자동 실행이 `force`를 쓰지 않으므로 가드가 그대로 산다.

## 4. 자산 평가의 USD 환산 전환

지금 `CurrencyConverter`는 USD와 USDT를 **같은 분기**에서 `fxRateService.getUsdtToKrw()`
— Binance USDT/KRW — 로 환산한다. USD를 공식 매매기준율로 옮기고 USDT는 그대로 둔다.

### USDT를 분리하는 이유

Binance USDT/KRW에는 김치 프리미엄이 섞여 있다. 그건 "부정확"이 아니라 **Binance에 실제 USDT를
들고 있는 사용자에게는 실현 가능한 값**이다. 공식 고시가로 바꾸면 더 "정확"해지지만 그 계정에서는
덜 현실적이 된다. 그래서 법정통화 USD만 공식 고시로 옮기고 스테이블코인은 거래소 시세를 유지한다.

> **후속(2026-08-12): 라벨이 이 분리를 무효화하고 있었다.**
> Binance·OKX·Bybit 어댑터가 USDT 호가로 평가액을 만들면서 `currency`는 `"USD"`로 적고 있었다.
> 그대로 두면 위 논거가 지키려던 바로 그 자산 — 거래소에 실제 USDT를 들고 있는 계정 — 이
> 공식 고시로 환산된다. 세 어댑터를 `"USDT"`로 고쳤다(`UsdtQuotedValuation`).
> 지갑(`WalletSyncAdapter`)은 Moralis `usd_value` 기반이라 `"USD"`가 맞아 그대로 둔다.
> `CurrencyConverter`가 아직 USD·USDT를 같은 분기로 보내므로 **그 시점의 숫자 변화는 없다** —
> 아래 "어댑터의 정규화를 갈라야 한다"가 들어와야 분리가 실제로 선다.

### 포트 변경

```kotlin
interface FxRateService {
    fun getUsdtToKrw(): BigDecimal
    // ...기존 셋...

    /** 공식 원/미국달러 매매기준율. 하나은행 고시가 없으면 USDT 환율로 근사한다(현행 동작). */
    fun getUsdToKrw(): BigDecimal = getUsdtToKrw()
}
```

**default 구현이 필요하다.** 이 인터페이스를 구현하는 테스트 fake가 5곳이다
(`CurrencyConverterTest`, `SecurityConfigAdminTest`, `SecurityConfigErrorDispatchTest`,
`UnifiedAssetFxConverterAdapterTest`, `FxRateBackfillServiceTest`). default 없이 메서드를 추가하면
그 변경 하나로 컴파일이 무너진다 — AF-98·AF-100 Task 1에서 이미 겪은 계열이다.
여기서는 default 값이 곧 현행 동작이라 의미도 정확하다.

`HanaFxRateService`(`@Primary`)가 `getUsdToKrw()`만 오버라이드하고 나머지 넷은
`RedisFxRateService`에 위임한다. 하나은행 행이 없거나 조회가 실패하면 default 동작으로 떨어지므로,
**수집을 한 번도 안 돌린 상태에서도 오늘과 똑같이 굴러간다.**

### 신선도: 기간 제한을 두지 않는다

가장 최근 고시를 그대로 쓴다. 주말이면 금요일 최종고시를 쓰게 되는데, 그게 실제 시장 상황과 맞는다 —
주말엔 환전도 그 값으로 된다. "N시간 넘으면 폴백" 같은 규칙을 두면 연휴에 정상인데도 폴백이 돌아
환율이 튄다.

NAV 계산 한 번에 `toKrw`가 수십 번 불리므로 **프로세스 내 60초 캐시**를 둔다. Postgres 단건
인덱스 조회지만 요청당 수십 회는 낭비다. Redis는 쓰지 않는다 — 설계 원칙이
"Postgres가 진실, Redis는 가속, Redis가 비어도 동작"이고, 이 경로는 in-process 캐시로 충분하다.

### 어댑터의 정규화를 갈라야 한다

`UnifiedAssetFxConverterAdapter.toKrw`가 `canonical(currency)`를 거치는데 그게 `USDT → USD`로 접는다.
그대로 두면 USDT가 `CurrencyConverter`에 닿기 전에 USD가 되어 **분리가 무효가 된다.**

- `toKrw` — trim + uppercase만. `USDT → USD` 매핑을 뺀다. `" usdt "`는 `"USDT"`가 되어 USDT 분기로
  가므로 AF-100이 고친 `100 USDT → 100원` 버그는 그대로 막혀 있다
- `toKrwOn` — 지금 그대로 `canonical`. 과거 USDT 시계열은 존재하지 않으니 USD로 근사하는 게 맞다

**두 경로가 의도적으로 다른 규칙을 쓴다** — 현재가는 USDT를 별개 자산으로, 과거는 USD로 근사.
양쪽에 이유를 주석으로 남긴다. 안 그러면 다음 사람이 불일치로 보고 통일해 버린다.

## 5. 어드민 엔드포인트

```
POST /api/admin/fx/hana/collect?date=YYYY-MM-DD&force=false
```

`date` 생략 시 오늘(현재고시). `/api/admin/**`는 `SecurityConfig`에서 이미 `hasRole("ADMIN")`이다.

응답: `{baseDate, roundNo, currencies, inserted, updated, unchanged, skipped, anomalies[]}`

- `currencies` — 저장 대상이 된 통화 수
- `inserted`/`updated`/`unchanged` — AF-100의 `BackfillSummary`와 같은 의미. `updated`가 0이 아니면
  같은 회차를 다시 수집해 값이 바뀐 것이다
- `skipped` — **파싱 단계에서 버린 행 수.** 컬럼 수가 11이 아니거나 통화명에서 3자리 코드를
  못 뽑은 행이다. 안전장치에 걸려 저장을 통째로 막은 것과는 다르다 — 그건 `anomalies`에 실린다
- `anomalies` — 판정에 걸린 항목을 그대로 싣는다. 무엇 때문에 막혔는지 운영자가 응답만 보고
  알 수 있어야 한다

상태 코드는 AF-100에서 정한 규칙을 따른다: 400=요청이 잘못됨 · 409=동시 실행 ·
500=우리 설정·코드 문제 · 502=하나은행 쪽 문제(응답 없음·마크업 변경).

`GET /api/admin/fx/usdkrw`도 추가한다. 기존 `usdtkrw`만 있으면 전환 후 **평가 경로가 실제로 무엇을
쓰는지 확인할 방법이 없다.**

## 6. 테스트

TDD, 아래에서 위로.

- `HanaFxParser`: 실제 응답을 저장한 고정 HTML로 — 정상 파싱, `(100)` 통화 ÷100,
  컬럼 수 불일치 행 버림, 통화 코드 추출 실패 행 버림, 빈 테이블, 기준일·회차 추출
- 안전장치 4종: 각각 저장을 막는지, 그리고 `force=true`가 2% 가드만 뚫는지
- 저장: `(기준일, 회차, 통화)` upsert 멱등성, 주말 조회 시 응답 기준일로 저장되는지
- `HanaFxRateService`: 하나은행 행 있음 → 그 값, 없음 → 위임, 조회 실패 → 위임, 60초 캐시
- `CurrencyConverter`: USD와 USDT가 **다른 환율**로 환산되는지 (분리의 핵심)
- 어댑터: `toKrw("usdt")`가 USDT 분기로 가는지, `toKrwOn`은 여전히 USD로 접는지

## 7. 배포

마이그레이션 `docs/superpowers/migrations/2026-08-12-hana-fx-quote.sql` + `init.sql` 하단.
`ddl-auto=none`이므로 **Neon 수동 실행이 배포보다 먼저다** — AF-100에서 확인했듯 테이블이 없어도
앱은 정상 기동하고, 조회하는 경로에서만 터진다.

`HanaFxRateService`는 행이 없으면 위임하므로 **수집 전에 배포해도 회귀가 없다.** 배포 → 수집 →
그때부터 USD 평가가 공식 고시로 바뀐다.

새 환경변수는 없다. 하나은행은 인증이 필요 없다.

## 미결

- **하나은행이 봇 트래픽을 어떻게 대하는지 모른다.** 수동 트리거 단계에서는 문제가 안 되지만
  AF-103에서 10분 간격으로 돌리기 전에 확인이 필요하다. 차단되면 수집이 조용히 0건이 되고,
  그건 위 안전장치가 잡는다
- **`(100)` 외에 다른 단위 표기가 있는지 모른다.** 원본 스크래퍼는 `(100)`만 처리한다.
  파서가 통화명에서 코드를 못 뽑으면 그 행을 버리므로 조용히 틀리지는 않지만, 로그로 드러낸다
- 시세 재배포 약관(AF-108)은 별도 태스크다. 하나은행 고시환율을 화면에 노출하는 것이
  거기 걸리는지는 그 태스크에서 판단한다
