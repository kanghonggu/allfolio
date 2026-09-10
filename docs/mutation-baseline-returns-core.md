# 수익률 코어 변이 기준선

**AF-198** · 측정일 2026-09-10 · 기준 커밋 `0dec46e` (`origin/main`)

금지 목록 Tier 2-2가 수익률 코어 변경을 막고 있고 해제 조건은 *"테스트가 이 영역을 커버"*다.
그런데 **지금 있는 테스트가 실제로 무엇을 잡는지 아무도 몰랐다.** 참조 개수는 커버리지가 아니다 —
2026-09-08 AF-193에서 fake 저장소 테스트가 초록인 채로 경로 하나가 롤백에 쓸려 사라지고 있었고,
사람 리뷰와 CI를 둘 다 통과했다.

메우기 전에 쟀다. 안 재고 테스트를 더하면 **무엇을 메웠는지 말할 수 없다.**

## 어떻게 쟀나

AF-198이 **지정한 변이 31개**를 그대로 썼다. 스스로 고르지 않았다 — 변이를 고르는 쪽이
무해한 변이를 넣으면 표가 조용히 오염된다.

각 변이마다: 적용 → `allfolio-backend/gradlew test`(전 모듈) → 실패 테스트 수집 → `git checkout` 원복.
하네스가 죽어도 원복되도록 `try/finally`로 감쌌다.

- **판정 기준**: 기존 테스트가 하나라도 빨개지면 **잡힘**. 전부 초록이면 **안 잡힘**
- `FCE-M1`은 무한 루프를 만든다. macOS엔 `timeout`이 없어 `perl -e 'alarm N; exec @ARGV'`로 끊었다
- **운영 코드 diff 0줄** — 측정 후 `git diff --stat`가 비어 있음을 확인했다

## 결과 요약

| | 건수 |
| --- | --- |
| 잡힘 | **19** |
| 안 잡힘 | **12** |
| 합계 | 31 |

영역별로 갈린다:

| 영역 | 잡힘 / 전체 |
| --- | --- |
| FIFO lot (`FifoCostEngine`·`LotPosition`) | **7 / 7** |
| external flow (`CashFlow`·`CashFlowRecomputeService`) | 6 / 8 |
| TWR/MWR (`ReturnsCalculator`) | 5 / 7 |
| 월별 실현손익 (`FifoRealizedPnlCalculator`) | 1 / 4 |
| 일별 성과 (`DailyPerformanceEngine`) | **0 / 4** |

---

## 1. `snapshot/…/domain/DailyPerformanceEngine.kt` (94줄)

**참조 테스트 0건.** 네 변이 전부 안 잡힌다.

| # | 위치 | 변이 | 결과 | 깨진 테스트 |
| --- | --- | --- | --- | --- |
| M1 | `computeDailyReturn` | `.subtract(externalCashFlow)` 제거 | 🔴 **안 잡힘** | — |
| M2 | `computeCumulativeReturn` | `multiply` → `add` (기하 연결을 산술로) | 🔴 **안 잡힘** | — |
| M3 | `calculate` | 전일 NAV 0 가드 무력화 | 🔴 **안 잡힘** | — |
| M4 | `calculate` | 알파 부호 반전 | 🔴 **안 잡힘** | — |

> M3은 블록을 지우면 컴파일이 깨져 "변이"가 아니라 "빌드 실패"가 되므로, 조건을
> `if (false && …)`로 바꿔 **가드가 절대 안 걸리게** 했다. 의미는 같고 컴파일은 된다.

## 2. `report/…/returns/ReturnsCalculator.kt` (271줄)

| # | 위치 | 변이 | 결과 | 깨진 테스트 |
| --- | --- | --- | --- | --- |
| M1 | `segments` | 분모의 입금 보정 제거 | ✅ 잡힘 | `AttributionTest > 분모가 0 이하인 구간을…` · `ReturnsCalculatorEdgeTest > 기말 관측일 당일 플로우는 포함한다` |
| M2 | `segments` | 구간 창 경계를 옆으로 (`>=` / `<`) | ✅ 잡힘 | `GetDashboardUseCaseReturnsTest > 계좌 연동 초기 편입은 수익이 아니다 - QA +2060% 재현 케이스` |
| M3 | `twr` | `product - ONE` → `product` | ✅ 잡힘 | 위 케이스 + `> 윈도우 시작 이전의 마지막 관측을 기저로 쓴다` |
| M4 | `xirrPeriodReturn` | 초기 NAV 부호 반전 | ✅ 잡힘 | `ReturnsCalculatorTest` 3건(`xirr converges to known answer` 등) |
| M5 | `xirrPeriodReturn` | 연환산 `/365` → `/360` (두 곳) | 🔴 **안 잡힘** | — |

## 3. `unified-asset/…/ReturnsReportGenerator.kt` (101줄)

> 🔴 **티켓과 실제 위치가 다르다.** M2·M3은 `ReturnsReportGenerator`가 아니라
> `ReturnsCalculator.periodTwrPercent`(97줄·95줄)에 있다. 지어내지 않고 **코드가 있는 자리**에
> 넣고 그 사실을 여기 적는다.

| # | 실제 위치 | 변이 | 결과 | 깨진 테스트 |
| --- | --- | --- | --- | --- |
| M1 | `ReturnsReportGenerator:61` | `signedKrw()` → `amountKrw` (출금 부호 소실) | 🔴 **안 잡힘** | — |
| M2 | `ReturnsCalculator:97` | `?.multiply(BigDecimal(100))` 제거 | ✅ 잡힘 | `ReturnsCalculatorTest > periodTwrPercent - percent 스케일로 반환한다` 외 1 |
| M3 | `ReturnsCalculator:95` | 앵커 `sorted.last` → `sorted.first` | 🔴 **안 잡힘** | — |

## 4. `unified-asset/…/domain/cashflow/CashFlow.kt` (80줄)

| # | 위치 | 변이 | 결과 | 깨진 테스트 |
| --- | --- | --- | --- | --- |
| M1 | `signedKrw()` | 출금이 입금처럼 (`negate()` 제거) | ✅ 잡힘 | `CashFlowTest > signedKrw는 외부흐름만 부호를 갖고 내부는 0` 외 1 |
| M2 | `signedKrw()` | 내부이동이 외부 기여로 | ✅ 잡힘 | `GetDashboardUseCaseNetWorthChangeTest > 내부 이체는 외부 유입이 아니므로…` |
| M3 | `create` | 금액 양수 검증 제거 | 🔴 **안 잡힘** | — |
| M4 | `transferPair` | 출발=도착 검증 제거 | ✅ 잡힘 | `CashFlowTest > transferPair 같은 계좌면 예외` 외 1 |
| M5 | `create` | `currency.uppercase()` → `currency` | 🔴 **안 잡힘** | — |

## 5. `backend-app/…/fx/CashFlowRecomputeService.kt` (190줄)

> M3(드라이런이 쓰기를 한다) 적용 전에 확인함: `CashFlowRecomputeServiceTest`는
> `CashFlowJpaRepository`를 **목으로** 쓴다. 실 DB에 닿지 않으므로 안전하다.

| # | 위치 | 변이 | 결과 | 깨진 테스트 |
| --- | --- | --- | --- | --- |
| M1 | 변경 없음 판정 | `== 0` → `!= 0` | ✅ 잡힘 | `CashFlowRecomputeServiceTest` 4건 |
| M2 | delta 계산 | 부호 반전 | ✅ 잡힘 | `> 보고서에 변동 폭 상위가 담긴다` 외 1 |
| M3 | 적용 게이트 | 드라이런이 쓰기를 한다 | ✅ 잡힘 | `> 드라이런은 저장하지 않는다` |

## 6. `trade/…/domain/FifoCostEngine.kt` (89줄)

| # | 위치 | 변이 | 결과 | 깨진 테스트 |
| --- | --- | --- | --- | --- |
| M1 | `replay` 루프 | 소진된 lot이 안 빠짐 | ✅ **잡힘(멈춤)** | 테스트가 끝나지 않는다 — 아래 주 참고 |
| M2 | `sell` | 초과매도 clamp 붕괴 | ✅ 잡힘 | `FifoCostEngineEquivalenceTest` 4건 |
| M3 | `sell` | 수수료를 안 뺀다 | ✅ 잡힘 | `FifoCostEngineEquivalenceTest` 4건 |
| M4 | `apply` BUY | FIFO 순서 반전 | ✅ 잡힘 | `FifoCostEngineEquivalenceTest` 2건 + `FifoCostEngineTest > apply incrementally equals replay in batch` |

> **M1은 "빨개진다"가 아니라 "안 끝난다"로 잡힌다.** 수량 0인 lot이 앞에 남으면 `consumed`가
> 0이라 `remaining`이 줄지 않고, 초과매도면 `deque`도 안 빈다. CI에서는 잡 타임아웃으로
> 빨개지므로 회귀는 막히지만, **실패 모드가 "무한 루프"라는 점은 기록해 둘 값어치가 있다.**

## 7. `trade/…/domain/LotPosition.kt` (35줄)

| # | 위치 | 변이 | 결과 | 깨진 테스트 |
| --- | --- | --- | --- | --- |
| M1 | `averageCost` | 수량 0 가드 제거 | ✅ 잡힘 | `PositionEngineTest > full sell realizes pnl and leaves zero quantity` |
| M2 | `averageCost` | 반올림 → 버림 | ✅ 잡힘 | `PositionEngineTest > average cost is rounded to scale 10 half up` |
| M3 | `fifoCostBasis` | FIFO → LIFO | ✅ 잡힘 | `PositionDataMapperTest > PositionData with lots maps to LotPosition preserving lot prices` |

## 8. `unified-asset/…/FifoRealizedPnlCalculator.kt` (45줄)

| # | 위치 | 변이 | 결과 | 깨진 테스트 |
| --- | --- | --- | --- | --- |
| M1 | 정렬 | 같은 날 타이브레이크 소실 | 🔴 **안 잡힘** | — |
| M2 | `calculate` 필터 | 종료일 당일 제외 | 🔴 **안 잡힘** | — |
| M3 | `monthRealized` 반환 | 기간 차분 소실 | ✅ 잡힘 | `FifoRealizedPnlCalculatorTest > 이전월 매도는 당월에 포함되지 않는다` |
| M4 | `monthRealized` | 시작일 당일이 이전으로 | 🔴 **안 잡힘** | — |

## 9. `backend-app/…/pnl/PositionLot.kt` — 변이 지점 없음

22줄 데이터 홀더. `price`·`quantity`·`purchasedAt`만 갖고 계산 로직이 없다.

## 10. `backend-app/…/pnl/CostBasisMethod.kt` — 변이 지점 없음

14줄 enum(`AVG_COST`, `FIFO`). 분기가 이 파일에 없다.

**소비처의 분기 위치**(이 태스크 범위 밖이라 변이는 넣지 않음, 후속 입력용):

```
backend-app/src/main/kotlin/com/allfolio/pnl/PositionCacheService.kt:147-152
    fun costBasis(data: PositionData, method: CostBasisMethod): BigDecimal =
        when (method) {
            AVG_COST -> position.averageCost
            FIFO     -> position.fifoCostBasis ?: position.averageCost
        }

backend-app/src/main/kotlin/com/allfolio/api/portfolio/PortfolioQueryController.kt:106
    val basis = service.costBasis(p, method)   // ?costMethod= 쿼리 파라미터로 들어온다
```

`FIFO`가 null이면 `AVG_COST`로 **조용히 폴백**한다. 두 방식이 같은 값을 내는 상황에서는
분기가 바뀌어도 아무도 모른다 — 후속 태스크에서 볼 자리다.

---

# 🔴 "안 잡힘" 12건 — 위험 순

후속 태스크(AF-199 TWR·external flow / AF-200 FIFO lot)의 입력이다.
**위험 = 틀렸을 때 사용자가 보는 숫자가 얼마나 크게 어긋나는가 × 눈으로 알아챌 수 있는가.**

## 1군 — 값이 크게 틀리고 티도 안 난다

| 순위 | 변이 | 무엇이 틀리나 |
| --- | --- | --- |
| 1 | **DPE-M1** 외부현금흐름 조정 제거 | **입금이 그날 수익으로 잡힌다.** 1,000만 원을 넣은 날 수익률이 폭등한다. QA에서 실제로 나왔던 `+2060%` 부류의 결함이고, `ReturnsCalculator` 쪽은 그 케이스를 무는데 **이 엔진은 아무도 안 문다** |
| 2 | **DPE-M2** 기하 연결을 산술로 | 누적 수익률이 구간 수를 늘릴수록 틀어진다. 하루짜리로는 차이가 안 보이고 **기간이 길수록 벌어져** 발견이 늦다 |
| 3 | **RRG-M1** 출금 부호 소실 | 출금이 입금으로 잡혀 **수익률 보고서 전체**가 틀어진다. `CashFlow.signedKrw()` 자체는 `CF-M1`이 무는데, **그걸 호출하지 않고 `amountKrw`를 직접 쓰는 경로**는 아무도 안 문다 |
| 4 | **DPE-M4** 알파 부호 반전 | 벤치마크 대비 초과수익의 부호가 뒤집힌다. 지수를 이겼는지 졌는지가 반대로 보인다 |

## 2군 — 경계에서만 틀린다

| 순위 | 변이 | 무엇이 틀리나 |
| --- | --- | --- |
| 5 | **RRG-M3** 커버리지 앵커를 앞으로 | 기간 수익률의 기저가 cutoff 이전 **가장 오래된** 관측이 된다. 시계열이 길수록 크게 틀어지고, 짧으면 우연히 맞는다 |
| 6 | **FRP-M4** 시작일 당일이 이전으로 | 월초 첫날 거래의 실현손익이 **전월로 새어 나간다** |
| 7 | **FRP-M2** 종료일 당일 제외 | 월말 마지막 날 거래가 **그달에서 빠진다**. M4와 반대쪽 끝 |
| 8 | **DPE-M3** 전일 NAV 0 가드 무력화 | 계좌 개설 첫날 0으로 나누기. 예외가 나거나 무한대가 저장된다 |
| 9 | **CF-M3** 금액 양수 검증 제거 | 음수·0 금액 현금흐름이 저장된다. 화면 검증은 있으나 **도메인 불변식이 뚫린다** |

## 3군 — 실무 영향이 좁다

| 순위 | 변이 | 무엇이 틀리나 |
| --- | --- | --- |
| 10 | **FRP-M1** 같은 날 타이브레이크 소실 | 같은 날 매수·매도가 섞인 종목에서 FIFO 순서가 흔들린다. 실현손익이 조금 달라진다 |
| 11 | **RC-M5** 연환산 365 → 360 | MWR이 약 1.4% 상대오차. **부호나 자릿수가 안 바뀌어** 눈으로는 절대 안 보인다 |
| 12 | **CF-M5** 통화 대문자화 제거 | `usd`와 `USD`가 다른 통화로 갈린다. 입력 경로가 대문자를 보장하면 안 터진다 |

## 후속 태스크 배분 제안

| 태스크 | 맡을 변이 |
| --- | --- |
| **AF-199** (TWR·external flow) | DPE-M1·M2·M3·M4 · RRG-M1·M3 · RC-M5 · CF-M3·M5 |
| **AF-200** (FIFO lot) | FRP-M1·M2·M4 |

`DailyPerformanceEngine`이 **단독으로 4건**이고 전부 1·2군이다. 참조 테스트가 0건이라
테스트 파일 자체가 없고, AF-199에서 가장 먼저 손댈 자리다.

---

## 이 표를 읽을 때 주의

- **"잡힘"이 곧 "잘 덮여 있다"는 아니다.** 어떤 변이는 우연히 다른 단언에 걸릴 수 있다.
  이 표가 말하는 것은 *"이 한 줄을 망가뜨리면 빨개지긴 한다"*까지다
- **"안 잡힘"이 곧 "버그"는 아니다.** 지금 코드는 맞다. 안 잡힌다는 것은
  **누가 그 줄을 잘못 고쳐도 아무도 안 말려 준다**는 뜻이다
- 측정은 `origin/main = 0dec46e` 기준이다. 그 뒤 테스트가 늘면 결과가 달라진다 —
  다시 재려면 하네스를 다시 돌릴 것
