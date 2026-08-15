# AF-106 수익 기여도 분해 (자산 vs 환율) — 설계

- 작성일: 2026-08-15
- 상태: 설계 확정
- 화면: `/unified/reports/returns` (수익률 보고서 R-02)

## 1. 왜 이게 단순한 계산 태스크가 아닌가

노션 명세의 식은 한 줄이다.

```
(1 + 자산수익률) × (1 + 환율변동률) − 1 = 원화수익률
```

문제는 계산이 아니라 **데이터가 없다**는 것이다. 착수 전 조사 결과:

| 계층 | 상태 |
|---|---|
| `performance_daily.nav` | 원화 총액 하나. 통화별 내역 없음 |
| `position_daily` | quantity·averageCost·PnL — **통화 컬럼 없음** |
| `ReturnsCalculator.NavPoint(date, nav)` | 원화만 |
| `JdbcNavHistorySource` | `SELECT date, nav FROM performance_daily` |
| `cash_flow` | 기록 시점에 KRW로 고정(`signedKrw()`) |

시세가 스냅샷에 들어가기 **전에** 환산된다 — `SnapshotTriggerService.kt:53`:

```kotlin
currencyConverter.toKrw(last.price, last.tradeCurrency)   // ← 날짜 인자가 없다
```

그 아래 줄부터는 전부 원화다. 게다가 이 변환은 날짜를 안 받으므로 **과거 스냅샷을 재계산해도 현재 환율이 적용된다.** 즉 저장된 과거 NAV는 "그날의 환율"을 담고 있지 않다. AF-100이 과거 환율을 2020년까지 받아 놨는데도 소급 분해가 안 되는 이유가 이것이다.

### 검토하고 버린 대안

**소급 복원 (`trade_raw` + 과거 환율)** — `trade_raw`가 `price`·`tradeCurrency`를 원통화로 보존하므로 통화 *구성*은 복원된다. 하지만 통화별 *과거 평가액*을 구하려면 자산별 과거 시세가 필요한데 **그 테이블이 없다.** 현재 스냅샷조차 "자산별 마지막 거래가"를 시세 대용으로 쓰고 있다. 막힌 길이다.

**현재 구성 × 과거 환율 (근사)** — 오늘 바로 숫자가 나온다. 그러나 기간 중 매매가 있으면 틀리고 **얼마나 틀렸는지 알 방법이 없다.** "환율이 내 수익의 얼마였나"에 답하는 화면이 검증 불가능한 추정치를 내놓으면 화면의 존재 이유가 무너진다.

**채택: 오늘부터 통화별 관측을 쌓는다.** 이 저장소는 파생값을 저장하지 않는다(`T10Y2Y`를 안 받고 계산하는 것). 그날의 통화별 평가액과 적용 환율은 파생이 아니라 **관측**이다. 지금 안 남기면 영영 복원 못 한다.

## 2. 저장 — `nav_currency_daily`

```sql
CREATE TABLE nav_currency_daily (
    portfolio_id  UUID     NOT NULL,
    date          DATE     NOT NULL,
    currency      VARCHAR(10) NOT NULL,
    value_native  NUMERIC(20,8) NOT NULL,  -- 그날의 외화 기준 평가액
    fx_rate       NUMERIC(20,8) NOT NULL,  -- 그날 적용한 KRW 환율 (KRW 행은 1)
    PRIMARY KEY (portfolio_id, date, currency)
);
```

**`value_krw`를 저장하지 않는다.** `value_native × fx_rate`로 나오고, 저장하면 셋이 어긋나는 날 무엇이 맞는지 가릴 수 없다.

**미지원 통화는 `fx_rate = 1`로 기록한다.** `CurrencyConverter`가 실제로 환산하는 통화는 `KRW·USD·USDT·BTC·ETH` **다섯뿐**이고, 나머지는 경고 로그를 남기고 원금을 그대로 돌려준다. 예외를 던지지 않고 그 동작을 그대로 기록한다 — 그래야 §2의 합계 불변식이 성립하고, `currency='JPY'`인데 `fx_rate=1`인 행이 **미환산 자산의 진단 지표**가 된다.

**PK가 곧 조회 인덱스다.** `(portfolio_id, date, ...)` 선두 두 열이 기간 조회를 그대로 받는다 — AF-102에서 중복 인덱스를 하나 지웠던 것과 같은 이유로 별도 인덱스를 만들지 않는다.

`performance_daily`는 손대지 않는다. 옆에 세운다.

### 불변식 — 합계 일치

```
Σ_c (value_native(c) × fx_rate(c))  ==  performance_daily.nav      (같은 portfolio_id·date)
```

이건 검증 장치이면서 동시에 §3 쓰기 지점 선택의 근거다. 같은 가격·같은 환율에서 파생시키므로 구조적으로 성립한다.

**정확히 같지는 않다.** `toKrw`가 자산별 가격을 원 단위로 반올림한 뒤 수량을 곱하기 때문에, 자산마다 최대 `0.5 × quantity`만큼 벌어진다. 허용오차는 `0.5 × Σ|quantity| + 1`로 잡는다 — 고정 원 단위로 잡으면 수량이 큰 포트폴리오에서 거짓 경보가 난다. §4의 계산은 이 드리프트에 아예 닿지 않게 설계되어 있다.

## 3. 쓰기 지점 — `SnapshotTriggerService`

**통화를 아직 아는 유일한 자리다.** `toKrw` 호출 직전엔 원통화 가격·통화가 살아 있고, 직후엔 사라진다.

`SnapshotTriggerService.trigger()`는 `@Transactional`이 없고 `generateDailySnapshotUseCase.generate()`가 커밋한 뒤 반환한다(클래스 KDoc에 명시됨). 따라서 `generate()` 반환 후에 커밋된 `position_daily`를 읽는 건 안전하다.

절차:

1. 기존 `historicalPrices` 루프에서 자산별로 `(nativePrice, currency)`를 같이 모은다 — 지금은 환산 결과만 남기고 버리고 있다.
2. 통화별 환율은 `currencyConverter.sourceOf(currency)`에서 받는다. **새로 계산하지 않는다.** AF-105가 "`sourceOf`가 밝히는 환율은 `toKrw`가 실제로 쓴 환율과 같다"를 테스트로 못박아 뒀다(`CurrencyConverterTest`). 그 테스트가 이 설계의 합계 일치를 지켜 준다.
3. `generate()` 반환 후 `position_daily`(해당 portfolio·date)를 읽어 자산별 수량을 얻는다.
4. 자산별 `quantity × nativePrice`를 통화로 묶어 합산 → `value_native`.
5. `nav_currency_daily`를 DELETE-then-INSERT로 쓴다 — 스냅샷 모듈의 재계산 멱등성 패턴과 같은 모양.

**스냅샷 모듈(ABOR 이식분) 내부는 건드리지 않는다.** `GenerateDailySnapshotUseCase`의 시그니처도 트랜잭션 경계도 그대로다.

### `currentPrices` 경로의 한계 — 명시적으로 기록한다

`marketPrices = historicalPrices + currentPrices`에서 `currentPrices`는 **이미 KRW**로 들어온다(호출자가 환산). 이 자산들은 원통화를 복원할 수 없으므로 **KRW 통화로 계상한다.**

이건 알고 감수하는 부정확이다. 대신 **감수한 만큼을 세어서 로그로 남긴다** — `currentPrices`로 덮인 자산 수를 요약에 싣는다. 이 값이 크면 분해가 환율 효과를 과소평가하고 있다는 뜻이고, 그때 `currentPrices` 공급자까지 원통화를 싣도록 고치는 게 후속 작업이다. 조용히 넘어가면 "환율 기여가 왜 이렇게 작냐"에 답할 근거가 없어진다.

### 실패 처리

`nav_currency_daily` 쓰기 실패가 **스냅샷 생성을 되돌리면 안 된다.** NAV는 핵심 기능이고 통화 분해는 부가 기능이다. `generate()` 커밋 뒤에 별도로 쓰되, 실패는 WARN 로그로 삼키고 `trigger()`는 정상 반환한다. 그날 행이 없으면 §5의 노출 조건이 알아서 블록을 숨긴다.

## 4. 계산 — 일별로 쪼개고 기하 연결

### 두 시점 공식을 쓰면 화면과 안 맞는다

명세의 식을 기간 양 끝에 그대로 적용하면 **화면의 TWR과 어긋난다.** TWR은 입출금을 구간 분할로 걸러내는데 두 시점 차이는 안 그러기 때문이다. 사용자가 보는 `+18.5%`와 분해 합이 다르면 이 블록은 신뢰를 깎는다. 일별 행이 쌓이므로 제대로 할 수 있다.

### 기존 TWR 구간 공식

`ReturnsCalculator.twr()`는 관측일 사이 구간마다 이렇게 계산해 곱한다:

```
r_i = (NAV_i − NAV_{i−1} − net_i) / (NAV_{i−1} + inflow_i)
```

`net_i`는 구간 순플로우, `inflow_i`는 입금만. **분모 ≤ 0인 구간은 `continue`로 건너뛴다**(전액 출금 후 재개 등). 이 건너뜀이 아래 항등식에서 결정적이다.

### 환율을 얼린 평행 계열

구간 `i`에 대해 하나를 더 만든다:

```
A_i = Σ_c v_c(i) · r_c(i−1)      당일 수량·가격, 환율만 전일로 고정
NAV_i = Σ_c v_c(i) · r_c(i)      당일 원화 평가액 (= performance_daily.nav)
```

**실제로는 이 형태로 계산한다** — 대수적으로 같지만 반올림에 강하다:

```
A_i = NAV_i + Σ_c v_c(i) · (r_c(i−1) − r_c(i))
```

`toKrw`가 자산별 가격을 **원 단위로 반올림한 뒤** 수량을 곱하므로 `Σ v_c·r_c`는 `performance_daily.nav`와 정확히 같지 않다. 위 식을 안 쓰면 **환율이 하나도 안 움직인 날에도 환율기여가 0이 아니게 되고**, 250구간을 곱하면 눈에 보일 만큼 쌓인다. 권위 있는 NAV에 얹고 환율 차이만 적용하면 그 항이 상쇄되어, 환율 불변 구간에서 `A_i == NAV_i`가 **정확히** 성립한다.

그리고 같은 분모·같은 플로우로 자산 다리를 만든다:

```
r_asset_i = (A_i − NAV_{i−1} − net_i) / (NAV_{i−1} + inflow_i)
r_fx_i    = (1 + r_i) / (1 + r_asset_i) − 1
```

**환율을 "나머지"로 두지 않는다.** `r_fx_i`를 위 식으로 명시해야 교차항이 자산 쪽에 조용히 흡수되지 않는다. 그리고 이 정의 때문에 구간마다 `(1+r_i) = (1+r_asset_i)(1+r_fx_i)`가 **정의상 성립**한다.

`i`에만 존재하는 통화(신규 매수)는 `r_c(i−1)`이 없다 — 이때 `r_c(i)`를 대신 쓴다. 그 통화의 그날 환율 기여가 0이 되는데, 전일에 보유가 없었으므로 그게 맞다.

### 기간 연결과 항등식

```
자산기여 = Π(1 + r_asset_i) − 1
환율기여 = Π(1 + r_fx_i) − 1
```

곱은 재배열할 수 있으므로:

```
(1+자산기여)(1+환율기여) = Π(1+r_asset_i)·Π(1+r_fx_i) = Π(1+r_i) = 1 + TWR
```

**단, 세 계열이 정확히 같은 구간 집합을 돌아야 한다.** 분모 ≤ 0으로 건너뛰는 구간을 한쪽만 건너뛰면 항등식이 즉시 깨진다. 그래서 **세 계열을 별도 루프로 돌리지 않고 하나의 루프에서 같이 계산한다.**

### 구현 형태 — `ReturnsCalculator`에 붙인다

구간 규약을 복제하면 어느 날 한쪽만 고쳐진다. 같은 객체 안에 함수를 더한다.

두 계열을 별도 리스트로 받으면 어긋날 수 있으므로 한 타입에 묶는다:

```kotlin
/** navAtPriorFx: 당일 보유를 전일 환율로 평가한 값. 첫 관측일은 null(직전 구간이 없다) */
data class NavFxPoint(val date: LocalDate, val nav: BigDecimal, val navAtPriorFx: BigDecimal?)

data class Attribution(val assetContribution: BigDecimal, val fxContribution: BigDecimal)

fun attribute(
    series: List<NavFxPoint>,
    flows: List<Flow>,
    from: LocalDate,
    to: LocalDate,
): Attribution?
```

`attribute()`는 `twr()`와 **같은 필터·같은 정렬·같은 구간 분할·같은 건너뜀**을 쓴다. `nav` 필드만 보면 `twr()`와 동일한 값이 나와야 하고, 이게 테스트로 못박힌다(§7).

`null`을 반환하는 경우:

- 관측이 2건 미만
- 유효 구간이 하나도 없다(전부 분모 ≤ 0)
- **어떤 구간에서 `1 + r_asset_i`이 0에 근접한다** — `r_fx_i`가 발산한다. 자산 다리가 그 구간에 −100%라는 뜻이고, 이때 분해는 정의되지 않는다. 억지 숫자를 내는 대신 블록 전체를 숨긴다. 임계값은 `1e-9`.

### 입출금

`cash_flow`가 KRW로 고정된 건 문제가 되지 않는다 — 입출금은 분모와 분자에 같은 값으로 들어가고 통화별로 배분할 필요가 없다. `GetReturnsAnalysisUseCase`가 만드는 `effectiveFlows`를 그대로 넘긴다.

## 5. 노출 조건

응답에 분해를 싣는 조건:

1. `from ~ to` 구간에 `nav_currency_daily` 관측일이 **2일 이상** 있고,
2. 그중 **KRW 아닌 통화가 하나 이상** 있을 것.

둘 중 하나라도 아니면 `null`이고 FE는 블록 자체를 그리지 않는다.

**임의의 N일이 아니라 "2건"인 이유**: 화면과 대시보드가 이미 "일별 NAV 스냅샷이 2건 이상"이라는 규약을 쓰고 있다(`GetReturnsAnalysisUseCase`가 `series.size < 2`에 `InsufficientDataException`). 새 임계값을 만들면 규약이 둘이 된다.

**'며칠'이 아니라 '관측 몇 건'인 이유**: 스냅샷 리듬이 사용자마다 다를 수 있다. "30일 지났으니 보여준다"는 행이 안 쌓인 사용자에게 빈 화면을 준다.

**안내 문구를 넣지 않는다.** 외화 자산이 없는 사용자에게 "환율 분해를 수집 중입니다"는 영원히 오지 않을 것을 기다리게 한다. 블록이 없는 게 정확하다.

## 6. 읽기 경로

`A_i`는 저장하지 않는다 — **당일 평가액과 전일 환율의 조합이라 읽을 때 만들어진다.** 둘 다 `nav_currency_daily`에 있다.

기존 `JdbcNavHistorySource`(`SELECT date, nav FROM performance_daily`)는 그대로 두고, 옆에 `nav_currency_daily`와 `performance_daily`를 **함께** 읽어 `NavFxPoint` 목록을 만드는 어댑터를 하나 세운다.

- `nav` — `performance_daily`에서 읽은 값을 그대로 쓴다. `Σ v_c·r_c`로 **재계산하지 않는다.** 화면의 TWR이 이 값으로 계산되므로 같은 값을 써야 항등식이 성립한다.
- `navAtPriorFx` — `nav + Σ_c v_c(i)·(r_c(i−1) − r_c(i))`. 통화별 행을 날짜로 묶고 연속한 두 날짜의 환율 차이만 얹는다. 첫 관측일은 `null`.

두 테이블 중 한쪽에만 있는 날짜는 `NavFxPoint`를 만들 수 없다 — **`performance_daily`에 있고 `nav_currency_daily`에 없는 날은 목록에서 뺀다.** §3의 실패 처리가 그런 날을 만들 수 있고, 그 구간을 억지로 이으면 환율 차이가 0으로 잡혀 자산 쪽에 흡수된다. 빼면 §5의 "관측 2건" 조건이 알아서 처리한다.

## 7. 테스트 — 무엇을 못박는가

이 설계의 계약은 넷이다. 각각 테스트가 있어야 하고, **변이를 넣어 실제로 잡히는지 확인한다.**

1. **합계 일치** — `Σ value_native × fx_rate == performance_daily.nav`, **원 단위 허용오차**. 변이: 통화 하나를 빠뜨리면 실패해야 한다.
2. **TWR 일치** — `(1+자산기여)(1+환율기여) − 1 == TWR`. BigDecimal `MathContext(20)` 연산이라 비트 단위로 같지는 않다 — **상대 허용오차 `1e-12`**. 변이: `r_fx`를 "나머지"가 아닌 다른 근사(예: 단순 가중 환율 변화)로 바꾸면 실패해야 한다. **입출금이 있는 시나리오를 반드시 포함한다** — 없으면 이 테스트가 통과해도 아무것도 증명 못 한다.
3. **같은 구간 집합** — 분모 ≤ 0인 구간이 섞인 시계열에서 `attribute()`가 `twr()`와 같은 구간을 건너뛰는지. 변이: 한쪽 루프의 `continue`를 지우면 실패해야 한다. **이게 §4의 항등식을 실제로 지키는 테스트다.**
4. **노출 조건** — 관측 1건, KRW 단일 통화, 외화 포함 2건. 변이: 조건을 `||`로 바꾸면 실패해야 한다.

추가로:

- **`attribute()`의 nav 다리는 `twr()`와 같은 값** — 같은 시계열을 양쪽에 넣어 대조한다. 구간 규약이 갈라지는 순간 잡힌다.
- **환율 고정의 의미** — 수량·가격이 전혀 안 변하고 환율만 오른 구간: 자산기여 0, 환율기여 양수. 이게 무너지면 분해가 의미가 없다.
- **`1 + r_asset ≈ 0`이면 `null`** — 억지 숫자가 안 나오는지.
- **`0`과 `null`의 구별** — AF-104가 자릿수 사고로 배운 것. 기여가 정확히 0인 경우와 데이터가 없는 경우가 화면에서 같아 보이면 안 된다. 응답 타입과 FE 렌더 양쪽에서 확인한다.
- **응답 타입 대조** — AF-104에서 FE 타입이 `string`인데 BE가 JSON 숫자를 보내 스케일이 날아갔다. **실제 페이로드로 확인한다.**
- **쓰기 실패가 스냅샷을 안 되돌린다** — §3의 계약. 변이: 예외를 전파시키면 실패해야 한다.

## 8. API·화면

### API

`ReturnsAnalysis`에 nullable 필드 하나를 더한다. **새 엔드포인트를 만들지 않는다** — 기간 선택기가 이미 이 응답을 갱신하고 있어서, 3M을 누르면 분해도 같이 바뀐다.

```kotlin
data class CurrencyAttribution(
    val assetContribution: BigDecimal,   // 퍼센트 (0~100 스케일, 컨트롤러 경계에서 변환)
    val fxContribution: BigDecimal,      // 퍼센트
    val currencies: List<String>,        // 기간 중 보유한 비-KRW 통화
    val approximatedDays: Int,           // 통화별 행이 없어 분해에서 빠진 관측일 수
)
```

**`approximatedDays`이지 `approximatedAssets`가 아니다.** §3의 `currentPrices` 자산 수는 **쓰기 시점에만** 알 수 있고 저장하지 않으므로 조회 경로에서 복원할 수 없다. 그걸 응답에 실으려면 테이블에 열을 하나 더 만들어야 하는데, 그건 파생값 저장이다. 대신 §6이 이미 세고 있는 것 — `performance_daily`에는 있는데 `nav_currency_daily`에는 없어 목록에서 빠진 날 수 — 을 싣는다. 자산 수는 §3대로 로그로만 남긴다.

비율→퍼센트 변환은 **컨트롤러 경계에서만** 한다 — `ReportController`가 이미 twr/mwr에 그렇게 하고 있다.

### 화면

`app/unified/reports/returns/page.tsx`, 워터폴 "입출금 효과 분해" 섹션 옆에 같은 모양의 `<section className="mt-8 border border-line-card bg-surface-muted p-5">` 하나. 워터폴이 이미 분해 블록이라 시각적 이웃으로 맞다.

```
기간 수익 (TWR)   +18.5%
  ├ 자산          +10.0%
  └ 환율           +7.7%
```

- 자릿수는 페이지의 기존 `fmtPct`를 쓴다. 새 포맷터를 만들지 않는다.
- 부호 색은 기존 `pctColor`(양수 빨강/음수 파랑)를 쓴다.
- `approximatedDays > 0`이면 각주 한 줄로 밝힌다 — 숨기지 않는다.
- `currencyAttribution == null`이면 섹션을 렌더하지 않는다.

## 9. 범위 밖

**`toKrw`가 날짜를 안 받는 문제는 고치지 않는다.** 오늘부터 쓰는 행은 그날 환율이라 맞고, 이건 과거 스냅샷 재계산에만 영향을 준다. `fx_rate`를 행에 남겨 두므로 나중에 고칠 때 대조할 근거가 생긴다. **별건으로 백로그에 올린다.**

**소급 백필을 하지 않는다.** §1에서 확인했듯 자산별 과거 시세가 없어 원리적으로 불가능하다.

**자산 단위 분해를 하지 않는다.** "어느 종목의 환율 효과인가"는 `position_daily`에 통화·외화평가액을 더하면 답할 수 있지만, AF-106이 묻는 건 포트폴리오 수준이다. 필요해지면 그때 붙여도 늦지 않다.

## 10. 착수 전 확인할 것

**`performance_daily`가 실제로 매일 쌓이는가.** 코드상 쓰기 경로는 `GenerateDailySnapshotUseCase` 하나뿐이고 이를 부르는 건 `TradeEventListener`와 `OutboxEventProcessor`(둘 다 거래 이벤트)다. 자정 마감 워크플로우(`ClosingScheduler` → `WfStepExecutor`)가 있으나 **단계 정의가 DB(`wf_step`)에 있어 코드로는 NAV를 쓰는지 알 수 없다.** 화면 문구는 "매일 자정 스냅샷이 쌓입니다"라고 말한다.

계획의 첫 태스크로 프로덕션에서 실측한다. 결과가 "거래일에만 쌓인다"여도 설계는 뒤집히지 않는다 — 통화별 행이 NAV와 같은 리듬으로 쌓이고, §4가 "관측일 사이 구간"으로 쓰여 있어 성기든 촘촘하든 같은 식이 돈다. 다만 그 경우 §5의 "2건" 조건이 실제로 얼마나 자주 만족되는지가 달라지므로, 화면의 기대치를 조정해야 한다.

## 관련

- AF-100 과거 환율 백필 — 재료는 있으나 스냅샷이 안 썼다
- AF-105 환율 출처 표기 — `sourceOf`의 무드리프트 테스트가 §3의 근거
- AF-104 시장 화면 — `0`/`null` 구별, 응답 타입 실측 교훈
- AF-107 벤치마크 비교 — 같은 "데이터가 쌓여야 의미가 생긴다" 처지
