# AF-100 — ECOS 과거 환율 시계열 백필 + 현금흐름 환산 오차 수정

작성일: 2026-08-11
노션: AF-100 (P1 가시화) · 관련 문서 「ALLFOLIO 시장 화면 설계 — 2026-08-11」

## 배경

`cash_flow.amount_krw`가 **거래 시점이 아니라 조회 시점 환율**로 채워지고 있다.

`FxConverter` → `CurrencyConverter` → `RedisFxRateService`(TTL 60초 현재가) 체인에는 날짜 개념이 없다.
과거 환율을 담는 테이블도 없다. 그래서 2023년에 체결된 USD 매수가 2026년 환율로 환산되어
저장되고, 그 값이 netFlow로 들어가 TWR·MWR을 왜곡한다.

AF-93(소급 현금흐름 생성)이 체결일·체결금액까지는 바로잡았지만, 환산 환율은 여전히 오늘 것이다.

## 오차 지점

세 곳이다. 전부 `flowDate`를 명시적으로 받으면서 환산은 현재 환율로 한다.

| 위치 | 상황 |
|---|---|
| `SyncAccountUseCase.kt:181` | AF-93 소급 현금흐름 — 체결일 거래를 오늘 환율로 |
| `RecordCashFlowUseCase.kt:33` | 사용자가 과거 날짜 USD 입금 직접 입력 (line 24가 과거 날짜를 허용) |
| `RecordInternalFlowUseCase.kt:29,49,50` | 과거 날짜 이체·환전 |

나머지 약 25개 `fx.toKrw` 호출부(`NavCalculator`, `ReportService`, `GetDashboardUseCase`,
`GetPortfolioUseCase`, `GoalService`, `EsgReportService`, `DailyNavScheduler`)는 전부 자산 평가액이라
현재 환율이 맞다. 이 작업에서 건드리지 않는다.

경계 규칙: **`cash_flow.amount_krw`는 `flowDate` 환율, 자산 평가는 오늘 환율.**

## 범위

포함:
- ECOS 일별 환율 시계열 수집·저장 (백필)
- 날짜 인식 환산 포트 + 어댑터
- 위 소비 지점 3곳 수정

제외:
- 이미 저장된 잘못된 `cash_flow` 레코드 소급 정정 (별도 판단)
- BTC/ETH 과거 시세 — ECOS에 없다
- 일일 자동 갱신 — AF-103(Render Cron)이 맡는다
- 시장 화면 환율 탭 58통화 — AF-99(하나은행)가 맡는다

## 1. 데이터 모델

> **구현 시 바뀐 부분** — 아래는 실제 배포된 스키마다. 초안은 복합 PK와 `TIMESTAMPTZ`를 썼는데,
> 이 코드베이스의 모든 테이블이 대리키 `id UUID` PK + `TIMESTAMP` 관례라 거기에 맞췄다.
> 정본은 `docs/superpowers/migrations/2026-08-11-fx-rate-daily.sql`이다.

```sql
CREATE TABLE IF NOT EXISTS fx_rate_daily (
    id         UUID           NOT NULL,
    base_date  DATE           NOT NULL,
    currency   VARCHAR(10)    NOT NULL,
    rate_krw   NUMERIC(18, 6) NOT NULL,   -- 통화 1단위당 KRW
    source     VARCHAR(20)    NOT NULL DEFAULT 'ECOS',
    created_at TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_fx_rate_daily PRIMARY KEY (id),
    CONSTRAINT uk_fx_rate_daily UNIQUE (base_date, currency)
);
CREATE INDEX IF NOT EXISTS idx_fx_rate_daily_lookup ON fx_rate_daily (currency, base_date DESC);
```

`rate_krw`는 **항상 통화 1단위 기준**으로 정규화해 저장한다. ECOS는 JPY를 100엔 기준으로 주므로,
지금 USD만 다루더라도 정규화를 수집기 책임으로 못박아 둔다. 나중에 통화가 늘 때 이 규칙이
없으면 JPY만 100배로 들어간다.

하나은행 회차별 고시 테이블(AF-99)과 섞지 않는다. 성격이 다르다 — 이쪽은 일별 확정 종가 한 건,
저쪽은 하루 안에서 여러 회차다.

## 2. 수집 — ECOS 클라이언트 + 어드민 트리거

위치: `backend-app`의 `com.allfolio.fx`.

- `EcosApiClient` — ECOS StatisticSearch 호출, 응답 파싱
- `FxRateBackfillService` — 기간을 받아 수집·정규화·upsert, 요약 반환
- `FxRateAdminController`에 `POST /api/admin/fx/backfill?currency=USD&from=&to=` 추가

`market-data` 모듈이 아니라 `backend-app`에 두는 이유: AF-100은 일회성 과거 백필이 본체이고,
`market-data`에 두면 AF-103(Render Cron)이 사실상 선행이 된다. AF-100의 노션 의존성은 "없음"이다.
일일 갱신을 붙일 때 AF-103에서 옮긴다.

저장은 재실행이 안전해야 긴 기간을 나눠 돌릴 수 있다.

> **구현 시 바뀐 부분** — 초안은 네이티브 `ON CONFLICT (base_date, currency) DO UPDATE`를 썼는데,
> H2(테스트)와 Postgres(운영)의 `DO UPDATE` 지원이 갈려서 **기존 행을 한 번에 읽어 값만 덮는**
> 방식으로 바꿨다. 어드민이 수동으로 돌리는 경로라 동시성 경합을 걱정할 필요가 없고,
> 자연키 UNIQUE 제약이 최후 방어선이다. 동시에 두 번 돌리면 지는 쪽이 409를 받는다.

안전장치 (AF-99 스크래퍼 원칙을 그대로 적용):
- 응답 0건 → 아무것도 쓰지 않고 실패로 기록. 빈 결과로 기존 값을 덮지 않는다
- `rate_krw <= 0` 또는 파싱 실패 행 → 스킵하고 카운트
- 응답으로 `{요청범위, 저장, 스킵, 최초일, 최종일}` 요약 반환 — 무엇이 들어갔는지 눈으로 확인 가능해야 한다

설정 (**실제 배포된 형태** — 정본은 `application.yml`):
```yaml
ecos:
  api-key: ${ECOS_API_KEY:}
  base-url: ${ECOS_BASE_URL:https://ecos.bok.or.kr}
  series:
    USD:
      stat-code: ${ECOS_USD_STAT_CODE:}   # 착수 시 ECOS 사이트에서 확인
      item-code: ${ECOS_USD_ITEM_CODE:}   # 원/미국달러
      unit-divisor: 1
```

초안은 `ecos.fx.stat-code`(→ `ECOS_FX_STAT_CODE`)였으나 통화별 시계열 맵으로 바뀌었다.
**환경변수 이름이 `ECOS_USD_STAT_CODE`·`ECOS_USD_ITEM_CODE`다** — 초안 이름으로 등록하면
아무 효과가 없고 백필이 `NO_SERIES`로 실패한다.

통화를 추가하려면 YAML 수정이 필요하다. 대문자 맵 키는 환경변수 relaxed binding으로 표현할 수
없기 때문이다(`ECOS_SERIES_JPY_STAT_CODE`는 `ecos.series.jpy.*`로 바인딩된다). 백필 서비스는
대소문자 무관으로 조회하므로 소문자 키도 동작한다. 다만 어댑터의 `HISTORICAL` 집합은 별도로
하드코딩돼 있어, 통화를 늘리려면 양쪽을 함께 고쳐야 한다.

### 확정된 코드 (2026-08-12, ECOS 통계코드검색에서 직접 확인)

ECOS OpenAPI의 [통계코드검색](https://ecos.bok.or.kr/api/#/DevGuide/StatisticalCodeSearch)에서
트리를 직접 열어 확인한 값이다.

| 항목 | 값 |
|---|---|
| `ECOS_USD_STAT_CODE` | **`731Y001`** — 3.1.1.1. 주요국 통화의 대원화환율, 주기 `[D]`(일별) |
| `ECOS_USD_ITEM_CODE` | **`0000001`** — 원/미국달러(매매기준율), 단위 `[원]` |
| `unit-divisor` | `1` (원/미국달러는 1달러 기준) |

트리 위치: `3. 환율/통관수출입/외환보유액` → `3.1. 환율` → `3.1.1. 일일환율` → `3.1.1.1.`

**주의**: 웹 검색은 `731Y001`을 "외환보유액"이라고 답한다. 틀렸다. 1차 출처를 볼 것.

부수 확인 둘:
- 이 통계표의 **항목 차원은 하나뿐**이다(`계정항목-코드1`). 클라이언트가 item code를 하나만
  넘기는 현재 구조와 맞는다. 차원이 둘 이상인 통계표를 쓰게 되면 URL 조립을 고쳐야 한다.
- **원/일본엔은 `[0000002]`이고 100엔 기준**이다. `unitDivisor`를 만든 근거가 1차 출처로 확인됐다 —
  JPY를 추가하면 divisor는 100. 그 밖에 원/이태리리라(100리라, `0000006`),
  원/스페인페세타(100페세타, `0000010`)도 100 단위다.

같은 계열 참고: 원/위안(매매기준율) `0000053`, 원/유로 `0000003`, 원/영국파운드 `0000012`,
원/캐나다달러 `0000013`, 원/호주달러 `0000017`.

---

통계표·항목 코드는 설정값으로 빼고 **사이트에서 직접 확인해 채운다.** 설계 문서에서
추정하지 않는다 — 틀린 코드는 조용히 0건을 반환하고, 위 안전장치에 걸려 실패로만 보인다.

## 3. 조회 — 포트와 폴백

```kotlin
data class KrwConversion(
    val amountKrw: BigDecimal,
    val rateDate: LocalDate?,   // 적용된 고시일. 현재환율 폴백이면 null
    val estimated: Boolean,
)

interface FxConverter {
    fun toKrw(amount: BigDecimal, currency: String): BigDecimal

    fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate): KrwConversion =
        KrwConversion(toKrw(amount, currency), rateDate = null, estimated = true)
}
```

**default 구현이 핵심이다.** `FxConverter`를 구현하는 테스트 fake가 여러 파일에 흩어져 있어,
default 없이 메서드를 추가하면 이 변경 하나로 테스트 컴파일이 무너진다. AF-98에서 물린 것과
같은 계열의 사고다.

어댑터(`UnifiedAssetFxConverterAdapter`) 폴백 순서:

0. 통화 정규화 — `USDT`는 `USD`로 치환한다 (현행 근사를 유지). 이후 단계는 치환된 코드로 판단한다
1. `KRW` → 1:1, `estimated=false`
2. `USD` → `SELECT rate_krw, base_date FROM fx_rate_daily WHERE currency=? AND base_date <= ? ORDER BY base_date DESC LIMIT 1`
   - hit → `estimated=false`, `rateDate`는 조회된 `base_date`
   - miss (요청일이 보유 최소일보다 이름) → 현재 환율, `estimated=true`, `rateDate=null`
3. `BTC`/`ETH` → 과거 시세 없음. 현행 현재가 환산, `estimated=true`

이번 백필 대상 통화는 **USD 1종**이다. 테이블·조회는 통화 일반으로 만들되, 수집은 USD만 돌린다.
계좌 통화 화이트리스트가 `KRW/USD/USDT/BTC/ETH` 5종이고, 이 중 ECOS로 과거를 채울 수 있는 것이
USD뿐이기 때문이다.

주말·공휴일은 별도 로직이 필요 없다. `base_date <= ?` + `DESC LIMIT 1`이 직전 영업일로 자동으로
이어준다. 백필 범위 이전으로 무한정 거슬러 가는 문제도 없다 — 범위 이전이면 애초에 행이 없어
miss로 떨어진다.

과거 일자 환율은 확정값이라 변하지 않으므로 어댑터에 `ConcurrentHashMap` 캐시를 둔다.
**오늘 날짜는 캐싱하지 않는다** — 아직 확정 전이다. 거래 수백 건짜리 sync에서 날짜별 반복 조회가
도는 것을 막는 용도이고, 프로세스 재시작 시 비워져도 무방하다.

Redis는 쓰지 않는다. 시장 화면 설계 문서의 원칙("Postgres가 진실, Redis는 가속, Redis가 비어도
동작해야 한다")에 따라, 이 경로는 Postgres 단건 인덱스 조회로 충분하다.

## 4. 소비 지점 3곳

세 곳 모두 `toKrwOn(amount, currency, flowDate)`로 교체한다. `estimated` 처리는 갈린다.

**`SyncAccountUseCase`** — 시스템이 생성하는 메모이므로 표기를 붙인다:

```
거래 로그 기준 자동 기록(삼성전자) · 환율 추정치
```

**`RecordCashFlowUseCase` / `RecordInternalFlowUseCase`** — 사용자가 쓴 메모를 서버가 고쳐 쓰지
않는다. WARN 로그만 남긴다. 사용자 입력을 서버가 편집하면 그 메모는 더 이상 사용자의 것이 아니다.

## 5. 테스트

TDD. 아래에서 위로.

- 조회 계층: 정확일 hit / 주말 → 직전 영업일 / 범위 이전 → miss
- 어댑터 폴백 5분기: KRW, USD hit, USD miss, USDT → USD 매핑, BTC 현행
- `EcosApiClient`: 고정 JSON 파싱, ECOS 에러코드, 0건 응답
- `FxRateBackfillService`: upsert 멱등성, 0건이면 미저장, 이상값 스킵 카운트
- 소비 지점: 기존 `SyncAccountUseCaseBackdatedInflowTest`에 "과거 USD 거래가 체결일 환율로
  환산된다" 추가. `RecordCashFlowUseCase`·`RecordInternalFlowUseCase`도 각 1건
- 회귀: `NavCalculator` 등 기존 `toKrw` 경로가 그대로인지 (기존 테스트가 커버)

## 6. 배포

마이그레이션: `docs/superpowers/migrations/2026-08-11-fx-rate-daily.sql` + `init.sql` 하단 추가.
`ddl-auto=none`이므로 **Neon 수동 실행이 필요하다(사용자 몫).**

환경변수: `ECOS_API_KEY`, `ECOS_USD_STAT_CODE`, `ECOS_USD_ITEM_CODE` — Render에 사용자가 등록.

**키가 없어도 배포가 안전하다.** 테이블이 비어 있으면 조회가 전부 miss → 현재 환율 폴백 →
현행과 동일하게 동작한다. 키·백필 없이 먼저 머지해도 회귀가 없고, 키가 생긴 뒤 백필을 돌리면
그때부터 값이 정확해진다. 배포와 데이터 확보를 분리할 수 있다는 뜻이다.

**단, 마이그레이션은 배포보다 먼저다.** `ddl-auto=none`이라 테이블이 없어도 앱은 정상 기동한다 —
그리고 계좌 동기화·이체·환전에서 500이 나기 시작한다. 조회 실패를 삼키고 폴백하도록 만들어 뒀지만,
그 세 경로는 `@Transactional` 안이라 JPA가 트랜잭션을 rollback-only로 표시해 커밋에서 터진다.
수동 입출금만 조용히 폴백한다. 기동이 멀쩡해서 더 헷갈리니 순서를 지킬 것.

### 운영 절차

**백필 시작일 정하기** — 가장 오래된 외화 거래·현금흐름 기준:
```sql
SELECT MIN(flow_date) FROM cash_flow WHERE currency IN ('USD','USDT');
SELECT MIN(traded_at)  FROM ua_stock_trades;
```

**실행** (다년 구간은 **반드시 나눠서** — 행마다 merge SELECT가 나가 한 트랜잭션이 길어진다):
```
POST /api/admin/fx/backfill?currency=USD&from=2020-01-01&to=2020-12-31
```

**결과 확인** — 응답 요약의 `inserted`/`updated`/`unchanged`/`skipped`/`duplicates`/`outOfRange`를
먼저 보고, DB에서 한 번 더 대조:
```sql
SELECT currency, COUNT(*), MIN(base_date), MAX(base_date) FROM fx_rate_daily GROUP BY currency;
```

**응답 코드 읽는 법**: 400=요청이 잘못됨(통화·기간) · 409=동시 실행, 재실행하면 됨 ·
500=우리 설정 누락(`ECOS_API_KEY` 등) · 502=ECOS 쪽 문제. 본문의 `code`가 어느 경우인지 말해 준다.

**수동으로 한 행을 고칠 때**는 `id`를 직접 넣어야 한다 — `fx_rate_daily.id`에 `DEFAULT gen_random_uuid()`가 없다.

### 네 단계를 다 끝내도 기존 수익률은 그대로 틀리다

분명히 해 둔다. 이 작업이 고치는 것은 **앞으로 기록되는** `cash_flow.amount_krw`다.
이미 저장된 행을 다시 계산하는 경로는 어디에도 없다 — 대시보드·수익률 보고서·현금흐름 보고서는
전부 저장된 `amountKrw`를 그대로 읽는다. 즉 AF-100을 촉발한 그 잘못된 TWR/MWR은
**기존 데이터에 대해서는 여전히 틀린 채로 남는다.**

소급 정정을 별도 판단으로 미룬 건 의도한 것이지만(아래 미결 참조), "배포하면 수익률이 고쳐진다"고
읽히면 안 된다. 정정하려면 어느 행이 추정치였는지 골라내야 하고, 지금은 그 표식이 메모 문자열뿐이다.

## 미결

- ~~ECOS 통계표 코드·항목 코드~~ → **확인 완료 (2026-08-12)**. 아래 참조.
- 백필 시작 시점을 언제로 잡을지 — 가장 오래된 거래 체결일 기준으로 정하면 충분
- **추정치 출처를 데이터로 남길지** (Task 5 코드 리뷰에서 제기, 2026-08-11).
  지금은 `estimated`를 메모 접미사로만 남기므로, 나중에 정정 대상을 고르려면 `LIKE '%환율 추정치%'`밖에
  없고 두 종류를 구분하지 못한다 — **백필 범위 밖이라 추정치인 건(범위를 넓히면 고칠 수 있음)** 과
  **BTC/ETH라 추정치인 건(과거 시세 소스가 아예 없어 영영 못 고침)**. `KrwConversion.rateDate`는
  이번 범위에서 버려지고 프로덕션에서 읽는 데가 없다.
  후보: `cash_flow`에 nullable `fx_rate_date DATE`를 추가해 `rateDate`를 저장하면, null=추정치이고
  고칠 수 있는지 여부는 `currency`로 갈린다. 이번엔 스키마를 넓히지 않고 호출부 WARN에 행 식별자를
  실어 로그로 셀 수 있게만 해뒀다. **소급 정정 작업을 시작하기 전에 결론을 낼 것.**
- **한 계좌에 통화가 섞인 경우** — `StockTrade`에 통화 필드가 없어 `account.currency`가 거래의 유일한
  통화다. 국내 KRW와 해외 USD를 한 계좌에 담으면 이제 "일률적으로 틀린 오늘 환율" 대신
  "정밀하게 틀린 과거 환율"로 환산된다. 회귀는 아니지만 결과가 날카로워졌다. 거래 단위 통화가 필요해지면
  `ua_stock_trades`에 컬럼 추가가 선행되어야 한다.
