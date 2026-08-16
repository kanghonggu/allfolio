# 통화별 행을 모든 NAV 기록 경로에서 남긴다 — 설계

- 작성일: 2026-08-16
- 상태: 설계 확정
- 목적: AF-106 수익 기여도 분해가 **거래 없이도** 동작하게 한다

## 1. 왜 — 기능은 머지됐는데 데이터가 없다

AF-106(PR #167)이 머지·배포됐지만 `nav_currency_daily`가 **0건**이다(2026-08-15 실측). 통화 행을 쓰는 경로가 `SnapshotTriggerService`(거래 이벤트) 하나뿐인데 거래가 드물다 — 최근 15일에 4일.

### 마감 경로에만 더해도 안 된다

읽기 규약이 실패를 닫는 쪽이다:

```kotlin
// JdbcNavFxHistorySource.assemble
if (rows == null || priorRows == null) return@mapIndexed NavFxPoint(date, nav, null)

// ReturnsCalculator.attribute
val frozen = s[seg.i].navAtPriorFx ?: return null   // 구간 하나라도 결측이면 분해 전체 포기
```

`performance_daily`에는 **동기화 경로가 오늘 날짜 행**을 만든다. 그 행에 통화 내역이 없으면 마지막 구간이 null이 되고 분해 전체가 null이 된다. 화면의 기간 프리셋(`1M`·`3M`·`6M`·`YTD`·`1Y`·`SI`)은 **전부 오늘로 끝난다.** 즉 블록이 영영 안 뜬다.

**이건 버그가 아니라 의도한 동작이다.** AF-106 설계가 "억지로 이으면 환율 차이가 0으로 잡혀 자산 쪽에 흡수된다"는 이유로 일부러 이렇게 만들었다. 따라서 고칠 곳은 읽기가 아니라 **쓰기 쪽 커버리지**다.

### 규모

계좌 보유 4명 중 **자산이 있는 사용자는 1명**(자산 9건·통화 2종). 나머지 3명은 자산 0이라 무엇을 해도 볼 것이 없다. 이 작업의 수혜자는 1명이고, 그 1명이 통화 2종을 가져서 분해가 의미를 갖는다.

> 착수 전에 이 숫자를 재지 않고 "3명이 누락된다"고 보고했다가 정정했다. `ua_assets`를 사용자별로 세 보니 3명이 진짜로 0건이었고, `DailyNavScheduler`가 1명만 찍은 것은 정확한 동작이었다.

## 2. 구조 — 쓰기가 한 곳으로 모인다

`performance_daily`에 쓰는 unified-asset 경로는 전부 `PerformanceSnapshotService.record()`를 지난다:

| 호출자 | 언제 |
|---|---|
| `DailyNavScheduler` | 마감 워크플로우 S030 |
| `SyncAccountUseCase` | 계좌 동기화 직후 |
| `AccountController.createAsset` | 수동 자산 등록 |
| `AccountController.importCsv` | CSV 임포트 |

그래서 **`record()`가 총액이 아니라 통화별 내역을 받게** 한다:

```kotlin
fun record(userId: UUID, navByCurrency: Map<String, BigDecimal>, date: LocalDate)
```

타입이 호출자에게 통화 내역을 강제하므로 **새 호출자가 생겨도 빠뜨릴 수 없다.** 총액은 `record()`가 스스로 계산하므로 두 테이블이 같은 숫자에서 나온다.

`DailyNavScheduler`는 이미 `SELECT user_id, currency, SUM(current_value) ... GROUP BY user_id, currency`로 갖고 있다 — 지금은 접어서 버린다. 나머지 셋은 `NavCalculator.kt`에 확장 하나를 더한다:

```kotlin
fun Collection<Asset>.navByCurrency(): Map<String, BigDecimal>
```

**같은 파일의 `navInKrw` KDoc이 "통화를 무시하고 raw 합산하면 KRW와 USD를 그대로 더해 의미 없는 숫자가 나온다"고 경고한다.** 새 함수는 통화로 묶은 *뒤에* 합산하므로 그 경고에 걸리지 않는다 — 그 이유를 KDoc에 적어 두지 않으면 다음 사람이 `navInKrw`의 경고를 보고 이 함수를 "고치려" 든다.

## 3. 환율을 밝히는 포트 메서드

`FxConverter.toKrw`는 환산 금액만 돌려주고 환율을 안 밝힌다. **`toKrw(1, c)`로 역산할 수 없다** — 어댑터가 감싸는 `CurrencyConverter.toKrw`가 `setScale(0, HALF_UP)`을 해서 환율 1400.5가 1401이 된다.

포트에 더한다:

```kotlin
/** 이 통화를 KRW로 바꿀 때 쓰는 환율. KRW와 미지원 통화는 1. */
fun rateOf(currency: String): BigDecimal
```

어댑터 구현은 `currencyConverter.sourceOf(currency)?.rate ?: BigDecimal.ONE`.

**거래 경로가 이미 쓰는 식과 같다** — `NavCurrencyDailyStore.aggregate`의 `rateOf` 람다가 정확히 그 표현이다. 두 경로가 같은 규약으로 환율을 얻는다는 것이 이 설계에서 중요하다. AF-105가 "`sourceOf`가 밝히는 환율은 `toKrw`가 실제로 쓴 환율과 같다"를 테스트로 못박아 뒀고, 그 테스트가 여기서도 합계 불변식을 지켜 준다.

**미지원 통화가 1인 것도 그대로 따른다.** `CurrencyConverter`가 환산하는 통화는 `KRW·USD·USDT·BTC·ETH` 다섯뿐이고 나머지는 원금을 그대로 돌려준다. 예외를 던지지 않고 그 동작을 기록해야 불변식이 성립하고, `currency <> 'KRW' AND fx_rate = 1`인 행이 미환산 자산의 진단 지표가 된다.

## 4. 통화 행 쓰기 포트

모듈 의존은 **단방향**이다 — `backend-app → unified-asset`. 따라서 `PerformanceSnapshotService`(unified-asset)는 `NavCurrencyDailyStore`(backend-app)를 직접 부를 수 없다.

unified-asset에 포트를 두고 backend-app이 기존 스토어에 위임한다:

```kotlin
/** 통화별 일간 평가액 저장 포트 (AF-106). 구현은 backend-app이 NavCurrencyDailyStore로 위임한다. */
interface NavCurrencyStore {
    fun replace(portfolioId: UUID, date: LocalDate, values: List<CurrencyValue>)
}
```

**SQL 소유자를 하나로 유지하는 것이 요점이다.** `PerformanceSnapshotService`가 이미 원시 JDBC를 쓰고 있어서 INSERT를 복제하는 쪽이 짧지만, 그러면 같은 테이블에 쓰는 코드가 두 벌이 되고 스키마가 바뀌는 날 한쪽만 고쳐진다. 포트·어댑터는 이 저장소의 확립된 관례다(`FxConverter`·`NavHistorySource`·`RateSource`·`HistoricalRateSource`).

`CurrencyValue`(currency·valueNative·fxRate)는 현재 backend-app에 있다. 포트 시그니처에 필요하므로 **unified-asset으로 옮기고** backend-app이 그것을 쓴다. 옮기는 것이지 복제하는 것이 아니다.

## 5. 산술 — 무엇이 얼마나 달라지나

### 합계 불변식은 "정확"이 아니라 "1원 이내"다

`nav = Σ_c toKrw(v_c, c)`이고 `toKrw`가 통화마다 `setScale(0, HALF_UP)`을 하므로, `Σ v_c × rate_c`와는 **통화당 최대 0.5원** 벌어진다. 통화 2종이면 1원 이내다.

거래 경로의 허용오차(`0.5 × Σ|quantity|`)보다 훨씬 타이트하지만 정확하지는 않다. **정확하게 만들려고 `nav` 계산식을 바꾸지 않는다** — 그러면 저장된 NAV 값이 달라지고, 얻는 것은 1원인데 잃는 것은 기존 값과의 연속성이다.

### 동기화 경로의 NAV가 미세하게 바뀐다 (의도된 변화)

지금 `SyncAccountUseCase`·`AccountController`는 `navInKrw`를 쓴다 — **자산별** 환산 후 합산(`fold { acc + asset.currentValueInKrw(fx) }`). 통화별 내역을 넘기게 되면 **통화별** 환산 후 합산이 된다.

`DailyNavScheduler` KDoc이 이미 이 차이를 적어 뒀다: *"자산별 환산 합과 통화별 환산 합은 KRW 라운딩 방식 차이로 수 원 다를 수 있으나, 통화별 1회 환산인 이 패스 값이 더 정확하다."*

그러므로 이 변화는 **개선이고, 부수적으로 두 경로의 산술이 통일된다.** 다만 저장된 NAV 값이 수 원 움직이는 것은 사실이므로 명시해 둔다.

## 6. 실패 처리 — 한 트랜잭션으로 묶는다

거래 경로는 통화 행 쓰기 실패를 `try/catch`로 삼킨다. 스냅샷이 이미 커밋된 뒤라 가능한 방식이다.

**여기서는 그 방식이 성립하지 않는다.** `SyncAccountUseCase.record()` 호출은 `@Transactional` 안이고, Postgres는 트랜잭션 안에서 SQL 오류가 나면 그 트랜잭션을 abort 상태로 만든다. 예외를 잡아도 트랜잭션은 이미 죽어 있어서 이어지는 커밋이 실패한다.

**선택: 둘을 한 트랜잭션으로 묶는다.** 통화 행 쓰기가 실패하면 NAV 행도 안 남는다.

근거는 둘이다.

첫째, **NAV만 있고 통화 내역이 없는 상태가 정확히 AF-106이 null을 내는 상태다.** 그런 행을 남기느니 둘 다 없는 편이 깨끗하다 — `assemble`이 `navByDate` 키를 기준으로 점을 만들므로, 행이 아예 없으면 그 날짜는 계열에서 자연히 빠지고 구멍이 아니라 관측 없음이 된다.

둘째, **실제 실패 원인은 대개 "테이블 없음"**(마이그레이션 미적용)인데, 그건 조용히 넘어가면 안 되는 종류다. 마감 워크플로우가 정확히 그것 때문에 몇 달을 조용히 죽어 있었다.

**감수하는 것**: 통화 행 문제가 계좌 동기화를 실패시킬 수 있다. 보조 기능이 핵심 기능을 막는 구조이므로, 이건 알고 받는 대가다. `REQUIRES_NEW`로 분리하는 대안은 커넥션을 하나 더 쓰면서 "NAV만 남는" 상태를 다시 만들어 내므로 택하지 않는다.

## 7. 테스트 — 무엇을 못박나

1. **`record()`가 두 테이블에 같은 날짜로 쓴다.** 변이: 통화 행 쓰기를 지우면 실패해야 한다
2. **합계 불변식** — `Σ value_native × fx_rate`가 기록된 `nav`와 1원 이내. 변이: 통화 하나를 빠뜨리면 실패해야 한다
3. **미지원 통화가 `fx_rate = 1`로 기록된다** — 예외를 던지지 않는다. 변이: `rateOf`가 미지원 통화에 예외를 던지게 하면 실패해야 한다
4. **`navByCurrency()` 확장이 통화별로 묶는다** — 대소문자 정규화 포함
5. **어댑터의 `rateOf`가 `toKrw`가 쓴 환율과 같다** — AF-105의 무드리프트 테스트와 같은 성격. `toKrw(v, c) ≈ v × rateOf(c)`를 통화별로 확인

**개발 머신 TZ 함정을 다시 밟지 말 것.** 이 저장소에서 시간대 관련 테스트가 로컬 KST 때문에 변이를 못 잡은 사례가 있다. 날짜가 관련된 단언은 오늘일 수 없는 고정 날짜를 쓴다.

## 8. 범위 밖

- **읽기 규약을 완화하지 않는다.** "통화 내역 없는 날은 분해 포기"는 AF-106의 의도된 실패-닫힘이다
- **`nav` 계산식을 정확한 불변식을 위해 바꾸지 않는다**(§5)
- **`SnapshotController`(`POST /api/snapshots/daily`)는 손대지 않는다.** `marketPrices`를 요청 본문으로 이미 KRW로 받아 원통화를 복원할 수 없다. 전부 KRW로 기록하면 그날 환율 기여가 0으로 잡혀 자산 쪽에 흡수되므로, 행이 없어 분해를 포기하는 편이 정직하다
- **`toKrw`가 날짜를 안 받는 문제**는 그대로 둔다(AF-106 설계에서 범위 밖으로 둔 것)

## 관련

- AF-106 수익 기여도 분해 — 이 작업이 그 기능의 데이터 공급원이다
- AF-105 환율 출처 표기 — `sourceOf`의 무드리프트 테스트가 §3의 근거
- 일별 마감 트리거 — `DailyNavScheduler`가 이 경로의 주 호출자다
