# 일일마감 워크플로우 (P3 #22~31) 설계 — 간소화 판정

- 작성일: 2026-07-31 · 상태: 초안 — **사용자 리뷰 후 착수**
- 상위 문서: 노션 「기능명세서 5장 (일일마감 워크플로우)」 FR-STEP-001~009·FR-DASH-001~004, 메뉴구조도 S010~S060
- 관련 모듈: `workflow`(신규), `backend-app`(조립), 기존 스케줄러(#24 편입 대상)

## 목적

자정에 흩어져 도는 배치들(재동기화→NAV→…)을 **데이터로 정의된 마감 워크플로우**로 묶고, 일자별 실행 현황을 관제(대시보드)·개입(수동 처리)·감사(재작업 이력)할 수 있게 한다. P2 대사는 S040 단계로 편입된다.

## 간소화 판정 (P2 원칙의 연장 — "비즈니스 로직만 이식, 기술은 ALLFOLIO 방식")

| # | 기능명세서(기관) | ALLFOLIO 판정 | 이유 |
|---|---|---|---|
| 1 | 단계 정의=데이터(3계층) | **유지** — 정의(순서·선행조건·컷오프·주기·실행일 규칙)는 DB 행, 화면 CRUD(#30) | "단계 정의만 바꿔 워크플로우 변경(코드 무수정)"이 이 명세의 핵심 가치(FR-STEP-009). 단, **실행 액션은 코드 빈**(아래 2) |
| 2 | ACTION_REF가 임의 로직 참조 | **액션=Spring 빈 레지스트리** — `WfAction` 인터페이스 구현 빈을 action_ref 문자열로 매칭(ReconRule과 동일 OCP) | P2 코드 룰 원칙. 정의는 "무엇을 어떤 순서로"만 들고, "어떻게"는 코드·테스트·리뷰가 보증 |
| 3 | HIST 트리거 2테이블(전체 컬럼 스냅샷) | **단일 `wf_def_hist`** — 애플리케이션 레벨에서 엔티티 JSON 스냅샷 기록(C/U/D) | 개인 규모에서 DB 트리거·테이블 2종은 과설계. 감사 요구(FR-AUTH-004)는 단일 테이블로 충족 |
| 4 | 상태 모델 N/R/S·Y/E/P + 롤업 5종 | **동일 채택** — sub: PENDING/RUNNING/SUCCESS/ERROR/PAUSED, step 롤업: STANDBY/FINISH/ERROR/RUNNING/PAUSED | 검증된 패턴 그대로 |
| 5 | 컷오프 SLA + 지연 경고(FR-DASH-003) | 컷오프 필드는 스키마에 **유지**, 경고 알림은 **후속**(SSE #31에서 error 알림만 먼저) | 권장 항목 |
| 6 | 버튼 단위·기간제 권한(FR-AUTH-001/002) | **생략** — USER/ADMIN 2단계(기존 판정 유지). 관제 화면·수동 개입은 ADMIN | 기존 간소화 판정 |
| 7 | WebSocket 알림 | **기존 SSE 인프라 재사용**(#31, `SseEmitterRegistry`) — 단계 완료/오류 이벤트 | 스택 무변경 |
| 8 | 마감ID·수신 영역 파이프라인(3장) | **생략** — ALLFOLIO에 수신 영역·기간계 개념 없음. "일자(ymd)"가 실행 단위 | 대응물 부재 |
| 9 | 워크플로우 스코프 | **시스템 전역**(일자 단위 1회) — 사용자별 아님. 액션 내부에서 전 사용자 루프(기존 DailyAccountSyncer.syncAll 방식) | 자정 배치가 이미 전역. 관제 주체는 운영자(ADMIN) |

## 스키마 — 5테이블 (`wf_` 접두어, #22)

```sql
wf_step (
  step_cd VARCHAR(20) PK, step_seq INT, step_name VARCHAR(100), step_group VARCHAR(50),
  term_gb VARCHAR(1),          -- D(일)/M(월)/Q(분기)
  cutoff_start VARCHAR(5), cutoff_end VARCHAR(5),   -- "HH:mm" (경고는 후속)
  essential_step_cd VARCHAR(20),                    -- 선행 필수 단계(자기참조, FK 없음)
  url VARCHAR(200),                                 -- 업무화면 경로(대시보드 카드 링크)
  holiday_except_yn BOOLEAN, use_yn BOOLEAN
)
wf_sub_step (
  step_cd VARCHAR(20), sub_step_cd VARCHAR(20), PK(step_cd, sub_step_cd),
  sub_step_seq INT, sub_step_name VARCHAR(100),
  auto_manual VARCHAR(1),      -- A(자동)/M(수동확인)
  closing_check_yn BOOLEAN,    -- 마감판정(롤업) 포함 여부
  date_term INT, date_gb VARCHAR(1),  -- M/Q 실행일 규칙: n번째(음수=역산) × S(달력일)/B(영업일)
  action_type VARCHAR(10),     -- CHAIN/POLL/MANUAL
  action_ref VARCHAR(100),     -- WfAction 빈 ref (MANUAL이면 null)
  timeout_sec INT DEFAULT 300, poll_interval_sec INT DEFAULT 10,  -- POLL 파라미터(명세 5분·10초)
  use_yn BOOLEAN
)
wf_job_log (
  id UUID PK, ymd DATE, step_cd VARCHAR(20), sub_step_cd VARCHAR(20), exec_seq INT,  -- 재작업 차수
  UNIQUE(ymd, step_cd, sub_step_cd, exec_seq),
  status VARCHAR(10),          -- PENDING/RUNNING/SUCCESS/ERROR/PAUSED
  started_at TIMESTAMP, finished_at TIMESTAMP,
  auto_manual VARCHAR(1),      -- 이번 실행이 자동/수동이었는지
  executor VARCHAR(100),       -- SYSTEM 또는 admin userId
  remark VARCHAR(500),         -- 수동 성공/실패 사유(수동 시 필수), 오류 메시지
  error_detail TEXT
)
wf_def_hist (
  id UUID PK, entity_type VARCHAR(10),  -- STEP/SUB_STEP
  entity_key VARCHAR(50), crud VARCHAR(1),  -- C/U/D
  snapshot TEXT,               -- 변경 후 엔티티 JSON (D는 변경 전)
  changed_by UUID, changed_at TIMESTAMP
)
wf_holiday (
  day DATE, country VARCHAR(2) DEFAULT 'KR', PK(day, country),
  name VARCHAR(100)
)
```

배포: init.sql + 자립형 마이그레이션(신규 테이블 + 시드 — 무해·멱등). 시드는 아래 §시드.

## 실행기 (#23) — `workflow` 모듈

```kotlin
interface WfAction {                    // 실행 액션 = Spring 빈 (ReconRule 패턴)
    val ref: String                     // wf_sub_step.action_ref와 매칭
    fun execute(ctx: WfContext): WfActionResult   // ctx: ymd. 동기 완료형(CHAIN용)
}
interface WfPollAction : WfAction {     // POLL형: 시작 후 상태를 물어봄
    fun poll(ctx: WfContext): WfPollStatus        // IN_PROGRESS / DONE / FAILED
}
```

`WfStepExecutor` (FR-STEP-001~008):
1. **runDaily(ymd)** — 자정 스케줄러(§#24)/수동 트리거 진입점. `wf_step` use_yn·주기 필터:
   - D: 매일(holiday_except_yn이면 영업일만). M/Q: sub_step의 date_term/date_gb + 휴일 캘린더로 산정된 실행일에만(§#25 유틸).
2. **선행단계 게이트**(FR-STEP-001): essential_step_cd의 당일 롤업이 FINISH가 아니면 해당 단계 전체 SKIP(로그 없이 PENDING 유지) — 다음 트리거·수동 실행에서 재시도.
3. 단계 내 하위단계는 seq 순 **순차 실행**(FR-STEP-002/003 — 단일 프로세스라 "폴링 대기"는 순차 실행으로 자연 충족. CHAIN=동기 실행).
4. **POLL**(FR-STEP-004): poll_interval_sec 간격, timeout_sec 초과 시 ERROR.
5. **MANUAL**: 자동 실행하지 않고 PENDING 유지 — 대시보드에서 수동 성공/실패 처리(사유 필수, FR-STEP-005·FR-AUTH-005).
6. 상태 기록(FR-STEP-007): 시작 RUNNING → SUCCESS/ERROR + 시각·executor. 액션 예외는 ERROR(error_detail 500자) 격리 — **후속 하위단계 중단**(같은 단계 내), 다른 단계는 게이트 판정에 따름.
7. **재작업**(FR-STEP-008): 재실행 = 같은 (ymd, step, sub) 최대 exec_seq+1 새 로그 행. 멱등성은 각 액션이 보장(기존 배치들이 이미 UPSERT/재구성형).
8. **롤업**(5.3 동일): closing_check_yn 대상 최신 차수 기준 — 전부 PENDING→STANDBY / 전부 SUCCESS→FINISH / 하나라도 ERROR→ERROR / 하나라도 RUNNING→RUNNING / 그 외→PAUSED.

동시성: 전역 워크플로우이므로 `wf:lock:{ymd}` Redis 락(P2 UserReconSyncMutex 패턴)으로 runDaily 중복 방지. 수동 단계 실행도 같은 락.

## 시드 워크플로우 + 기존 스케줄러 편입 (#24)

| 단계 | 하위단계(액션 ref) | 유형 | 편입 원본 |
|---|---|---|---|
| S010 브로커 동기화 | SYNC_ALL_ACCOUNTS → `DailyAccountSyncer.syncAll()` | CHAIN | DailyNavScheduler 1단계 |
| S020 사전검증 | RECON_VALIDATION → 전 사용자 `ReconRunService(VALIDATION, SCHEDULED)` | CHAIN | 신규(P2 재사용) |
| S030 포지션·스냅샷 | NAV_SNAPSHOT → DailyNavScheduler NAV 파트 추출 | CHAIN | DailyNavScheduler 2단계 |
| S040 대사 | RECON_POSITION → 전 사용자 `ReconRunService(RECONCILIATION, SCHEDULED)` | CHAIN | 신규(P2 재사용) |
| S050 마감 확인 | CLOSING_CONFIRM | **MANUAL** | 신규 — 운영자가 대시보드 확인 후 수동 마감 |
| S060 월마감 | MONTHLY_REPORTS → 전 사용자 전 리포트 유형 아카이브 생성 (M, date_term=-1, date_gb=B: **월말 -1영업일**) | CHAIN | 기존 report generate 재사용 |

- **DailyNavScheduler는 얇은 트리거로 축소**: `@Scheduled(자정 KST)` → `WfStepExecutor.runDaily(오늘)`. 기존 [sync→NAV] 로직은 WfAction 2개로 분리 이식(동작 동일). BrokerSyncScheduler(60초)·OutboxEventProcessor(30초)·DlqWorker(5초) 등 **상시 폴링형은 편입하지 않음** — 마감 체인은 "일 단위 상태를 만드는 배치"만(명세 취지).
- S020/S040 전 사용자 루프: 사용자별 recon 락과 공존(사용자 단위 획득 실패 시 해당 사용자 skip·집계).

## 휴일 캘린더·영업일 유틸 (#25, FR-CMMN-002·FR-STEP-006)

- `wf_holiday` 시드: 2026년 KR 공휴일. 관리 API(ADMIN CRUD)는 후속 — 초기엔 마이그레이션 시드.
- 순수 `BizDayCalculator`(주말+휴일 테이블 기반): `isBizDay(date)`, `addBizDays(date, n)`(음수=역산), `nthBizDayOfMonth(ym, n)`(음수=월말 역산) — sub_step 실행일 산정(`date_term=-1, date_gb=B` → 월말 -1영업일).

## API (backend-app, ADMIN — `/api/admin/closing`)

| 엔드포인트 | 설명 |
|---|---|
| `GET /dashboard?month=` | 월 달력: 일자별 단계 롤업 상태·오류/미실행 건수 (SCR-DASH-01, 휴일 표시) |
| `GET /days/{ymd}` | 일자 상세: 단계×하위단계×최신 차수 로그 (SCR-DASH-02) |
| `POST /days/{ymd}/run` | 당일 워크플로우 (재)실행 — 락 경합 시 409 |
| `POST /days/{ymd}/steps/{step}/substeps/{sub}/run` | 개별 하위단계 재실행(재작업 차수 증가) |
| `POST /.../substeps/{sub}/manual` | 수동 성공/실패 처리 {result: SUCCESS/ERROR, remark 필수} |
| `GET /jobs?ymd=&status=` | 재작업 로그·이력 (SCR-DASH-05) |
| `GET/POST/PUT/DELETE /steps`, `/substeps` | 워크플로우 정의 CRUD + wf_def_hist 기록 (SCR-DASH-03) |
| `GET /holidays?year=` | 휴일 조회(달력 렌더용) |

SSE (#31): 기존 `SseEmitterRegistry`로 단계 완료/오류 이벤트 push(`closing.step` 이벤트: ymd·step·status·사유). 컷오프 경고·개시공지는 후속.

## FE (ADMIN, 관리자 콘솔 하위)

- `/unified/admin/closing` — **마감 대시보드**(#26, SCR-DASH-01): 월 달력(휴일 회색), 일자 클릭 → 단계 카드(롤업 색상·오류/미실행 건수·시각). 상단 "오늘 마감 실행" 버튼.
- 단계 카드 펼침 = **단계 상세**(#27, SCR-DASH-02): 하위단계 목록(상태·차수·시각·수행자·사유), 재실행·수동 처리(사유 입력 모달).
- **일별 매트릭스**(#28, SCR-DASH-04): 일×단계 상태 그리드(월 뷰) — 대시보드 하단 섹션으로 통합.
- **재작업 로그**(#29, SCR-DASH-05): 차수>1 로그 필터 탭.
- **정의 관리**(#30, SCR-DASH-03): 단계·하위단계 CRUD 테이블+폼, 변경 이력 뷰.
- FE 구성도 P2처럼 **탭 통합 1~2페이지**로: `/unified/admin/closing`(대시보드+매트릭스+재작업 탭) + `/unified/admin/closing/define`(정의 관리). 관리자 허브·서브내비에 추가.

## 테스트 (TDD)

- 순수: BizDayCalculator(역산·경계), 롤업 규칙 5분기, M/Q 실행일 판정(date_term±·S/B), 선행 게이트 판정.
- 실행기: fake WfAction(성공/예외/POLL 타임아웃)으로 상태 전이·차수 증가·MANUAL 미실행·게이트 SKIP.
- 정의 CRUD: hist 기록·소유권(ADMIN 게이트는 SecurityConfig).
- 라이브: 시드 적용 → 수동 runDaily → S010~S040 SUCCESS·S050 PENDING(MANUAL) → 수동 마감 → FINISH 롤업, 대시보드 렌더.

## 구현 순서 (PR 매핑)

1. **PR A(#22+#25)**: workflow 모듈 골격 + 5테이블·시드(2026 KR 휴일 포함) 마이그레이션 + BizDayCalculator — 휴일 유틸은 스키마와 결합돼 한 PR
2. **PR B(#23)**: WfAction 계약 + WfStepExecutor(게이트·롤업·차수·MANUAL) + 수동 처리·조회 API
3. **PR C(#24+#31)**: 액션 6종 구현(기존 로직 편입) + DailyNavScheduler 트리거화 + SSE 이벤트
4. **PR D(#26~29)**: 대시보드 통합 화면
5. **PR E(#30)**: 정의 관리 화면 + hist

## 범위 밖 (후속)

컷오프 지연 경고(FR-DASH-003)·개시/마감 공지(FR-DASH-004), 휴일 ADMIN CRUD 화면, 분기(Q) 시드(테이블은 지원), 사용자별 마감 뷰.
