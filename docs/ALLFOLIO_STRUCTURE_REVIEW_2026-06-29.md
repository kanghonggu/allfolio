# ALLFOLIO 구조 정리

작성일: 2026-06-29
기준: `allfolio-backend` 코드, 1차 보안 PR 적용 후 상태

## 0. 한 줄 요약

Allfolio 백엔드는 외부 브로커/거래소에서 거래와 자산을 수집하고, 거래 원장(`trade_raw`)을 기준으로 FIFO 손익을 계산한 뒤, 일별 스냅샷 테이블에 집계하고, 조회 API는 주로 미리 저장된 DB/Redis 데이터를 읽는 구조다.

현재 구조는 MSA가 아니라 모듈러 모놀리스다. `backend-app`이 대부분의 도메인 모듈을 한 프로세스로 조립하고, `market-data`만 별도 Spring Boot 앱으로 분리되어 선택적으로 동작한다.

## 1. 모듈 지도

`settings.gradle.kts` 기준 모듈은 다음과 같다.

```text
allfolio-backend
├── backend-app        # API, 인증/보안, 브로커 연동, 스케줄러, 조립 모듈
├── market-data        # 별도 Spring Boot 앱: 실시간 시세 수집, Kafka/DB 적재
├── common             # 공통 도메인 기반
├── portfolio          # 포트폴리오 도메인 모델
├── asset              # 자산 도메인 모델
├── benchmark          # 벤치마크 도메인
├── trade              # 거래 원장, Outbox 저장
├── snapshot           # 일별 포지션/성과/리스크 스냅샷 계산 및 저장
├── risk               # 리스크 계산 도메인
├── esg                # ESG 계산 도메인
├── report             # 리포트 도메인
└── unified-asset      # 통합 자산/계좌/리포트 API와 usecase
```

의존성 방향은 대체로 `common`과 각 도메인 모듈을 `backend-app`이 끌어다 쓰는 형태다.

`backend-app`은 `common`, `portfolio`, `asset`, `benchmark`, `trade`, `snapshot`, `risk`, `esg`, `report`, `unified-asset`에 의존한다. `snapshot`은 특이하게 `trade`와 `risk`에 의존한다. `market-data`는 내부 project 의존성이 없고 별도 앱으로 빌드된다.

정직한 설명:

> 모듈러 모놀리스로 설계했고, 시세 수집만 별도 서비스로 분리할 수 있게 만들었습니다.

피해야 할 설명:

> MSA로 만들었습니다.

## 2. Snapshot

`:snapshot` 모듈은 보고서 파일을 저장하는 모듈이 아니다. 거래 원장(`trade_raw`)을 기준으로 특정 날짜의 포트폴리오 상태를 재계산해서 다음 세 테이블에 저장한다.

- `position_daily`: 자산별 수량, 평균단가, 실현/미실현 손익
- `performance_daily`: NAV, 일간 수익률, 누적 수익률, 벤치마크 수익률, 알파
- `risk_daily`: 변동성, 연환산 변동성, VaR95, MDD

`snapshot`은 `report` 모듈에 직접 의존하지 않는다. 대신 snapshot이 테이블에 쓰고, 조회/리포트 기능이 그 테이블을 읽는다. 구조적으로 write-side 집계와 read-side 조회가 분리된 CQRS 성격이 있다.

스냅샷 생성 트리거는 세 종류다.

- 수동 API: `POST /api/snapshots/daily`
- 거래 저장 후 이벤트: `TradeRecordedEvent`가 `AFTER_COMMIT`으로 처리됨
- Outbox 폴러: 30초마다 PENDING/FAILED 이벤트 재처리

주의할 점:

- “매일 자정 전체 NAV 기록”은 `snapshot`의 전체 재계산이 아니라 `unified-asset`의 `DailyNavScheduler`가 `performance_daily`에 사용자별 NAV를 가볍게 기록하는 경로다.
- `PortfolioSnapshotQueryController`는 1차 보안 PR 이후 query parameter의 `tenantId`를 더 이상 신뢰하지 않고, `X-User-Id`를 tenantId로 고정한다.

## 3. Outbox 패턴

현재 Outbox는 Kafka 발행 보장만을 위한 구조가 아니다. 핵심 목적은 거래 저장 이후 “스냅샷을 생성해야 한다”는 사실을 DB에 남기고, 후속 처리가 실패해도 재시도할 수 있게 하는 것이다.

흐름은 다음과 같다.

1. `RecordTradeUseCase`가 `trade_raw`를 저장한다.
2. 같은 트랜잭션 안에서 `outbox_event`를 PENDING 상태로 저장한다.
3. 커밋 이후 `TradeEventListener`가 즉시 스냅샷 생성을 시도한다.
4. 실패하면 FAILED로 표시한다.
5. `OutboxEventProcessor`가 30초마다 PENDING/FAILED 이벤트를 `FOR UPDATE SKIP LOCKED`로 가져와 재처리한다.
6. 재시도 횟수 초과 시 DEAD로 전이한다.

Outbox 상태값은 다음과 같다.

- `PENDING`
- `PROCESSED`
- `PROCESSED_KAFKA`
- `FAILED`
- `DEAD`

멱등성 장치는 여러 층에 있다.

- 브로커 거래 원장 dedup: `broker_type + external_trade_id` unique index
- 스냅샷 재계산: 해당 날짜 데이터를 지우고 다시 쓰는 방식
- Kafka 소비 경로: `kafka_processed_event` 테이블로 처리 이벤트 기록

주의할 점:

- `kafka.enabled`는 기본값이 false다. Kafka 경로는 선택적이다.
- Outbox status에 스냅샷 처리 상태와 Kafka 전파 상태가 함께 들어가 있어, Kafka 경로를 본격적으로 켤 때 상태 모델을 분리하는 것이 안전하다.
- `OutboxEventProcessor`의 DEAD 전이 경로는 현재 `retryCount`를 증가시켜 저장하지 않는 코드 흐름이 있어 개선 여지가 있다.

## 4. 외부 브로커 연동

브로커 거래 원장 연동은 `BrokerAdapter` 인터페이스로 추상화되어 있다.

```kotlin
interface BrokerAdapter {
    val brokerType: BrokerType
    fun fetchTrades(portfolioId: UUID, accountId: String, cursor: String = ""): BrokerTradeResult
    fun fetchAccounts(userId: UUID): List<BrokerAccountInfo>
}
```

현재 `backend-app`에서 구현된 브로커 거래 원장 어댑터는 5개다.

- Binance
- Toss
- Samsung
- KIS
- Kiwoom

이들은 `BrokerFacade`가 `List<BrokerAdapter>`를 주입받아 `brokerType`으로 라우팅한다. 새 브로커 추가 시 기본 확장 지점은 `BrokerAdapter` 구현체 추가다.

`unified-asset`의 `AccountProvider` enum은 12종이다.

- BINANCE, UPBIT, BITHUMB, COINONE, BYBIT, OKX
- KIS, KIWOOM, STOCK
- WALLET, CSV, MANUAL

다만 이 enum은 “계좌/자산 출처”의 열거값이고, `backend-app`의 브로커 거래 원장 어댑터와 1:1로 같지 않다. 예를 들어 `unified-asset` 쪽 SyncAdapter는 Binance/Upbit/Bithumb/Coinone/Bybit/OKX/Stock/Wallet/CSV/Manual 등에 있고, KIS/Kiwoom은 `backend-app`의 브로커 어댑터로 구현되어 있다.

정직한 설명:

> 자산 출처는 enum으로 넓게 설계했고, 거래 자동 연동은 핵심 브로커부터 단계적으로 구현했습니다. 거래 원장 연동은 현재 5개 브로커 어댑터가 있습니다.

피해야 할 설명:

> 모든 enum provider가 동일한 수준으로 완성되어 있습니다.

## 5. Lot 엔진

핵심 손익 계산 엔진은 `snapshot` 모듈의 `PositionEngine`이다. 이 엔진은 FIFO 기반이다.

동작 요약:

- 매수 거래는 Lot으로 `ArrayDeque`에 쌓는다.
- 매도 거래는 가장 오래된 Lot부터 차감한다.
- 부분 매도는 Lot의 남은 수량을 갱신한다.
- 실현손익은 매도 금액에서 원가와 수수료를 뺀 값이다.
- 미실현손익은 호출자가 주입한 현재가를 기준으로 계산한다.

평균단가 관련 로직은 하나의 전략 엔진으로 통합되어 있지 않고 여러 위치에 흩어져 있다.

- `PositionEngine`: FIFO 기반 일별 스냅샷 계산
- `PositionCacheService`: Redis 포지션 조회용 cost basis 선택
- `StockSyncAdapter`: 주식 데이터 동기화 과정의 평균단가 처리

개선 우선순위가 높은 부분은 `PositionEngine` 단위 테스트다. 현재 핵심 케이스인 부분 매도, 다중 Lot 매도, 보유수량 초과 매도, 수수료 반영을 명확히 고정하는 테스트가 필요하다.

## 6. 읽기 경로와 부하 테스트 후보

주요 대시보드/리포트 조회는 외부 브로커 API를 동기 호출하지 않는다. 대부분 DB와 Redis를 읽는다.

부하 테스트 후보:

- `GET /api/reports/allocation`: 가장 가벼운 축. `ua_assets` 중심 조회.
- `GET /api/portfolios/{id}/positions`: Redis 포지션 캐시 중심 조회.
- `GET /api/unified/dashboard`: 여러 DB 조회를 조합하는 무거운 축. 병목 진단용.

성장 시 주의할 조회:

- `reports/dividend`: 데이터가 늘면 인덱스/기간 조건 관리가 중요하다.
- `monthly-pnl`, `risk`: 전체 히스토리 조회가 커질 수 있어 기간 제한/인덱스 전략이 필요하다.

인증은 stateless JWT다. 부하 테스트에서는 `/api/auth/login`으로 access token을 받은 뒤 `Authorization: Bearer` 헤더를 사용하면 된다. 기본 access token 만료는 15분이다.

## 7. Market-Data

`market-data`는 별도 Spring Boot 앱이다.

역할:

- Binance/KIS WebSocket 시세 수집
- 내부 `InternalPriceEvent` 발행
- Kafka `market.prices` 발행
- `market_price_tick` 테이블 batch insert

통신 방식:

- REST 비즈니스 API 통신은 없다.
- Kafka `market.prices`는 선택적이다.
- `market_price_tick` 테이블은 backend-app과 공유될 수 있다.

주의할 점:

- `backend-app` 안에도 Binance/KIS/Upbit/Bithumb/Coinone/Bybit/OKX WebSocket adapter와 `StockPricePoller`가 있다.
- 따라서 현재는 market-data가 유일한 시세 source of truth라기보다, 별도 수집 서비스로 분리 가능한 선택형 보조 앱에 가깝다.
- 전체 `./gradlew test`는 현재 `market-data/src/main/kotlin/com/allfolio/marketdata/config/AsyncConfig.kt`의 `Val cannot be reassigned` 컴파일 오류로 실패한다. market-data를 손볼 때 가장 먼저 해결해야 한다.

## 8. 인증과 보안

현재 구현된 인증 기초:

- 비밀번호는 BCrypt로 해싱한다.
- JWT secret 기반 access token을 사용한다.
- refresh token은 DB에 SHA-256 hash로 저장한다.
- refresh token rotation이 구현되어 있다.

1차 보안 PR로 보강한 부분:

- `GET /api/unified/accounts/{id}/assets`
  - account가 없거나, 다른 사용자의 account이면 모두 404로 통일한다.
  - 정보 노출을 줄이기 위해 “없음”과 “남의 것”을 구분하지 않는다.
- `GET /api/portfolios/{id}/snapshot/{date}`
  - query parameter의 `tenantId`를 제거하고 `X-User-Id`를 tenantId로 사용한다.
- `GET /api/portfolios/{id}/snapshot/latest`
  - performance latest 조회도 tenantId + portfolioId 조건으로 제한한다.

남은 보안 과제:

- portfolioId 단독 API 소유권 검증
  - `/api/portfolios/{id}/trades`
  - `/api/portfolios/{id}/positions`
  - `/api/sse/pnl/{portfolioId}`
  - `POST /api/trades`
  - `POST /api/snapshots/daily`
- `/api/sse/**`가 현재 permitAll인 문제
- `/api/admin/fx/usdtkrw`의 admin role 검증
- `/api/ai/chat/{jobId}` 계열의 jobId-userId 바인딩
- broker OAuth token, 증권사 API key/secret, AI API key 평문 저장
- `/actuator/**` permitAll 및 health details 공개 범위 축소

## 9. Redis

Redis는 단순 캐시뿐 아니라 운영 보조 인프라로도 쓰인다.

주요 용도:

- 포지션 캐시: `pnl:positions:{portfolioId}`
- 최신 PnL: `pnl:latest:{portfolioId}:{assetId}`
- 최신 시세: `price:latest:{exchange}:{symbol}`
- snapshot cache: `snapshot:{tenantId}:{portfolioId}:{date}`, `snapshot:{tenantId}:{portfolioId}:latest`
- 환율: `fx:usdtkrw`
- 브로커 token cache: `broker:token:{userId}:{brokerType}`
- token refresh lock: `lock:token:refresh:{userId}:{brokerType}`
- 브로커 rate limit
- Redis DLQ

주의할 점:

- 포지션 캐시는 TTL이 없고 DB fallback이 약하다.
- snapshot cache도 TTL이 없어 evict 실패 시 오래된 데이터가 남을 수 있다.
- DLQ는 캐시라기보다 운영 큐에 가까워 Redis 장애 시 영향이 크다.

## 10. 우선순위

| 순위 | 작업 | 상태 | 이유 |
|---|---|---|---|
| 1 | account assets + snapshot tenant 위조 방지 | 완료 | 1차 보안 PR 범위 |
| 2 | portfolio 소유권 원장 및 portfolioId 단독 API 인가 | 다음 작업 | trade/positions/SSE/trade 생성/snapshot 생성 보호 |
| 3 | 민감정보 암호화 | 대기 | broker token, API key/secret, AI API key 평문 저장 해소 |
| 4 | PositionEngine 단위 테스트 | 대기 | 핵심 도메인 계산 로직 회귀 방지 |
| 5 | k6 부하 테스트 | 대기 | allocation, dashboard 기준 실측 근거 확보 |
| 6 | market-data 컴파일 오류 수정 | 대기 | 전체 Gradle test/CI 차단 요인 |
| 7 | 브로커 순차 호출 병렬화 | 대기 | 느린 외부 기관이 전체 sync를 지연시키는 구조 개선 |

## 11. 1차 보안 PR 변경 요약

변경 파일:

- `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/AuthorizationService.kt`
- `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/AccountController.kt`
- `backend-app/src/main/kotlin/com/allfolio/api/portfolio/PortfolioSnapshotQueryController.kt`
- `snapshot/src/main/kotlin/com/allfolio/snapshot/infrastructure/repository/PerformanceDailyJpaRepository.kt`
- `backend-app/src/test/kotlin/com/allfolio/unifiedasset/api/AccountControllerSecurityTest.kt`
- `backend-app/src/test/kotlin/com/allfolio/api/portfolio/PortfolioSnapshotQueryControllerSecurityTest.kt`

검증한 명령:

```bash
./gradlew :backend-app:test --tests 'com.allfolio.unifiedasset.api.AccountControllerSecurityTest' --tests 'com.allfolio.api.portfolio.PortfolioSnapshotQueryControllerSecurityTest'
./gradlew :unified-asset:test :snapshot:test :backend-app:test
./gradlew :backend-app:bootJar -x test
```

전체 `./gradlew test`는 market-data의 기존 컴파일 오류로 실패한다.

## 12. 설명할 때 피해야 할 표현

| 부정확한 표현 | 코드 기준 정정 |
|---|---|
| MSA입니다 | 모듈러 모놀리스이며 market-data만 별도 앱입니다 |
| 11개/12개 기관이 모두 완성되어 있습니다 | provider enum과 실제 구현 수준을 구분해야 합니다 |
| Outbox는 Kafka 발행 보장만을 위한 것입니다 | 현재 핵심은 거래 후 스냅샷 재시도 보장입니다 |
| FIFO와 평균단가가 전략 패턴으로 통합되어 있습니다 | FIFO 엔진은 있고 평균단가성 로직은 여러 곳에 흩어져 있습니다 |
| market-data가 유일한 시세 수집기입니다 | backend-app 내부 시세 수집기도 공존합니다 |
