# AF-105 순자산 하단 환율 출처 표기 설계

- 작성일: 2026-08-12
- 선행: AF-99(하나은행 고시환율 수집기), AF-103(수집 스케줄러) — 둘 다 완료·가동 중
- 노션: AF-105 (P1 가시화, 규모 S)

## 목적

다중통화 트래커에서 사용자가 가장 먼저 의심하는 것은 **"무슨 환율로 계산했나"** 다.
증권사 OAuth 연결까지 요구하는 서비스라면 이런 데서 오는 신뢰가 크다.

순자산 바로 아래에, 그 숫자를 만드는 데 실제로 쓰인 환율과 그 출처를 밝힌다.

```
₩ 152,340,000
▲ 1,240,000 (+0.82%)  오늘

원화 환산 · USD 1,383.50  (8/11 32회차 고시)
```

**회차까지 적는 이유**: 하나은행 화면과 직접 대조가 가능해진다. 값이 다르면 사용자가 바로 확인할 수 있다.

## 노션 분류가 틀렸다 — 백엔드가 먼저다

태스크의 영역은 `FE`지만 프론트만으로는 불가능하다. 환율값·기준일·**회차**는 `hana_fx_quote`에만 있고
이를 노출하는 사용자용 API가 없다. 유일한 통로인 `GET /api/admin/fx/usdkrw`는 ADMIN 권한이 필요한 데다
**환율값만 돌려주고 기준일·회차 필드가 아예 없다.** 회차를 적는 근거가 정확히 그 없는 필드에 걸려 있다.

## 이 설계의 핵심 — 분기표를 한 벌만 둔다

출처 표기는 **실제 환산에 쓰인 값과 일치할 때만** 의미가 있다. 일치하지 않으면 신뢰를 만들려던 기능이
반대로 동작한다 — 화면이 틀린 근거를 자신 있게 제시하게 된다.

`CurrencyConverter.toKrw`는 통화별로 서로 다른 소스를 쓴다(USD=하나은행 고시, USDT=거래소 시세,
BTC·ETH=코인 시세). 출처 표기를 별도 함수로 만들면 이 분기표가 두 벌이 되고, 누가 통화를 추가하며
한쪽만 고치는 날 조용히 갈라진다.

그래서 **`sourceOf(currency)`를 유일한 분기표로 두고, `toKrw`가 그 위에 올라탄다.**

```kotlin
fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
    if (currency.uppercase() == "KRW") amount
    else sourceOf(currency)
        ?.let { (amount * it.rate).setScale(0, RoundingMode.HALF_UP) }
        ?: run { log.warn(...); amount }
```

드리프트가 규율이 아니라 구조로 막힌다. 환산에 쓸 환율을 구하는 경로와 화면에 밝힐 환율을 구하는
경로가 같은 코드다.

## 구성

### 1. `FxRateService`에 고시 메타 조회 추가

```kotlin
data class UsdQuoteRef(val rate: BigDecimal, val baseDate: LocalDate, val roundNo: Int)

/** 공식 고시 출처 메타. 고시 기반 구현이 아니면 null. */
fun usdQuoteRef(): UsdQuoteRef? = null
```

**기본 구현을 두는 이유**: 이 인터페이스는 테스트에서 10곳 넘게 익명 객체로 구현돼 있다.
추상 메서드로 추가하면 그 전부가 깨진다. AF-100에서 `FxConverter.toKrwOn`에 썼던 방식과 같다.

`HanaFxRateService`가 오버라이드한다. 그 클래스는 **이미 엔티티 전체를 읽고 `baseRate`만 남기고 버리고
있어서**, 60초 캐시가 담는 값을 `BigDecimal`에서 `UsdQuoteRef`로 바꾸면 DB 조회가 늘지 않는다.

`getUsdToKrw()`도 `usdQuoteRef()` 위에서 구현한다 — 둘이 다른 값을 말할 수 없게.

### 2. `CurrencyConverter.sourceOf`

| 통화 | 환율 출처 | `source` 문구 | baseDate·roundNo |
|---|---|---|---|
| KRW | 환산 없음 | — (null 반환) | — |
| USD (고시 있음) | 하나은행 매매기준율 | `하나은행 매매기준율` | 있음 |
| USD (고시 없음) | USDT 환율 근사 | `고시 없음 · 거래소 시세 근사` | null |
| USDT | 거래소 시세 | `거래소 시세` | null |
| BTC · ETH | 코인 시세 | `코인 시세` | null |
| 그 외 | 환산 안 함 | — (null 반환) | — |

**USD 폴백에서 `getUsdToKrw()`가 아니라 `getUsdtToKrw()`를 부른다.** 이유는 조회 횟수다 —
`getUsdToKrw()`를 부르면 고시 조회가 한 번 더 나가고, 고시가 없는 상태(배포 직후 첫 수집 전)에서는
`HanaFxRateService`가 실패를 캐시하지 않으므로 매 호출마다 DB를 두 번 치게 된다.
인터페이스의 default 구현이 이미 `getUsdToKrw() = getUsdtToKrw()`라 의미는 같다.

**KRW와 미지원 통화가 똑같이 null인 것은 의도다.** 둘 다 "환산이 일어나지 않았다"이고,
일어나지 않은 환산에는 밝힐 출처가 없다.

### 3. `GetDashboardUseCase` → `DashboardResponse.fxSources`

positions + realAssets의 통화를 distinct로 모아 `sourceOf`에 태우고, null을 버린다.

```kotlin
data class FxSourceDto(
    val currency: String,
    val rate: BigDecimal,
    val source: String,
    val baseDate: LocalDate?,
    val roundNo: Int?,
)
```

**조건부 노출이 프론트의 `if`가 아니라 백엔드의 결과가 된다.** 원화 자산만 가진 사용자는 빈 배열을
받고, 프론트는 빈 배열에 아무것도 그리지 않는다. "원화만 있으면 숨긴다"는 규칙을 프론트가 다시
판단하지 않으므로 두 곳이 어긋날 수 없다.

정렬은 통화 코드 사전순으로 고정한다. 자산 구성이 조금 바뀔 때마다 줄 순서가 바뀌면
화면이 불안정해 보인다.

### 4. `NetWorthBar` 렌더

순자산 아래, 작고 흐리게. 보유 통화마다 한 줄.

```
원화 환산 · USD 1,383.50  (8/11 32회차 고시)
원화 환산 · USDT 1,350.00  (거래소 시세)
```

- `baseDate`·`roundNo`가 있으면 `(M/D N회차 고시)`
- 없으면 `(<source>)`
- `fxSources`가 비면 아무것도 렌더하지 않는다

**링크는 넣지 않는다.** 설계 원안은 `/market?tab=fx`로 이동시키지만 그 라우트가 없다(AF-104 대기).
죽은 링크를 만드느니 표기만 하고, 시장 화면을 만들 때 링크를 붙인다.

**폴백 상태를 숨기지 않는다.** 고시가 없어 거래소 시세로 근사한 날에도 줄은 그대로 나오고 문구만
바뀐다. 숨기면 값이 가장 못 미더운 순간에 출처 표기가 사라져, 신뢰가 목적인 기능이 정확히
반대로 동작한다.

## 테스트

- **환율 일치**: 지원 통화마다 `toKrw(1000, c) == (sourceOf(c)!!.rate * 1000).setScale(0, HALF_UP)`.
  화면이 밝히는 환율이 실제 환산에 쓰인 환율과 같음을 고정한다. 이 설계의 전제 그 자체다.
- **KRW·미지원 통화**: `sourceOf`가 null, `toKrw`는 원값 그대로.
- **USD 폴백**: `usdQuoteRef()`가 null인 스텁에서 `source`가 `고시 없음`을 포함하고
  `baseDate`·`roundNo`가 null.
- **`HanaFxRateService`**: 고시가 있으면 `usdQuoteRef()`와 `getUsdToKrw()`가 같은 값을 말한다.
  조회가 실패해도 캐시에 남지 않는다(기존 성질 유지).
- **`GetDashboardUseCase`**: 원화만 보유 → `fxSources` 빈 배열. USD+USDT 보유 → 두 줄, 사전순.
- **프론트**: 빈 배열이면 렌더 없음, `baseDate` 유무로 문구가 갈림.

## 범위 밖

- `/market` 라우트와 시장 화면 (AF-104)
- 환율 이력·차트 — 이 태스크는 "지금 쓰인 값"만 밝힌다
- `fx_rate_daily`(AF-100 과거 환율) 노출 — 대시보드는 현재 평가라 과거 환율을 쓰지 않는다
