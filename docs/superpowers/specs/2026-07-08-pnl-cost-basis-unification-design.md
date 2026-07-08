# 손익 원가(cost-basis) 계산 통합 설계

- 날짜: 2026-07-08
- 상태: 승인됨 (구현 전)
- 범위: TradeRaw 기반 FIFO 원가 계산을 하나의 공용 도메인 코어로 통합. pnl 모듈의
  재부팅 후 FIFO 불일치 버그를 먼저 해결하고, snapshot 이전은 뒷 단계로 순차화.

## 배경

원가(cost-basis) 계산이 5곳에 흩어져 있고 두 알고리즘이 섞여 있다.

| # | 위치 | 알고리즘 | 용도 |
|---|---|---|---|
| 1 | `snapshot/PositionEngine` | FIFO (lot 소진 + 실현손익) | TradeRaw 재생, 일별 스냅샷. characterization 테스트 11개 |
| 2 | `pnl/PositionCacheService.applyTrade` | 하이브리드 (lots + 가중평균) | Redis 실시간 캐시. `costBasis()`가 AVG_COST/FIFO 선택 |
| 3 | `pnl/PositionCacheInitializer.initPortfolio` | 순수 이동평균 (lots 없음) | 부팅 시 TradeRaw로 캐시 재구축 |
| 4 | `unified-asset/StockSyncAdapter` | 이동평균 | 수동입력 국내주식(별도 도메인 StockTrade) |
| 5 | `unified-asset/ReportService` | 계산 없음 (purchasePrice 직접) | ua_assets 조회 |

### 해결할 결함

**#2는 lots를 유지하는데 #3은 lots 없이 avgCost만 만든다.** 그래서 서버 재부팅
(초기화기 실행) 후에는 `costBasis(FIFO)`가 lots가 비어 조용히 avgCost로 폴백한다 —
같은 포지션인데 재부팅 전후로 FIFO 원가가 달라지는 잠재 버그. 공개 API
`/api/portfolios/{id}/positions?costMethod=FIFO`가 이 값을 노출한다.

### 스코프 결정

- **목표: 정합성/결함 우선.** pnl 모듈 내부 불일치(#2 vs #3)를 잡고 TradeRaw 기반
  원가 계산(#1·#2·#3)을 공용 코어로 통합.
- **FIFO 유지 + 재부팅 후에도 정확.** 초기화기도 lots를 재생성하는 lot 리스트 모델로
  통일. 공개 API의 FIFO 옵션이 비로소 제대로 동작.
- **순차화.** pnl 쌍(#2·#3)을 코어로 먼저 정합화(버그 해결), snapshot(#1) 재작성은
  뒷 단계로 분리.
- **범위 밖:** unified-asset(#4·#5)은 `trade`에 의존하지 않고 완전히 다른 도메인
  모델(StockTrade)을 쓰므로 이번 통합에서 제외. 별도 과제.

## 공용 코어 (trade 모듈)

`com.allfolio.trade.domain`에 순수 도메인 3종. Spring/Jackson/Redis 의존 없음.

```
data class CostLot(unitPrice: BigDecimal, quantity: BigDecimal)   // 불변 lot

data class LotPosition(lots: List<CostLot>, realizedPnl: BigDecimal)   // FIFO 순서(오래된 것 앞)
   ├ totalQuantity  = Σ lot.quantity
   ├ averageCost    = 잔여 lots 가중평균 (scale 10, HALF_UP; 비면 0)
   ├ fifoCostBasis  = lots.first().unitPrice (비면 null)
   └ companion: EMPTY = LotPosition(emptyList(), ZERO)

object FifoCostEngine
   ├ apply(position: LotPosition, tradeType, quantity, price, fee = ZERO): LotPosition
   └ replay(trades: List<TradeRaw>): LotPosition
```

동작 규칙:

- **BUY**: `CostLot(price, quantity)`를 뒤에 추가. realizedPnl 불변.
- **SELL**: FIFO로 앞 lot부터 소진. 실현손익 = (매도수량 × 매도가) − 소진원가 − fee.
- **초과매도(SELL > 보유)**: 코어는 보유분까지만 소진(clamp)하고 예외를 던지지 않는다.
  브로커 연동은 이력이 불완전할 수 있어(중간 연동 시 BUY 없는 SELL) clamp가 안전.
  초과매도를 데이터 오류로 막으려는 snapshot(#1)은 자기 쪽 사전검증에서 예외를 던지고
  코어엔 정상 범위만 넘긴다 → 정책(거부)과 메커니즘(FIFO 소진)의 분리.
- **정밀도**: averageCost는 scale 10, HALF_UP (기존 #1·#2와 동일).
- **성능**: `replay`는 내부적으로 ArrayDeque 누적(현 PositionEngine과 동일, 긴 이력
  O(n)). `apply`는 작은 현재 lot 리스트 재구성(pnl 포지션은 lot 수 적음). SELL 소진
  헬퍼는 한 벌 공유.

## pnl 리팩터링 (1단계 — 재부팅 버그 수정)

### 매핑 계층 (pnl ↔ 코어)

Redis 직렬화용 `pnl.PositionData`/`pnl.PositionLot`은 하위호환을 위해 그대로 두고,
코어와 변환하는 얇은 매퍼를 추가한다.

- `PositionData → LotPosition`: `lots`를 `CostLot`으로 매핑. **레거시 lot-less 데이터
  (옛 캐시)면 `CostLot(avgCost, quantity)` 단일 lot으로 합성** — 수량 손실 방지 shim.
- `LotPosition → PositionData`: `lots`, `avgCost = averageCost`, `quantity =
  totalQuantity` 투영. realizedPnl·fee는 pnl이 쓰지 않으므로 버린다(YAGNI).

### #2 PositionCacheService.applyTrade

private 메서드 `applyBuy`/`applySellFifo`/`consumeFifo`/`weightedAvgCost` 4개를 제거하고
`기존 PositionData → LotPosition → FifoCostEngine.apply(...) → PositionData` 흐름으로
교체한다. `costBasis()`는 AVG_COST → `averageCost`, FIFO → `fifoCostBasis ?: averageCost`
(lots가 비면 avgCost로 폴백 — 기존 `lots.firstOrNull()?.price ?: avgCost` 동작 보존).
청산(수량 0) 시 기존과 동일하게 Redis field 삭제.

### #3 PositionCacheInitializer.initPortfolio (버그의 핵심)

현재는 이동평균만 계산하고 lots를 만들지 않는다. 이를 `FifoCostEngine.replay(trades) →
PositionData(lots 포함)`로 교체한다. 초기화기가 write path와 동일한 lot 구조를 생성 →
재부팅 후에도 `costMethod=FIFO`가 정확해진다. 내부 `MutablePositionState` 제거.

### 전환 처리

배포 = 앱 재시작 = 초기화기 실행이므로, 배포 시점에 모든 broker-sync 포지션이 lots
포함으로 재구축된다. 그 전 레거시 lot-less 엔트리는 매핑 shim이 안전하게 처리하고
재시작으로 자연 소멸 → 수동 마이그레이션 불필요. (pnl 캐시는 broker_sync_state 포지션
대상. 수동입력 자산은 이 캐시 경로를 쓰지 않는다.)

## snapshot 코어 이전 (2단계 — 위험 순차화)

pnl 수정(1단계)이 커밋·검증된 뒤 별도 커밋으로 진행한다.

`PositionEngine.calculate(trades, marketPrice)`는 시그니처·반환 `PositionSnapshot`을
그대로 유지하고 내부만 교체:

1. 초과매도 사전검증(`sellQty > totalHeld → PositionException.insufficientQuantity`)은
   snapshot에 그대로 남긴다(정책은 snapshot 소유).
2. lot 소진·평균원가·실현손익은 `FifoCostEngine.replay(trades)` 결과에서 투영:
   `totalQuantity`, `averageCost`, `realizedPnl`을 매핑하고 `unrealizedPnl =
   (marketPrice − averageCost) × totalQuantity`만 snapshot이 계산.
3. snapshot 전용 `processSell`/내부 `ArrayDeque`/불변 `snapshot.PositionLot` 제거,
   코어 `CostLot`으로 대체.

안전망: snapshot의 characterization 테스트 11개가 재작성 전후로 동일하게 통과해야 한다.
하나라도 깨지면 코어 로직이 기존 FIFO와 다르다는 뜻이므로 즉시 중단·조사.

초과매도 케이스가 테스트에 있으면 snapshot 사전검증이 먼저 throw하므로 코어 clamp에
도달하지 않아 통과가 유지된다.

## 테스트 전략

### 코어 (신규, TDD)

`trade` 모듈에 `FifoCostEngineTest`. 순수 함수라 fake·mock 불필요, `TradeRaw.reconstruct(...)`로 입력 구성.

- BUY 누적 → totalQuantity / averageCost 가중평균
- SELL FIFO 소진 → 오래된 lot 우선, 잔여 lots, realizedPnl(매도액 − 소진원가 − fee)
- 전량 매도 → lots 비고 averageCost=0, fifoCostBasis=null
- 초과매도 → clamp(보유분까지만 소진, 예외 없음)
- `apply`(증분) 결과 == `replay`(배치) 결과 동치성 (같은 거래열)

### pnl (1단계)

- **재부팅 버그 회귀 테스트(핵심)**: 동일 거래열에 대해 `initPortfolio`(replay) 결과
  `PositionData.lots`와 write-path `applyTrade`(증분 누적) 결과 `lots`가 동일 →
  `costMethod=FIFO` 원가가 재부팅 전후 일치함을 고정.
- 매핑 계층: 레거시 lot-less `PositionData(quantity=100, avgCost=50, lots=[])` →
  `LotPosition` 변환 시 수량 보존(합성 lot), 왕복 후 quantity 유지.
- 청산 시 Redis field 삭제 경로 유지.

### snapshot (2단계)

신규 테스트 없음. 기존 characterization 11개가 재작성 전후 그대로 GREEN인 것이 유일한
합격 기준.

### 전체 검증

`./gradlew test` 통과. 실계좌/실브로커 데이터가 없어 배포 후 검증은 테스트로만 한다.

## 커밋 분리 (위험 순차화)

1. 코어 + `FifoCostEngineTest`
2. pnl 매핑 계층 + `applyTrade` 코어 이전
3. pnl `initPortfolio` 코어 이전(버그 수정) + 재부팅 회귀 테스트
4. snapshot `PositionEngine` 코어 이전 (11개 테스트 GREEN 유지)

각 단계 후 `./gradlew test` 통과 게이트.
