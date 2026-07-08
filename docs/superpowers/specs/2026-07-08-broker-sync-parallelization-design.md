# 브로커 동기화 병렬화 설계

- 날짜: 2026-07-08
- 상태: 승인됨 (구현 전)
- 범위: `BrokerSyncScheduler`의 순차 계좌 동기화를 브로커 타입별 그룹 병렬로 전환

## 배경과 목표

`BrokerSyncScheduler.syncAll()`은 60초 `fixedDelay`로 `broker_sync_state` 전체를
순차 forEach로 돌며 `BrokerFacade.syncAccount()`(네트워크 I/O)를 호출한다.
한 브로커의 API가 느리면 뒤에 있는 다른 브로커 계좌들이 전부 밀린다.

목표: **서로 다른 브로커는 병렬로, 같은 브로커의 계좌들은 순차로** 동기화한다.
브로커별 rate limit(초당 2~5회, Redis INCR 기반 non-blocking)과 충돌하지 않는
가장 작은 병렬화 축이다.

검증은 테스트로만 한다. 운영 `broker_sync_state`는 현재 비어 있고 실브로커
계좌가 없으므로, fake 기반 단위 테스트가 유일한 검증 수단이다.

## 검토한 대안

1. **브로커 타입별 그룹 병렬 (채택)** — 동시성 상한이 브로커 수(5)로 구조적으로
   제한되고 rate limit과 충돌 없음.
2. 계좌 단위 전면 병렬 — 같은 브로커 계좌가 동시에 나가면 rate limiter에 걸려
   skip만 늘어남. 이득이 제한적이라 기각.
3. Kotlin 코루틴 — 프로젝트에 코루틴 의존성이 전혀 없어 이 한 곳 때문에 도입하지
   않음(기각). Spring `@Async` — self-invocation 프록시 함정으로 빈 분리가 강제되고
   결과적으로 채택안과 동일한 것을 더 복잡하게 얻음(기각).

## 설계

### 컴포넌트

| 항목 | 종류 | 내용 |
|---|---|---|
| `BrokerAccountSyncer` | 신규 인터페이스 (`com.allfolio.broker`) | `fun syncAccount(brokerType: BrokerType, portfolioId: UUID, accountId: String): Int` — 기존 `BrokerFacade.syncAccount` 시그니처 그대로 |
| `BrokerFacade` | 수정 | `: BrokerAccountSyncer` 구현 선언만 추가, 본문 변경 없음 |
| `BrokerSyncExecutorConfig` | 신규 설정 | `@Bean(destroyMethod = "shutdown") fun brokerSyncExecutor(): ExecutorService` — 고정 풀 크기 `BrokerType.entries.size`, 스레드명 `broker-sync-N` |
| `BrokerSyncScheduler` | 수정 | 의존성 `BrokerFacade` → `BrokerAccountSyncer` + `ExecutorService`. 필터링 후 `groupBy(brokerType)` → 그룹당 `Callable` 제출 → `invokeAll` join |

### syncAll() 흐름

1. `syncStateRepository.findAll()` — 기존과 동일
2. 필터 (기존 로직 보존): unknown brokerType 경고 후 제외, BINANCE 제외(레거시
   경로), `lastSyncedAt` 30초 이내 skip
3. 남은 states를 `groupBy(brokerType)`
4. 그룹마다 `Callable` 1개 제출 — 내부는 기존 forEach 본문 그대로 (계좌 단위
   `runCatching`, recorded/-1 로깅, 커서 갱신은 Facade가 담당)
5. `executor.invokeAll(tasks)` — 전 그룹 완료까지 스케줄러 스레드 대기
6. `Future.get()`에서 그룹 수준 예외를 잡아 브로커명과 함께 error 로그

### 보존되는 시맨틱

- `fixedDelay = 60s` 무겹침: `invokeAll` join으로 다음 틱과 절대 겹치지 않음
- 계좌 단위 실패 격리, rate limit skip(-1), 커서/카운트 갱신 — 기존 코드 이동만
- states가 비거나 그룹이 1개면 오늘과 사실상 동일하게 동작

### 달라지는 것

- 한 브로커의 지연·실패가 다른 브로커의 동기화를 지연시키지 않는다 (이번 목적)

### 의도적으로 하지 않는 것

- 그룹 태스크 타임아웃: 어댑터 HTTP 클라이언트 타임아웃이 1차 방어선. 행 걸림 시
  다음 틱이 밀리는 시맨틱은 현행 순차 방식과 동일하므로 추가하지 않는다 (YAGNI).
- 브로커 내 부분 동시성(예: Binance 5/s 여유 활용): 현재 계좌 규모에서 불필요.
- BINANCE 레거시 경로 통합: 별도 과제.

## 테스트 계획 (TDD, fake 기반)

프로젝트에 mockk가 없으므로(스타터의 Mockito는 Kotlin final과 궁합 나쁨) 손으로
쓴 fake `BrokerAccountSyncer` + `CountDownLatch`/`AtomicInteger`로 검증한다.

| # | 검증 | 방법 |
|---|---|---|
| T1 | 브로커 간 병렬 실행 | 두 브로커의 fake가 서로 상대의 시작 latch를 대기 — 순차라면 타임아웃 실패, 병렬이면 통과 |
| T2 | 브로커 내 순차 실행 | 같은 브로커 계좌 3개, 그룹 내 동시 실행 카운터 최대값 == 1 |
| T3 | 실패 격리 | 한 브로커 fake가 예외 — 다른 브로커 계좌 전부 호출됨 |
| T4 | 필터 보존 | BINANCE·unknown type·최근 동기화 계좌는 syncer에 도달하지 않음 |

테스트에서 스케줄러는 직접 생성(스프링 컨텍스트 불필요)하고, executor는 테스트가
소유·종료한다.
