# 일별 마감 워크플로우 트리거 — 설계

- 작성일: 2026-08-15
- 상태: 설계 확정
- 증상: `performance_daily`가 매일 안 쌓여 수익률 화면이 대부분 사용자에게 400을 낸다

## 1. 진단 — 코드는 전부 있고, 트리거만 안 뛴다

실측 (2026-08-15, 최근 30일):

| portfolio | 30일 행 수 | 마지막 | 달력일 |
|---|---|---|---|
| 3e055c70… | **4** | 2026-08-05 (열흘 전) | 15 |
| 나머지 4개 | **각 1행** | — | 1 |

배선은 끊긴 데가 없다:

```
ClosingScheduler  @Scheduled(cron "0 0 0 * * *", KST)
  └→ WfStepExecutor.runDaily(ymd)
       └→ S030 'NAV 스냅샷(전 사용자)'      ← 시드에 이미 등록됨
            └→ NavSnapshotAction (ref = "NAV_SNAPSHOT")
                 └→ DailyNavScheduler.recordDailySnapshots()
                      └→ PerformanceSnapshotService.record()  → performance_daily UPSERT
```

**`wf_job_log`가 비어 있다 — 워크플로우가 한 번도 돈 적이 없다.** 액션·게이트·시드는 전부 무죄다.

### 원인: Render 무료 플랜에서 `@Scheduled`는 성립하지 않는다

무료 웹 서비스는 15분 유휴 시 잠든다. 자정 KST에는 아무도 앱을 쓰지 않으므로 인스턴스가 자고 있고, **잠든 인스턴스에서는 `@Scheduled`가 뛰지 않는다.** 01:30 재시도도 같은 이유로 못 뛴다.

**이 저장소는 이미 이 사실을 알고 있다.** `SchedulerTriggerController`의 KDoc이 그대로 적어 뒀다:

> "Render 무료 플랜에는 크론 잡이 없고, 무료 웹 서비스는 15분 유휴 시 잠들어 인스턴스 안의 `@Scheduled`만으로는 주기 실행이 성립하지 않는다."

시세 수집(AF-102·103·104)은 그래서 GitHub Actions 크론이 HTTP로 깨우는 방식으로 옮겼다. **마감 워크플로우만 인프로세스 `@Scheduled`로 남았다.**

데이터도 이 설명과 맞는다. `PerformanceSnapshotService.record()`는 **sync 직후에도** 불린다(`SyncAccountUseCase`, `AccountController`). 그래서 사용자가 앱을 연 날만 행이 생긴다 — 15일에 4행이 정확히 그 모양이다.

## 2. 트리거 — 외부에서 깨운다

### 엔드포인트

`POST /api/internal/scheduler/closing`. 기존 수집 트리거와 **같은 파일**(`SchedulerTriggerController`), 같은 토큰 인증(`scheduler.trigger-token`, permitAll 경로 + 컨트롤러 자체 인증).

**날짜를 노출하지 않는다.** 서버가 `LocalDate.now(KST)`로 구한다 — 기존 트리거 전부가 그렇게 하는 이유와 같다(컨테이너가 UTC라 클라이언트가 날짜를 정하면 하루씩 밀린다).

응답은 `WfRunSummary`(`ymd`/`executedSteps`/`gateSkippedSteps`/`notScheduledSteps`)를 그대로 싣는다. **Actions 잡 요약에서 어느 단계가 안 돌았는지 바로 읽히는 것이 이 엔드포인트의 관측 수단 전부다.**

`ClosingInProgressException` → 409.

### 어드민 컨트롤러에 위임하지 않는다 — 관례를 깨는 자리

집 관례는 "어드민 컨트롤러에 위임해 예외→상태 매핑을 복제하지 않는다"이고, `SchedulerTriggerController`의 KDoc이 **"이 위임을 정리하지 말 것"**이라고까지 적어 뒀다. 여기서는 깬다.

`ClosingAdminController.runDay`는 `X-User-Id`를 받아 그 값을 **실행자로 `wf_job_log.executor`에 찍는다.** 크론에는 어드민 신원이 없다. 실존 인물의 id를 자동 실행에 찍으면 감사 추적이 오염되고, 나중에 "이 마감을 누가 돌렸나"에 거짓으로 답한다. 위임해서 얻는 것은 409 매핑 한 줄뿐이다.

따라서 `stepExecutor.runDaily(ymd)`를 직접 부른다 — 기본 실행자가 `SYSTEM_EXECUTOR`다.

### GitHub Actions 크론

`.github/workflows/closing.yml`:

```
cron: "0 15 * * *"    # UTC 15:00 = KST 00:00 (다음 날)
cron: "30 16 * * *"   # UTC 16:30 = KST 01:30 — 게이트 스킵 재시도
```

기존 `ClosingScheduler`의 두 시각(자정 + 01:30)을 그대로 옮긴 것이다.

**요일 필터를 걸지 않는다.** `S010~S050`은 `holiday_except_yn = FALSE`이고 `WfScheduleJudge`가 `WfTermGb.D -> !holidayExcept || bizDay.isBizDay(date)`로 판정한다 — `!false = true`라 **주말·공휴일 포함 매일 실행이 정의된 동작**이다. 크론에 `1-5`를 넣으면 정의와 어긋난다. 공휴일을 쉬어야 하는 단계가 생기면 그건 `wf_step` 데이터로 정하는 것이지 크론이 정할 일이 아니다.

**UTC 15:00이 KST 날짜 경계라는 점은 알고 쓴다.** `collect-rate.yml`이 "시각을 UTC 15:00 이후로 옮기면 KST가 다음 날로 넘어가 금요일 실행이 토요일이 되면서 `1-5`에서 조용히 사라진다"고 경고해 뒀다. 그 함정은 **요일 필터가 있을 때만** 성립한다. 우리는 매일 돌므로 걸리지 않는다. 다만 이 파일에 요일 필터를 나중에 추가하려는 사람이 그 경고를 다시 만나야 하므로, 워크플로 주석에 못 박는다.

콜드 스타트가 ~85초다(실측, 무료 0.1 vCPU). `collect-rate.yml`의 재시도 루프와 `timeout-minutes`를 그대로 따른다 — 그 파일이 "재시도 예산(최악 9분)보다 커야 한다"고 근거를 적어 뒀으므로 숫자를 새로 정하지 말고 복제한다. `concurrency` 그룹으로 겹침을 막되 `cancel-in-progress: false` — 진행 중인 마감을 죽이면 절반만 실행된 날이 남는다.

## 3. 날짜 — `record()`가 날짜를 받는다

`PerformanceSnapshotService.record()`가 `LocalDate.now()`를 **인자 없이** 쓴다. 컨테이너는 UTC다(Dockerfile·application.yml·render.yaml 어디에도 TZ 설정이 없다). 자정 KST는 UTC로 전날 15:00이므로:

| | 값 |
|---|---|
| `ctx.ymd` (`ClosingScheduler`가 `LocalDate.now(KST)`로 구함) | **D** |
| `wf_job_log.ymd` | **D** |
| `performance_daily.date` (`LocalDate.now()`, UTC) | **D−1** |

로그는 D를 성공이라 하는데 데이터는 D−1에 앉는다. 그리고 `record()`는 워크플로우가 정한 날짜를 **받을 방법 자체가 없다** — 시그니처가 `record(userId, nav)`뿐이라 백필도 구조적으로 불가능하다.

이 저장소에는 "Render 컨테이너는 UTC라…" 경고 주석이 **세 군데** 따로 적혀 있다(`MarketRateAdminController`, `FxRateAdminController`, `MarketQueryService`/`IndexCollectService`). 세 번 데인 종류의 실수다.

### 변경

`record(userId, nav, date)`로 날짜를 받는다. 호출자 넷:

| 호출자 | 넘길 값 |
|---|---|
| `DailyNavScheduler` (마감 워크플로우) | `ctx.ymd` — 워크플로우가 정한 날 |
| `SyncAccountUseCase` | KST 오늘 |
| `AccountController` ×2 | KST 오늘 |

`DailyNavScheduler.recordDailySnapshots()`도 `ymd`를 받고, `NavSnapshotAction`이 `ctx.ymd`를 흘려보낸다.

**기본값을 두지 않는다.** `date: LocalDate = LocalDate.now()` 같은 기본 인자를 두면 호출자가 빠뜨렸을 때 지금과 똑같이 조용히 UTC로 돌아가고, 증상은 "하루 밀림"이라 눈에 안 띈다. 넷 다 명시적으로 넘긴다.

## 4. `ClosingScheduler` — 남기되 끈다

`@ConditionalOnProperty(name = ["closing.scheduler.enabled"], havingValue = "true")`를 걸고 기본 off.

`FxRateScheduler`가 정확히 이 모양이다(`fx.scheduler.enabled`, 운영에서 `FX_SCHEDULER_ENABLED=true`). 코드를 지우지 않는 이유는 유료 플랜으로 올라가면 인프로세스가 더 단순하기 때문이고, 켜 두지 않는 이유는 인스턴스가 우연히 깨어 있을 때 외부 트리거와 겹쳐 도는 게 헷갈리기 때문이다. Redis 락(`WfLockPort`)이 있어 위험하지는 않다 — 순수하게 관측 가능성 문제다.

## 5. 첫 실행에서 벌어질 일

여섯 단계가 **처음으로** 깨어난다. 예상되는 모양을 미리 적어 두어야 Actions 로그를 보고 놀라지 않는다.

- **`S010` 전 계좌 동기화** — `DailyAccountSyncer.syncAll()`. 전건 실패일 때만 ERROR, 아니면 `synced=n failed=m total=k` 요약
- **`S020`·`S040` 전 사용자 대사** — 사용자별 `runCatching` 격리. **한 명이라도 성공하면 통과**한다(`if (ok == 0 && failed > 0) error(...)`). `S030`이 게이트에서 막힐 확률은 낮다
- **`S030` NAV 스냅샷** — 목표
- **`S050` 수동 마감 확인** — `auto_manual = 'M'`이라 매일 PENDING으로 남는다. **설계 의도이지 실패가 아니다**
- **`S060` 월마감 리포트** — `term_gb = M`, `date_term = -1`, `date_gb = 'B'` → 말일 영업일에만

## 6. 검증

- **토큰 인증이 실패-닫힘인가** — **설정 토큰이 비면 503**(엔드포인트를 닫는다), **제시 토큰이 다르면 401**이다. 둘은 다른 사고를 가리키므로 섞으면 안 된다: 503은 서버에 `SCHEDULER_TOKEN`을 안 넣은 것이고, 401은 대시보드와 GitHub 시크릿 사이를 손으로 옮기다 개행이 붙은 것이다(수집 트리거가 이미 겪은 함정)
- **`record()`가 넘겨받은 날짜를 쓰는가** — 변이: `LocalDate.now()`로 되돌리면 실패해야 한다
- **`NavSnapshotAction`이 `ctx.ymd`를 흘려보내는가** — 변이: 상수 날짜로 바꾸면 실패해야 한다
- **엔드포인트가 KST 오늘을 구하는가** — 크론 표현식 자체는 `.github/` 아래 YAML이라 클래스패스 밖이고 문자열 단언은 의미가 없다. 대신 **검증할 값이 있는 쪽**을 테스트한다: 트리거가 `runDaily`에 넘기는 날짜가 UTC 기준 오늘이 아니라 KST 기준 오늘인지. UTC 15:00~23:59 구간(= KST 익일)에서 갈리므로 그 시각을 고정해 확인한다. UTC↔KST 환산 근거는 워크플로 주석에 남긴다
- **배포 후**: 크론 한 번 → `wf_job_log`에 `S010~S040` 행 + `performance_daily`에 그날 행 + `nav_currency_daily`에 통화 행(AF-106). **이틀 뒤 수익률 화면의 분해 블록이 뜨는지**

## 7. 범위 밖

- **`S050` 자동화하지 않는다.** 운영자 확인 단계이고, 매일 PENDING으로 남는 것이 설계다
- **`DailyNavScheduler`의 사용자 집합 불일치를 안 건드린다.** `ClosingUserSource`는 `ua_accounts` 기준인데 `DailyNavScheduler`는 `ua_assets`를 직접 group by 한다 — 계좌는 있고 자산이 없는 사용자는 NAV 행이 안 생긴다. 지금 증상의 원인이 아니므로 이번 범위에 넣지 않는다
- **`toKrw`가 날짜를 안 받는 문제**(AF-106 설계에서 범위 밖으로 둔 것)는 그대로 둔다. 이 변경은 과거 스냅샷을 재계산하지 않는다

## 관련

- AF-103 수집 스케줄러 — GitHub Actions 크론 패턴의 원형
- AF-106 수익 기여도 분해 — 이 수정이 데이터 공급원이다
- AF-107 벤치마크 비교 — 한 달치 NAV가 필요해 이것에 막혀 있다
