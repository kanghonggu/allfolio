# 일별 마감 워크플로우 트리거 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 마감 워크플로우를 GitHub Actions 크론이 매일 깨우게 해서 `performance_daily`가 매일 쌓이게 한다.

**Architecture:** 코드는 전부 이미 있다 — `ClosingScheduler` → `WfStepExecutor.runDaily` → `S030 NAV 스냅샷` 배선이 완결돼 있고 `wf_job_log`가 비어 있을 뿐이다. Render 무료 플랜에서 잠든 인스턴스는 `@Scheduled`를 못 뛴다. 수집기가 이미 쓰는 패턴(외부 크론 → 토큰 인증 HTTP 트리거)을 마감에도 적용하고, 그 김에 `record()`가 UTC 날짜를 쓰던 것을 워크플로우가 정한 날짜로 바꾼다.

**Tech Stack:** Kotlin / Spring Boot / JUnit 5 + Mockito + MockMvc standalone / GitHub Actions

**설계 문서:** `docs/superpowers/specs/2026-08-15-daily-closing-trigger-design.md`

---

## 사전 필독

**1. 진단은 끝났다. 액션·시드·게이트를 고치지 말 것.** `wf_step`/`wf_sub_step` 시드에 `S030-1 'NAV 스냅샷(전 사용자)'`이 `action_ref = 'NAV_SNAPSHOT'`으로 등록돼 있고, `NavSnapshotAction` 빈도 있다. 안 도는 이유는 트리거 하나뿐이다.

**2. 컨테이너는 UTC다.** Dockerfile·application.yml·render.yaml 어디에도 TZ 설정이 없다. 이 저장소에는 "Render 컨테이너는 UTC라…" 경고 주석이 **세 군데** 따로 적혀 있다. 날짜를 다루는 모든 줄에서 이걸 기억할 것.

**3. 토큰 인증의 상태 코드는 두 가지다.** `SchedulerTriggerController.authorize()`는 **설정 토큰이 비면 503**(엔드포인트를 닫는다), **제시 토큰이 다르면 401**이다. 둘을 섞지 말 것.

**4. `ClosingInProgressException`은 이미 매핑돼 있다.** `GlobalExceptionHandler`가 409로 바꾼다. 트리거 컨트롤러에서 다시 매핑하지 말 것.

**5. KST 상수는 파일마다 private으로 둔다.** 공유 헬퍼가 없고, 이 저장소는 파일별 `private val KST = ZoneId.of("Asia/Seoul")` 관례를 쓴다. 새 공용 유틸을 만들지 말 것 — 요청 범위 밖이다.

---

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `unified-asset/.../usecase/PerformanceSnapshotService.kt` | `record()`가 날짜를 받는다 | 1 |
| `unified-asset/.../usecase/DailyNavScheduler.kt` | `recordDailySnapshots(ymd)` | 1 |
| `unified-asset/.../usecase/SyncAccountUseCase.kt` | KST 오늘을 넘긴다 | 1 |
| `unified-asset/.../api/AccountController.kt` | KST 오늘을 넘긴다 (2곳) | 1 |
| `backend-app/.../closing/ClosingActions.kt` | `NavSnapshotAction`이 `ctx.ymd`를 흘려보낸다 | 1 |
| `backend-app/.../api/scheduler/SchedulerTriggerController.kt` | 마감 트리거 엔드포인트 | 2 |
| `backend-app/.../closing/ClosingScheduler.kt` | 설정으로 끈다 | 3 |
| `.github/workflows/closing.yml` | 매일 두 번 깨운다 | 4 |

---

## Task 1: 날짜를 워크플로우가 정하게 한다

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/PerformanceSnapshotService.kt`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DailyNavScheduler.kt`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/SyncAccountUseCase.kt:100`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/AccountController.kt:267,283`
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/closing/ClosingActions.kt:93-101`
- Create: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/PerformanceSnapshotDateTest.kt`

### 왜 이걸 먼저 하나

자정 KST = UTC 전날 15:00이다. `record()`가 `LocalDate.now()`(UTC)를 쓰므로 워크플로우가 D를 실행하는데 데이터는 D−1에 앉는다. **트리거만 붙이고 이걸 안 고치면 `wf_job_log.ymd`와 `performance_daily.date`가 영원히 하루씩 어긋난다.**

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/PerformanceSnapshotDateTest.kt`

**무엇을 못박아야 하는가**: `record()`가 **호출자가 준 날짜**를 `performance_daily`에 넣는다는 것. 이 테스트가 없으면 누가 `LocalDate.now()`로 되돌려도 아무것도 안 깨진다 — 컨테이너가 UTC라 증상이 "하루 밀림"이고, 그건 눈으로 안 보인다.

**합격 기준(이것만 만족하면 방식은 자유)**: Step 6의 변이 — `date` 파라미터 사용을 `LocalDate.now()`로 되돌리기 — 에서 **이 테스트가 실패해야 한다.** 실패하지 않는 테스트는 쓴 의미가 없다.

기계적인 부분이라 방식은 구현자가 정한다. `PerformanceSnapshotService`는 `JdbcTemplate` 하나만 의존하므로 선택지는 대체로 셋이다:

- **`jdbc.update(...)`에 넘어간 인자를 가로채 3번째(날짜)를 확인한다.** 가장 직접적이다. Kotlin에서 `JdbcTemplate.update(String, vararg Any?)`의 vararg 캡처가 까다로우니, `thenAnswer`로 `invocation.arguments`를 통째로 보관하는 쪽이 `ArgumentCaptor`보다 수월하다. 전일/최초 NAV 조회(`jdbc.query`)는 빈 목록을 돌려주게 스텁하면 `dailyReturn`·`cumulativeReturn`이 0이 되어 계산이 단순해진다
- **모듈에 이미 있는 H2 통합 테스트 설정을 쓴다.** `:unified-asset:test`가 Hibernate로 스키마를 만드는 것이 로그에 보인다 — `performance_daily`가 그 스키마에 있으면 `record()` 호출 후 실제로 조회해 날짜를 확인할 수 있다. 가장 강한 검증이지만 그 테이블이 unified-asset 쪽 엔티티가 아니라 snapshot 모듈 소유라 안 만들어질 수 있다. **먼저 확인하고, 없으면 다른 방식으로 갈 것**
- **인자 조립을 순수 함수로 뽑아 그것을 테스트한다.** 가장 쉽지만 가장 약하다 — `record()`가 그 함수를 실제로 쓰는지는 안 지켜진다. **다른 둘이 다 막혔을 때만** 쓰고, 그때는 변이 테스트에서 실제로 잡히는지 반드시 확인할 것

**어느 방식을 택했고 왜인지 보고할 것.** 막다른 길을 만났으면 그것도 보고할 것 — 다음 사람이 같은 길을 다시 걷지 않게.

- [ ] **Step 2: 테스트를 돌려 컴파일/실패를 확인한다**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests '*PerformanceSnapshotDateTest*'
```

Expected: 컴파일 실패 — `record`가 인자 3개를 안 받는다.

- [ ] **Step 3: `record()`가 날짜를 받게 한다**

`PerformanceSnapshotService.kt`에서 `fun record(userId: UUID, nav: BigDecimal) {` 를 바꾸고, 본문 첫 줄의 `val today = LocalDate.now()` 를 **지운다**:

```kotlin
    /**
     * 오늘의 NAV 스냅샷을 performance_daily에 기록한다.
     *
     * **[date]에 기본값을 두지 않는다.** `LocalDate.now()`를 기본 인자로 두면 호출자가
     * 빠뜨렸을 때 조용히 UTC 날짜로 돌아가는데, 컨테이너가 UTC라 자정 KST 실행이 전날에
     * 앉는다. 증상이 "하루 밀림"이라 눈에 안 띄고, wf_job_log.ymd와 영원히 어긋난다.
     * 호출자 넷이 각자 무슨 날짜인지 알고 있으므로 전부 명시적으로 넘긴다.
     *
     * tenant_id = portfolio_id = userId (unified-asset은 사용자=포트폴리오 단위)
     */
    fun record(userId: UUID, nav: BigDecimal, date: LocalDate) {
```

본문에서 `today`를 쓰던 자리를 전부 `date`로 바꾼다 — 전일 NAV 조회의 `date < ?` 바인딩, INSERT의 날짜 인자, 로그 문자열 세 곳이다. **`LocalDate.now()`가 이 파일에서 완전히 사라져야 한다.**

- [ ] **Step 4: 호출자 넷을 고친다**

`DailyNavScheduler.kt` — 시그니처와 호출:

```kotlin
    /**
     * @param ymd 마감 워크플로우가 정한 일자. **`LocalDate.now()`로 대체하지 말 것** —
     *            자정 KST 실행은 UTC로 전날이라 스냅샷이 하루 밀린다.
     * @return 스냅샷 기록 사용자 수
     */
    fun recordDailySnapshots(ymd: LocalDate): Int {
```

그리고 아래쪽 호출을 `snapshotService.record(userId, nav, ymd)` 로. `import java.time.LocalDate` 추가.

`ClosingActions.kt`의 `NavSnapshotAction`:

```kotlin
/** S030 — NAV 스냅샷(전 사용자, 구 DailyNavScheduler 2단계). */
@Component
class NavSnapshotAction(
    private val dailyNavScheduler: DailyNavScheduler,
) : WfAction {
    override val ref = "NAV_SNAPSHOT"
    // ctx.ymd를 흘려보낸다 — 여기서 LocalDate.now()를 쓰면 워크플로우가 정한 날과
    // 데이터가 갈라진다(컨테이너 UTC, 자정 KST = UTC 전날 15:00)
    override fun execute(ctx: WfContext): WfActionResult =
        WfActionResult("snapshots=${dailyNavScheduler.recordDailySnapshots(ctx.ymd)}")
}
```

`SyncAccountUseCase.kt:100` — 이 파일은 `ZoneId`를 이미 import 하고 있고 219행에서 `LocalDate.now(ZoneId.of("Asia/Seoul"))`를 쓴다. 같은 표현을 쓴다:

```kotlin
            snapshotService.record(account.userId, nav, LocalDate.now(ZoneId.of("Asia/Seoul")))
```

`AccountController.kt:267,283` — 두 곳 모두:

```kotlin
        snapshotService.record(userId, nav, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))
```

파일에 `LocalDate`/`ZoneId` import가 없으면 추가하고, 이미 있으면 정규화된 이름을 쓴다. **`ZoneId.systemDefault()`를 쓰지 말 것** — 그게 UTC다.

- [ ] **Step 5: 테스트와 컴파일**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test :backend-app:compileKotlin :backend-app:compileTestKotlin
```

Expected: PASS. 기존 테스트가 `record(userId, nav)` 2인자로 부르고 있으면 컴파일이 깨진다 — **날짜를 명시적으로 넘겨 고치고, 단언은 건드리지 말 것.** 어떤 파일을 고쳤는지 보고한다.

- [ ] **Step 6: 변이 테스트**

`record()`의 `date` 파라미터 사용을 `LocalDate.now()`로 되돌린다 → Step 1의 테스트가 **실패해야 한다**. 확인 후 원복.

실패하지 않으면 그 테스트는 아무것도 안 지키는 것이다 — 크게 보고할 것.

- [ ] **Step 7: 커밋**

```bash
git add allfolio-backend/unified-asset/ allfolio-backend/backend-app/src/main/kotlin/com/allfolio/closing/ClosingActions.kt
git commit -m "fix(closing): 스냅샷 날짜를 워크플로우가 정하게 한다 — UTC 컨테이너가 하루를 밀고 있었다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: 마감 트리거 엔드포인트

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/scheduler/SchedulerTriggerController.kt`
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/scheduler/SchedulerTriggerControllerTest.kt`

- [ ] **Step 1: 컨트롤러에 `WfStepExecutor`를 주입하고 엔드포인트를 더한다**

생성자 파라미터 목록 끝(`@Value("\${scheduler.trigger-token:}") private val configuredToken: String,` **앞**)에 추가:

```kotlin
    private val stepExecutor: com.allfolio.workflow.application.WfStepExecutor,
```

> 순서 주의: `configuredToken`이 `@Value` 어노테이션 파라미터라 마지막에 두는 편이 읽기 좋고, 기존 테스트가 위치 인자로 생성하므로 **테스트도 같이 고쳐야 한다**(Step 3).

`/rate` 엔드포인트 아래에 추가:

```kotlin
    /**
     * POST /api/internal/scheduler/closing — 일별 마감 워크플로우 트리거
     *
     * **어드민 컨트롤러에 위임하지 않는다 — 이 파일의 다른 트리거와 다른 유일한 자리다.**
     * [com.allfolio.api.admin.ClosingAdminController.runDay]는 `X-User-Id`를 받아 그 값을
     * 실행자로 `wf_job_log.executor`에 찍는다. 크론에는 어드민 신원이 없고, 실존 인물의 id를
     * 자동 실행에 찍으면 "이 마감을 누가 돌렸나"에 거짓으로 답하게 된다. 위임해서 얻는 것은
     * 409 매핑 한 줄뿐인데 그건 GlobalExceptionHandler가 이미 해 준다.
     * 그래서 [WfStepExecutor.runDaily]를 직접 부른다 — 기본 실행자가 SYSTEM이다.
     *
     * **날짜를 노출하지 않는다.** 다른 트리거와 같은 이유다 — 컨테이너가 UTC라 클라이언트가
     * 날짜를 정하면 하루씩 밀린다. [closingDate]가 KST로 옮겨 정한다.
     *
     * 응답으로 WfRunSummary를 그대로 싣는다. 어느 단계가 게이트에서 스킵됐는지가
     * Actions 잡 요약에서 읽히는 것이 이 엔드포인트의 관측 수단 전부다.
     */
    @PostMapping("/closing")
    fun runClosing(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): WfRunSummary {
        authorize(token)
        return stepExecutor.runDaily(closingDate(Instant.now()))
    }
```

`companion object`에 추가:

```kotlin
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 마감 일자 — UTC 순간을 KST 날짜로 옮긴다.
         *
         * 크론이 UTC 15:00에 뛰면 KST로는 **다음 날** 00:00이다. 그 하루가 이 함수의 존재
         * 이유이고, `LocalDate.now()`를 쓰면 정확히 그 하루를 잃는다.
         * 함수로 뽑은 이유는 테스트가 시각을 고정할 수 있게 하려는 것이다 —
         * 크론 표현식 자체는 `.github/` 아래 YAML이라 값으로 검증할 방법이 없다.
         */
        internal fun closingDate(now: Instant): LocalDate = now.atZone(KST).toLocalDate()
```

필요한 import: `java.time.Instant`, `java.time.ZoneId`, `com.allfolio.workflow.application.WfRunSummary`. (`java.time.LocalDate`는 이미 있다.)

- [ ] **Step 2: `backend-app`이 `workflow` 모듈을 보는지 확인**

```bash
grep -n "workflow" /Users/hong9/IdeaProjects/allfolio/allfolio-backend/backend-app/build.gradle.kts
```

Expected: `implementation(project(":workflow"))` 같은 줄이 있어야 한다. `ClosingScheduler`가 이미 `WfStepExecutor`를 주입받으므로 있을 것이다. 없으면 보고하고 멈출 것 — 빌드 파일을 임의로 고치지 말 것.

- [ ] **Step 3: 테스트를 더한다**

`SchedulerTriggerControllerTest.kt`의 `mvc()` 헬퍼가 위치 인자로 컨트롤러를 만든다. 새 파라미터를 반영한다:

```kotlin
    private val stepExecutor: WfStepExecutor = mock(WfStepExecutor::class.java)

    private fun mvc(token: String) = MockMvcBuilders
        .standaloneSetup(SchedulerTriggerController(admin, indexAdmin, rateAdmin, stepExecutor, token))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()
```

> 실제 생성자 인자 순서에 맞출 것. Step 1에서 어디에 넣었는지에 따라 달라진다.

테스트 넷을 추가한다:

```kotlin
    @Test
    fun `마감 트리거는 KST 오늘로 워크플로우를 돌린다`() {
        val summary = WfRunSummary(LocalDate.of(2026, 8, 16), listOf("S010"), emptyList(), emptyList())
        `when`(stepExecutor.runDaily(any(), anyString())).thenReturn(summary)

        mvc("secret").perform(
            post("/api/internal/scheduler/closing").header("X-Scheduler-Token", "secret")
        ).andExpect(status().isOk)
         .andExpect(jsonPath("$.executedSteps[0]").value("S010"))
    }

    @Test
    fun `토큰이 다르면 401이고 워크플로우를 안 돌린다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/closing").header("X-Scheduler-Token", "wrong")
        ).andExpect(status().isUnauthorized)
        verify(stepExecutor, never()).runDaily(any(), anyString())
    }

    @Test
    fun `설정 토큰이 비면 503으로 닫는다`() {
        // 빈 값을 "토큰 불필요"로 해석하면 SCHEDULER_TOKEN을 빠뜨린 순간 완전 공개된다
        mvc("").perform(
            post("/api/internal/scheduler/closing").header("X-Scheduler-Token", "anything")
        ).andExpect(status().isServiceUnavailable)
        verify(stepExecutor, never()).runDaily(any(), anyString())
    }

    @Test
    fun `UTC 15시는 KST로 다음 날이다`() {
        // 크론이 UTC 15:00에 뛴다. LocalDate.now()를 쓰면 여기서 하루를 잃는다.
        assertEquals(
            LocalDate.of(2026, 8, 16),
            SchedulerTriggerController.closingDate(Instant.parse("2026-08-15T15:00:00Z")),
        )
        // 경계 바로 앞은 같은 날이어야 한다 — 함수가 무조건 +1일 하는 게 아님을 보인다
        assertEquals(
            LocalDate.of(2026, 8, 15),
            SchedulerTriggerController.closingDate(Instant.parse("2026-08-15T14:59:59Z")),
        )
    }
```

필요한 import를 더한다: `com.allfolio.workflow.application.WfStepExecutor`, `com.allfolio.workflow.application.WfRunSummary`, `org.junit.jupiter.api.Assertions.assertEquals`, `java.time.Instant`. (`any`, `anyString`, `never`, `verify`, `LocalDate`, `post`, `status`, `jsonPath`는 파일에 이미 있다.)

> `runDaily`는 `executor`에 기본값이 있어 Kotlin에서 인자 하나로 부를 수 있지만, Mockito 매칭은 **실제로 호출된 시그니처**를 본다. 기본값이 적용되면 두 인자로 호출되므로 `any(), anyString()`으로 잡는다. 안 맞으면 `any()` 하나로 바꿔 보고, 어느 쪽이 맞았는지 보고할 것.

- [ ] **Step 4: 테스트 실행**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test --tests '*SchedulerTriggerControllerTest*'
```

Expected: 기존 테스트 전부 + 새 넷 PASS.

- [ ] **Step 5: 변이 테스트**

| # | 변이 | 실패해야 하는 테스트 |
|---|---|---|
| 1 | `closingDate(Instant.now())` → `LocalDate.now()` | `UTC 15시는 KST로 다음 날이다`는 못 잡는다(순수 함수라 그대로다). **대신 `closingDate` 본문의 `KST`를 `ZoneOffset.UTC`로 바꿔 볼 것** — 그때 실패해야 한다 |
| 2 | `authorize(token)` 호출을 지운다 | `토큰이 다르면 401…`, `설정 토큰이 비면 503…` |

변이 1의 단서가 중요하다: **엔드포인트가 `closingDate`를 실제로 쓰는지는 이 테스트들이 안 지킨다.** 순수 함수만 검증하기 때문이다. 그 연결이 걱정되면 `stepExecutor.runDaily`에 넘어간 날짜를 `ArgumentCaptor`로 잡아 KST 오늘과 비교하는 테스트를 하나 더 쓸 것. **쓸지 말지 판단하고 그 이유를 보고할 것.**

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/scheduler/ allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/scheduler/
git commit -m "feat(closing): 마감 워크플로우 외부 트리거 — 잠든 인스턴스는 @Scheduled를 못 뛴다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: `ClosingScheduler`를 설정으로 끈다

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/closing/ClosingScheduler.kt`
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`

- [ ] **Step 1: `@ConditionalOnProperty`를 건다**

`FxRateScheduler`(`allfolio-backend/backend-app/src/main/kotlin/com/allfolio/fx/FxRateScheduler.kt:21`)와 같은 모양이다.

```kotlin
@Component
@ConditionalOnProperty(name = ["closing.scheduler.enabled"], havingValue = "true")
class ClosingScheduler(private val stepExecutor: WfStepExecutor) {
```

import 추가: `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`

클래스 KDoc에 왜 꺼져 있는지 남긴다:

```kotlin
/**
 * 마감 워크플로우 자정 트리거 (P3 #24) — 구 DailyNavScheduler @Scheduled의 대체.
 * 실제 배치 로직은 전부 WfAction으로 이동, 여기는 runDaily 호출만 하는 얇은 트리거.
 * 게이트 SKIP된 단계 재시도를 위해 오전 재트리거 1회(01:30) 포함.
 *
 * **기본 off다 (`closing.scheduler.enabled`).** Render 무료 웹 서비스는 15분 유휴 시 잠들고,
 * 자정 KST에는 아무도 앱을 안 쓰므로 인스턴스가 자고 있다. 잠든 인스턴스에서는 @Scheduled가
 * 뛰지 않는다 — 실제로 wf_job_log가 통째로 비어 있었다. 트리거는 GitHub Actions 크론이
 * `POST /api/internal/scheduler/closing`으로 대신한다(`.github/workflows/closing.yml`).
 *
 * 코드를 지우지 않는 이유는 유료 플랜에서는 이쪽이 더 단순하기 때문이고, 켜 두지 않는 이유는
 * 인스턴스가 우연히 깨어 있을 때 외부 트리거와 겹쳐 도는 게 헷갈리기 때문이다.
 * Redis 락(WfLockPort)이 있어 위험하지는 않다 — 순수하게 관측 가능성 문제다.
 */
```

- [ ] **Step 2: `application.yml`에 키를 선언한다**

`fx.scheduler.enabled`가 선언된 근처를 찾아 같은 자리에 둔다:

```yaml
closing:
  scheduler:
    enabled: false   # GitHub Actions 크론이 대신한다 — .github/workflows/closing.yml
```

> `fx:` 블록이 어떤 모양인지 먼저 볼 것. 들여쓰기와 위치를 그 파일 관례에 맞춘다.

- [ ] **Step 3: 앱이 뜨는지 확인**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :backend-app:test
```

Expected: PASS. `ClosingScheduler`를 주입받는 테스트가 있으면 빈이 사라져 깨진다 — 그런 테스트가 있으면 보고할 것(해당 테스트에서 프로퍼티를 켜는 게 정답이다).

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/closing/ClosingScheduler.kt allfolio-backend/backend-app/src/main/resources/application.yml
git commit -m "chore(closing): 인프로세스 자정 스케줄러를 기본 off로 — 외부 크론이 대신한다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: GitHub Actions 크론

**Files:**
- Create: `.github/workflows/closing.yml`

- [ ] **Step 1: `collect-rate.yml`을 읽는다**

```bash
cat /Users/hong9/IdeaProjects/allfolio/.github/workflows/collect-rate.yml
```

**이 파일이 원형이다.** 재시도 루프·타임아웃·URL 검증·요약 출력·에러 안내를 그대로 따른다. **숫자를 새로 정하지 말 것** — `--max-time 120 --retry 3 --retry-delay 20` = 최악 9분이고 `timeout-minutes: 12`가 그보다 커야 한다는 근거가 그 파일 주석에 적혀 있다.

- [ ] **Step 2: `.github/workflows/closing.yml`을 만든다**

`collect-rate.yml`을 베끼되 아래를 바꾼다:

- `name: Daily Closing`
- 크론 두 개:

```yaml
on:
  schedule:
    - cron: "0 15 * * *"    # UTC 15:00 = KST 익일 00:00
    - cron: "30 16 * * *"   # UTC 16:30 = KST 익일 01:30 — 게이트 SKIP 재시도
  workflow_dispatch:
```

- `concurrency.group: closing` (`cancel-in-progress: false` 유지)
- 엔드포인트: `"${BACKEND_URL}/api/internal/scheduler/closing"`
- 잡·스텝 이름을 마감에 맞게

파일 상단 주석에 다음을 반드시 남긴다:

```yaml
# Render 무료 플랜에는 크론 잡이 없고, 무료 웹 서비스는 15분 유휴 시 잠들어 인스턴스 안의
# @Scheduled만으로는 주기 실행이 성립하지 않는다. 실제로 wf_job_log가 통째로 비어 있었다 —
# ClosingScheduler는 한 번도 뛴 적이 없다. collect-*.yml과 같은 구조다.
#
# **요일 필터(1-5)를 걸지 말 것.** wf_step 시드에서 S010~S050이 holiday_except_yn = FALSE이고
# WfScheduleJudge가 `D -> !holidayExcept || isBizDay`로 판정하므로 주말·공휴일 포함 매일
# 실행이 정의된 동작이다. 공휴일을 쉬어야 하는 단계가 생기면 그건 wf_step 데이터로 정한다.
#
# **여기에 요일 필터를 나중에 추가하려는 사람은 이 문단을 먼저 읽을 것.** cron은 UTC로 해석되고
# UTC 15:00은 KST 날짜 경계라, 요일을 적으면 그건 UTC 요일이 된다 — KST로는 다음 날이므로
# 금요일 실행이 UTC 금요일이면 KST 토요일이 되어 1-5에서 조용히 사라진다. collect-rate.yml이
# 같은 함정을 반대편(UTC 09:10, 경계 한참 전)에서 설명해 뒀다. 지금은 요일 필터가 없어
# 이 함정에 걸리지 않는다.
#
# 두 시각은 구 ClosingScheduler의 자정 + 01:30을 그대로 옮긴 것이다. 01:30은 게이트에서
# SKIP된 단계의 재시도용이고, 이미 SUCCESS인 하위단계는 WfStepExecutor가 멱등 스킵한다.
```

`curl` 앞 주석은 마감에 맞게 고친다 — 특히 재시도가 안전한 근거:

```yaml
          # 재시도가 안전한 이유는 마감이 멱등하기 때문이다 — WfStepExecutor가 wf:lock:{ymd}로
          # 중복 실행을 막고(경합은 409), 이미 SUCCESS인 하위단계는 재실행하지 않는다.
```

실패 안내는 상태 코드별로:

```yaml
          # 401 = 토큰 불일치(대시보드와 시크릿 사이를 손으로 옮기다 개행이 붙는 게 흔하다)
          # 503 = 서버에 scheduler.trigger-token이 미설정 — 엔드포인트가 닫혀 있다
          # 409 = 같은 일자 마감이 이미 실행 중(두 크론이 겹쳤거나 어드민이 수동 실행 중)
```

- [ ] **Step 3: YAML이 유효한지 확인**

```bash
python3 -c "import yaml,sys; d=yaml.safe_load(open('/Users/hong9/IdeaProjects/allfolio/.github/workflows/closing.yml')); print([c['cron'] for c in d[True]['schedule']])"
```

Expected: `['0 15 * * *', '30 16 * * *']`

> `on:`이 YAML에서 boolean `True`로 파싱되는 것은 정상이다.

- [ ] **Step 4: 커밋**

```bash
git add .github/workflows/closing.yml
git commit -m "feat(closing): 마감 워크플로우 일일 크론 — 요일 필터를 걸면 안 되는 이유를 주석에 못박는다

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: PR + 배포 후 검증

- [ ] **Step 1: 전체 빌드**

```bash
cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew build -x test && ./gradlew test
```

- [ ] **Step 2: PR 생성**

```bash
git push -u origin fix/daily-closing-trigger
```

PR 본문에 반드시 적는다:
- **`SCHEDULER_TOKEN`과 `BACKEND_URL` 시크릿은 이미 있다**(기존 수집 워크플로가 쓴다). 새로 만들 것 없음
- **마이그레이션 없음**
- 머지 후 **첫 크론은 다음 KST 자정**이다. 즉시 확인하려면 Actions에서 `workflow_dispatch`로 수동 실행

- [ ] **Step 3: 배포 후 — 수동 실행 한 번**

Actions → Daily Closing → Run workflow. 잡 요약에서 확인:
- HTTP `200`
- `executedSteps`에 `S010`~`S040`이 있는가
- `gateSkippedSteps`에 무엇이 있는가 (`S060`은 말일이 아니면 `notScheduledSteps`가 정상)

- [ ] **Step 4: DB 확인**

```sql
SELECT ymd, step_cd, sub_step_cd, status, executor, remark, LEFT(error_detail, 200) AS err
FROM wf_job_log ORDER BY ymd DESC, step_cd;
```

`executor`가 `SYSTEM`이어야 한다 — 어드민 id가 찍혔으면 위임을 안 깬 것이다.

```sql
SELECT portfolio_id, date, nav FROM performance_daily ORDER BY date DESC LIMIT 10;
```

**오늘(KST) 날짜 행이 있어야 한다.** 어제 날짜면 Task 1이 안 먹은 것이다.

- [ ] **Step 5: AF-106 연결 확인**

```sql
SELECT date, currency, value_native, fx_rate FROM nav_currency_daily ORDER BY date DESC LIMIT 10;
```

**행이 없는 게 정상이다.** `DailyNavScheduler`는 `SnapshotTriggerService`를 안 타므로 통화 행을 안 쓴다. 이건 알려진 한계이고 이번 범위 밖이다 — 결과를 기록하고 후속으로 올린다.

- [ ] **Step 6: 이틀 뒤**

- `performance_daily`에 이틀 연속 행이 있는가 (크론이 실제로 매일 도는가)
- `/unified/reports/returns`가 400 대신 수익률을 내는가

---

## 완료 기준

- [ ] `wf_job_log`에 `executor = 'SYSTEM'` 행이 생긴다
- [ ] `performance_daily.date`가 **KST 오늘**이다 (어제가 아니다)
- [ ] 이틀 연속 행이 쌓인다
- [ ] 수익률 화면이 400을 안 낸다
- [ ] 변이 테스트: 날짜 파라미터 되돌리기 / `closingDate`의 KST를 UTC로 / `authorize()` 제거 — 전부 실패를 확인했다
- [ ] `nav_currency_daily`가 이 경로로는 안 채워진다는 것을 실측으로 확인하고 후속으로 올렸다
