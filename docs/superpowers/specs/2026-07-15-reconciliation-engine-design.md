# 룰 기반 대사·검증 엔진 v1 설계 (P2 BE 코어)

- 작성일: 2026-07-15
- 상태: 설계 승인 완료 (채팅 리뷰)
- 관련 모듈: `reconciliation`(신규), `backend-app`(조립)
- 상위 문서: 노션 「ALLFOLIO 기능명세서 (초안) — 룰 기반 대사·검증 엔진 & 일일마감 워크플로우」,
  「ALLFOLIO 메뉴 구조도 (초안)」의 간소화 판정
- 대응 태스크: 노션 「ALLFOLIO 이식 개발 태스크」 #12~#17

## 배경 / 문제

브로커 동기화(5개 어댑터)로 쌓이는 외부 데이터(`ua_assets`)와 거래 원장(`trade_raw`)에서
FIFO로 재계산한 내부 포지션(`position_daily`)이 서로 맞는지 검증하는 장치가 없다.
동기화 누락·중복, 수수료 반올림, 심볼 매핑 차이가 실제로 발생할 수 있는 구조인데
차이를 발견할 수단이 사람 눈뿐이다.

기관 ABOR 검증시스템에서 검증된 패턴(룰 기반 대사, 2단계 해시→데이터 대사,
Known Difference)을 ALLFOLIO 규모에 맞게 간소화해 이식한다.

핵심 간소화 판정(메뉴 구조도 문서에서 확정):

- SQL 자유기술 룰 → **사전 정의 룰 타입 + JSONB 파라미터** (임의 SQL 실행 리스크 제거)
- 4-eyes 승인 → 단일 운영자이므로 생략, 유효기간 버저닝으로 변경 이력만 보존
- 버튼 단위·기간제 권한 → USER/ADMIN 2단계 role

## 목표

1. 룰을 데이터(DB 행)로 관리하는 검증·대사 엔진: 파라미터 변경은 무배포, 룰 종류 추가만 배포
2. `ua_assets`(브로커 측) vs `position_daily`(내부 계산 측)의 2단계(해시→데이터) 포지션 대사
3. Known Difference로 허용 차이를 등록해 오탐 반복 제거 (흡수 건은 숨기지 않고 구분 표시)
4. 대사 중 동기화 경합 차단 (분산 락)
5. 실행 이력·드릴다운 조회 API (P2 FE 화면과 P3 마감 단계의 토대)

## 비목표 (YAGNI)

- SQL 직접 등록 룰 에디터 — 룰 타입 방식으로 부족해지면 2차 (FR-SEC 전제)
- 계좌↔포트폴리오 매핑 기반 계좌 단위 대사 — v2 (아래 조인 전략 참조)
- 평가액(current_value) 대사 — 시세 시점 차이로 오탐 대량 발생, KD 운영 경험 쌓은 뒤 v2
- 승인 워크플로우, KD 만료 푸시 알림(P3 SSE 통합 때), 엑셀 업로드
- P2 FE 화면 4종(SCR-RECON-01/02, SCR-KD-01, SCR-VRF-01) — 별도 스펙

## 설계

### 1. 모듈 배치

```
common → reconciliation(신규) → backend-app(조립)
```

- `reconciliation`은 unified-asset·snapshot에 **코드 의존 없음**. 두 원천(`ua_assets`,
  `position_daily`)은 읽기 전용 네이티브 쿼리로만 참조 — snapshot↔report와 동일한
  "데이터로만 연결" 패턴.
- 실행 진입점: backend-app 컨트롤러(수동 실행·조회) + 추후 P3 마감 단계(S040)에서 호출.

### 2. 대사 조인 전략 (v1): 사용자 × 심볼 단위 집계

두 원천은 자연 조인 키가 없다:

| 원천 | 키 | 성격 |
|---|---|---|
| `ua_assets` | userId + accountId + symbol(문자열) | 브로커 동기화 결과 (현재 상태, 이력 없음) |
| `position_daily` | tenantId + portfolioId + assetId(UUID) + date | trade_raw에서 FIFO 재계산 (일별 이력) |

계좌↔포트폴리오 매핑이 존재하지 않으므로, v1은 양쪽을 **user × symbol로 집계(수량 합산)**
해서 비교한다. 내부 측 symbol은 `position_daily ⋈ 자산 마스터`로 assetId를 해석한다.
차이는 "이 심볼이 안 맞는다"까지 특정하고, 계좌 단위 특정은 매핑 테이블 도입 후 v2.

### 3. 스키마 — 5테이블 (`recon_` 접두어, 태스크 #12)

| 테이블 | 역할 | 핵심 컬럼 |
|---|---|---|
| `recon_rule` | 룰 정의 | id(UUID PK), rule_code, rule_type, params(JSONB), apld_strt_dt, apld_end_dt(기본 9999-12-31), use_yn, created_by, created_at |
| `recon_run` | 실행 단위 | id, run_date(기준일), run_type(VALIDATION/RECONCILIATION/ALL), user_id(스코프), status(RUNNING/COMPLETED/FAILED), trigger_type(MANUAL/SCHEDULED), internal_as_of(스냅샷 date), external_as_of(min lastSyncedAt), started_at, finished_at, executed_by |
| `recon_result_summary` | 실행×룰 요약 | id, run_id FK, rule_id FK, phase(HASH/DATA/null), status, checked_cnt, diff_cnt, kd_absorbed_cnt, error_msg, elapsed_ms |
| `recon_result_detail` | 차이·오류 상세 | id, summary_id FK, symbol, field_name, diff_type(VALUE_MISMATCH/MISSING_INTERNAL/MISSING_EXTERNAL/RULE_VIOLATION), internal_value, external_value, diff_value, extras(JSONB), kd_id FK(nullable) |
| `recon_kd` | Known Difference | id, kd_code, target_broker(nullable), target_symbol(nullable), target_field(nullable), value_type(ABS/RATIO), allow_value, reason, apld_strt_dt, apld_end_dt, use_yn, created_by |

원 스펙(기능명세서 4.2) 대비 간소화 2가지:

1. **KD_DEFINE + KD_VALUE(1:N) → 단일 테이블 통합.** 허용값이 여러 개 필요하면 KD를
   여러 건 등록한다. 개인 서비스 규모에서 1:N은 과설계.
2. **KD_RESULT(적용 이력 테이블) 제거.** detail 행에 `kd_id`를 직접 기록해
   "어떤 차이가 어떤 KD로 흡수됐는지"(FR-KD-003)를 충족.

버저닝은 원 스펙 그대로: 룰·KD 수정 = 기존 행 `apld_end_dt` 마감 + 신규 행 INSERT.
소급 조회("그날 어떤 룰이 유효했나")가 가능해진다.

### 4. 룰 엔진 (태스크 #13)

`BrokerFacade`가 `List<BrokerAdapter>`를 주입받아 brokerType으로 라우팅하는 것과
동일한 OCP 패턴:

```
RuleEngine
 ├─ 유효 룰 조회: run_date ∈ [apld_strt_dt, apld_end_dt] AND use_yn = true
 └─ List<RuleExecutor> 주입 → rule_type으로 executor 매칭 → 실행
```

- 각 executor는 `params(JSONB)`를 자기 타입의 파라미터 데이터 클래스로 역직렬화.
- **룰 저장 시점에 파라미터 스키마 검증**: 미정의 키·필수 누락이면 저장 거부
  (원 스펙 FR-RULE-003 플레이스홀더 검사의 대응물).
- **대사(RECON)도 룰 타입이다.** 검증 룰과 대사 룰이 같은 엔진·같은 결과 테이블을 쓴다.

v1 룰 타입 6종:

| rule_type | 종류 | 내용 | params 예시 |
|---|---|---|---|
| `NEGATIVE_QUANTITY` | 검증 | ua_assets 음수 수량 탐지 | — |
| `STALE_SYNC` | 검증 | lastSyncedAt이 임계보다 오래된 AUTO 계좌 | `{"maxAgeHours": 26}` |
| `DUPLICATE_TRADE` | 검증 | trade_raw 중복 후보(수동입력 brokerType null 포함) | `{"lookbackDays": 7}` |
| `SNAPSHOT_MISSING` | 검증 | 거래가 있는 포트폴리오의 기준일 position_daily 부재 | — |
| `POSITION_HASH_RECON` | 대사 1단계(F) | 해시 비교로 차이 후보 심볼 선별 | `{"brokers": null}` |
| `POSITION_DATA_RECON` | 대사 2단계(G) | 후보 심볼 필드 단위 비교 | `{"fields": ["quantity"]}` |

초기 룰 인스턴스는 Flyway 시드로 등록한다 (룰 관리 API는 ADMIN role 도입 후 개방).

### 5. 2단계 대사 알고리즘 (태스크 #14, #15)

1. **정규화**: 양쪽에서 user×symbol 수량 집계 로드 → symbol 대문자·트림,
   quantity scale 10 통일, **수량 0 행 제외**(청산 포지션 vs 브로커 미표시 오탐 방지).
2. **Phase F (해시)**: 심볼별 `SHA-256("symbol|qty")` → 심볼 해시를 정렬·연결한
   사용자 루트 해시 비교. 일치 → 전체 통과, 요약만 기록하고 종료.
   불일치 → 심볼별 해시 대조로 후보 심볼 선별.
3. **Phase G (데이터)**: 후보 심볼만 필드 단위 비교 → detail에
   (symbol, field, internal, external, diff) 적재. v1 비교 필드는 **quantity만**.
4. 한쪽에만 존재하는 심볼 → `MISSING_INTERNAL` / `MISSING_EXTERNAL`로 기록.

**시점 정합(알려진 제약)**: `ua_assets`는 이력 없는 현재 상태 테이블 → 대사의 외부 측
정합 시점은 "마지막 동기화 기준"이다. 정석 실행은 자정 배치의
**재동기화(S010) → 스냅샷(S030) → 대사(S040)** 직렬 체인 뒤에 붙이는 것
(기존 자정 배치가 이미 [재동기화→NAV] 구조). 수동 실행 시엔 run에
`internal_as_of` / `external_as_of`를 기록해 화면에 표시한다.

### 6. KD 적용 (태스크 #16)

- **매칭**: KD의 (target_symbol, target_field) — null은 와일드카드 — 가 diff의
  (symbol, field)와 일치하고, run_date가 KD 유효기간 내일 때.
  `target_broker`는 diff에 브로커 문맥이 있을 때만(extras.broker, 검증 룰 결과 등) 대조하며,
  브로커 문맥이 없는 대사 diff(user×symbol 집계)에는 `target_broker` 지정 KD가 매칭되지 않는다.
- **허용 판정**: `ABS`: `|diff| ≤ allow_value` / `RATIO`: `|diff| / |internal| ≤ allow_value`.
- **적용**: Phase G 적재 시점에 판정 → diff 행은 남기고 `kd_id` 기록(숨김 아님, FR-KD-002).
  summary는 `diff_cnt`·`kd_absorbed_cnt` 분리 집계 → "차이 3건 중 2건은 알려진 차이".
- 만료 임박 여부는 KD 조회 응답의 계산 필드로만 제공 (푸시는 P3).

### 7. 동기화 잠금 (태스크 #17, FR-RECON-002)

- 기존 Redis 분산 락 인프라 재사용. 키: `recon:lock:{userId}`,
  TTL = 예상 최대 실행시간 + 버퍼(5분).
- 대사 실행 전 락 획득 실패(동기화 진행 중) → **실행 거부** + 명확한 오류.
  역방향으로 동기화(수동 Sync·자정 배치)도 시작 전 같은 락을 확인한다.
- Redis 장애 시 안전 우선으로 실행 거부 (구조정리 §9의 락 인프라 의존 트레이드오프 수용).

### 8. API 표면 (backend-app)

| 엔드포인트 | 권한 | 설명 |
|---|---|---|
| `POST /api/recon/runs` | USER(본인 스코프) | 실행. body: runDate, runType |
| `GET /api/recon/runs` | USER(본인) | 실행 목록 (기간 필터) |
| `GET /api/recon/runs/{id}` | USER(본인) | 요약: summary 목록 + 데이터 시점 |
| `GET /api/recon/runs/{id}/details` | USER(본인) | 드릴다운 (ruleId/symbol/field 필터, FR-RECON-004) |
| `GET/POST/PUT /api/recon/rules` | ADMIN | 룰 조회·등록·수정(버저닝) — **ADMIN role 도입 후 개방** |
| `GET/POST/PUT /api/recon/kds` | ADMIN | KD 관리 — 동상 |

**권한 결정(메뉴 구조도 판정에서 조정)**: 원 판정은 "실행=ADMIN"이었으나, 개인 서비스에서
본인 데이터 대사를 본인이 실행하지 못하는 건 부자연스러움 → v1은 본인 스코프 실행을
USER에 허용(기존 `PortfolioAuthorizationService` 계열 소유권 검증 패턴 적용).
덕분에 보류 중인 ADMIN role 없이 #12~#17 전체 진행 가능. ADMIN 필요 지점은
룰·KD 관리 API뿐이며 초기엔 Flyway 시드로 대체한다.

### 9. 실행 상태·멱등·오류 처리

- run 상태: `RUNNING → COMPLETED / FAILED`. 같은 기준일 재실행 = 새 run 행
  (자연스러운 차수·이력, FR-STEP-008 대응). 결과는 run 단위 격리라 재실행이
  기존 이력을 건드리지 않는다.
- **데이터 오류 vs 시스템 오류 분리(FR-RULE-007)**: 룰이 찾은 차이 = 데이터 오류(정상 결과).
  executor 예외 = 시스템 오류 → 해당 summary만 FAILED로 격리(`runCatching`,
  기존 스케줄러의 계좌 격리와 동일), 나머지 룰은 계속. 전 룰 실패 시 run FAILED.
- detail 적재는 룰당 상한 100건(FR-RULE-006). 초과분은 summary의 diff_cnt에만 반영.

### 10. 테스트 전략 (TDD)

- **단위** (순수 로직으로 분리, PositionEngine 스타일 외부 의존 0):
  정규화(스케일·0수량 제외·심볼 정리), 해시 계산, 룰별 executor 판정,
  KD 매칭·허용 판정(ABS/RATIO·와일드카드·기간 경계), 파라미터 스키마 검증(미정의 키 거부)
- **통합**: 유효기간 버저닝 룰 조회(경계일 포함), run E2E
  (시드 → F 해시 불일치 → G diff → KD 흡수 집계), 락 충돌 시 실행 거부,
  detail 100건 절단, MISSING_* 케이스
- 기존 모듈 테스트 컨벤션 준수

## 구현 순서 (태스크 매핑)

1. #12 `recon_` 스키마 DDL (Flyway) + `reconciliation` 모듈 골격
2. #13 룰 저장소(버저닝 조회) + RuleEngine + 검증 룰 executor 4종
3. #14 정규화·해시 대사(Phase F)
4. #15 데이터 대사(Phase G) + detail 표준 포맷
5. #16 KD 매칭·흡수
6. #17 분산 락 연동 (+동기화 측 락 확인)
7. API 컨트롤러(USER 스코프) + 자정 배치 S040 편입은 P3에서
