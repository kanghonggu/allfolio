# 일 시세갱신 + NAV 스냅샷 통합 배치 설계

- 작성일: 2026-07-13
- 상태: 설계 승인 대기
- 관련 모듈: `unified-asset`

## 배경 / 문제

매일 자정(KST) `DailyNavScheduler`가 사용자별 NAV를 `performance_daily`에 기록한다.
그런데 스냅샷 직전에 **시세를 새로 가져오지 않고** `ua_assets.current_value`에 저장된
마지막 값을 그대로 합산한다. `ua_assets.current_value`는 사용자가 계좌 상세에서 **수동으로
"Sync"를 눌러야만** 갱신된다(`SyncAccountUseCase` → 어댑터가 KIS 잔고API / Binance 티커 /
FSC·Yahoo에서 최신가 조회).

결과: 사용자가 한동안 Sync를 안 하면 매일 자정 스냅샷이 **같은 정체된 값**으로 쌓인다.
일단위 이력이 의미를 가지려면 스냅샷 시점에 시세가 갱신돼 있어야 한다.

현재 스케줄러 지형(확인 완료):
- `DailyNavScheduler`(00:00 KST): NAV 스냅샷 — 시세 갱신 안 함
- `FxRateScheduler`(60s): 환율 Redis 갱신 → NAV의 KRW 환산은 최신 환율 사용 ✅
- `KrStockRefreshService`(02:00): `kr_stocks` 종목 마스터(심볼/이름)만 갱신 — 가격/자산가치 아님
- backend-app의 WS 어댑터·StockPricePoller·MarketPriceBatchWriter: 별개 market/broker 시스템
  (`market_price` 테이블)용 — **`ua_assets`엔 반영 안 됨**
- **없음: unified-asset 계좌를 주기적으로 재동기화하는 배치** ← 이 스펙이 채운다

## 목표

자정 스냅샷 **직전에** 자동조회 대상 계좌를 전부 재동기화해, 최신 시세 기준으로 일단위
NAV 스냅샷이 쌓이게 한다.

## 비목표 (YAGNI)

- on/off 설정 플래그 (항상 실행 — 사용자 결정)
- 장중 주기 갱신(intraday) — 일 1회로 충분
- rate-limit 스로틀링/병렬화 (단일 사용자 규모, 순차+오류격리로 충분)
- MANUAL·CSV 계좌 자동 갱신 (라이브 시세 없음 → 사용자 입력값 보호 위해 제외)

## 설계

### 아키텍처: 통합 단일 잡
매일 자정(KST) 한 스케줄 진입점에서 **① 전 대상 계좌 재동기화 → ② NAV 스냅샷**을 순차
실행한다. 순서를 한 메서드 안에서 보장하므로 별도 스케줄러 간 경합/타이밍 버그가 없다.

### 컴포넌트

**1. `DailyAccountSyncer` (신규, `unified-asset/application/usecase`)**
- 책임: "자동조회 대상 계좌를 전부 재동기화"하는 순수 유닛.
- 동작:
  - `accountRepository.findByProviders(SYNC_ELIGIBLE_PROVIDERS)`로 대상 계좌 열거
  - 각 계좌에 `syncAccountUseCase.execute(account.id)` 호출
  - **계좌별 오류 격리**: `runCatching` — 한 계좌 실패가 다른 계좌·배치 전체에 영향 없음.
    실패 계좌는 `ua_assets` 기존 값 유지(현행 유지)
  - 순차 처리. 결과 카운트(synced/failed/total) 반환 + 요약 로깅
- `SYNC_ELIGIBLE_PROVIDERS` = `KIS, BINANCE, UPBIT, BITHUMB, COINONE, BYBIT, OKX, WALLET, STOCK`
  (프론트 sync 노출 대상과 동일. `MANUAL·CSV·KIWOOM` 제외 — KIWOOM은 sync 어댑터 없는 수동 라벨)

**2. `DailyNavScheduler` (수정)**
- `@Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")` 유지.
- 메서드 본문: **먼저 `dailyAccountSyncer.syncAll()` → 그 다음 기존 per-currency NAV 스냅샷**.
- 스냅샷 파트는 sync 결과와 무관하게 항상 실행(부분 갱신이라도 스냅샷은 남긴다).
- docstring/로그를 "시세 갱신 후 스냅샷"으로 갱신.

> **왜 명시적 스냅샷 패스를 유지하나 (중복 아님):** `SyncAccountUseCase.execute`는 sync
> 성공 시 이미 전체 사용자 NAV(`findByUserId().navInKrw(fx)`)로 스냅샷을 기록한다. 그리고
> `PerformanceSnapshotService.record`는 `(tenant, portfolio, date)` **UPSERT**라 같은 날
> 재기록은 덮어쓴다. 따라서 명시적 패스는 sync가 찍은 값과 **동일 계산이라 멱등**이다.
> 그럼에도 유지하는 이유는 **안전망**: ①syncable 계좌가 없는 사용자(수동·CSV만 보유)
> ②당일 모든 계좌 sync가 실패한 사용자 — 이들은 sync 부수효과 스냅샷이 없으므로, 명시적
> 패스가 `ua_assets` 마지막 값으로라도 일단위 스냅샷을 보장한다.

**3. `AccountRepository.findByProviders(providers)` (신규 포트 메서드)**
- 전 사용자에 걸쳐 특정 provider들의 계좌를 열거. JPA `findByProviderIn(...)`로 구현.
  (기존 포트엔 `findByUserId`/`findById`만 있어 전체 열거 수단이 없음)

### 데이터 흐름
자정 → 대상 계좌 목록 조회 → 각 계좌 `SyncAccountUseCase.execute`
(어댑터가 최신가 조회 → `ua_assets.current_value` 재기록, full refresh)
→ 갱신된 값으로 per-currency 합산·KRW 환산 → `performance_daily` 스냅샷 기록.

### 오류 처리
- 계좌 sync 실패(토큰 만료·API 오류·네트워크) → 로그 + 스킵, 해당 계좌 기존 값 유지, 배치 계속.
- `DailyAccountSyncer` 전체가 예외로 죽지 않도록 각 계좌를 `runCatching`으로 감싼다.
- 스냅샷 파트는 항상 실행.

## 테스트

- **`DailyAccountSyncer` 단위 테스트**(hand-written fake, 기존 패턴):
  - fake `AccountRepository`가 혼합 provider 계좌(KIS/BINANCE/STOCK + MANUAL/CSV) 반환하도록
    구성 → `findByProviders`에 넘긴 필터대로 **자동조회 대상만** 열거되고 sync 호출되는지,
    MANUAL·CSV는 애초에 조회 대상에서 빠지는지 검증
  - fake `SyncAccountUseCase`가 특정 계좌에서 예외를 던지게 해 **나머지 계좌 sync가 계속되고**
    결과 카운트(failed 반영)가 맞는지 검증
- 스케줄러는 얇게 유지(순서 sync→snapshot). 기존 스냅샷 로직 회귀 없음 확인.

## 미해결/후속
- 미국주식 등 타임존별 종가 정합(현재 KIS 국내주식+크립토라 자정 KST 적절, 후속 고려)
- 다수 사용자 확장 시 rate-limit/병렬화
