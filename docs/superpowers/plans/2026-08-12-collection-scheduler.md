# AF-103 수집 스케줄러 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GitHub Actions 예약 워크플로가 전용 토큰으로 하나은행 고시환율 수집을 평일 4회 자동 실행한다.

**Architecture:** 스케줄은 워크플로 YAML에, 수집 로직은 이미 있는 `HanaFxCollectService`에 그대로 둔다. 새 컨트롤러 `SchedulerTriggerController`가 `X-Scheduler-Token`을 상수시간 비교로 검증한 뒤 기존 `FxRateAdminController.collectHana`에 위임한다. 새 수집 코드도, 마이그레이션도 없다.

**Tech Stack:** Kotlin 1.9.25 / Spring Boot 3.2.5 / Spring Security / JUnit5 + MockMvc / GitHub Actions

**Spec:** `docs/superpowers/specs/2026-08-12-collection-scheduler-design.md`

---

## 사전 필독 (모든 태스크 공통)

- **Gradle 테스트는 반드시 `--rerun-tasks`** 를 붙인다. 없으면 전부 UP-TO-DATE로 보고되고 아무것도 실행되지 않는다.
- 작업 디렉터리는 저장소 루트 `/Users/hong9/IdeaProjects/allfolio`. Gradle 명령은 `allfolio-backend`에서 실행한다.
- 브랜치는 `feat/af-103-collection-scheduler` (이미 생성돼 있고 설계 문서 커밋이 올라가 있다).
- 이 기능의 코드는 전부 `backend-app` 모듈이다. `unified-asset`은 건드리지 않는다.

## 파일 구조

| 파일 | 책임 |
|---|---|
| `backend-app/src/main/kotlin/com/allfolio/api/scheduler/SchedulerTriggerController.kt` (신규) | 토큰 검증 + 수집 위임. 이 파일에는 수집 로직이 없다 |
| `backend-app/src/main/kotlin/com/allfolio/config/SecurityConfig.kt` (수정) | `/api/internal/scheduler/**`를 permitAll로 |
| `backend-app/src/main/resources/application.yml` (수정) | `scheduler.trigger-token` 설정 |
| `backend-app/src/test/kotlin/com/allfolio/api/scheduler/SchedulerTriggerControllerTest.kt` (신규) | 토큰 검증 5케이스 + 위임 상태 매핑 |
| `backend-app/src/test/kotlin/com/allfolio/config/SecurityConfigAdminTest.kt` (수정) | permitAll이 실제로 붙었는지. 기존 슬라이스 컨텍스트 재사용 |
| `.github/workflows/collect-fx.yml` (신규) | cron 스케줄 + curl 호출 + 잡 요약 |

---

### Task 1: 설정 키 추가

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/resources/application.yml`

- [ ] **Step 1: `fx:` 블록 바로 앞에 최상위 `scheduler:` 블록을 추가한다**

`application.yml`에서 `# FX 환율 자동 수집` 주석으로 시작하는 `fx:` 블록을 찾는다(대략 246행).
그 **바로 위**에 다음을 삽입한다. (최상위 `scheduler:` 키는 현재 없다 — `fx.scheduler`와는 다른 키다.)

```yaml
# 외부 스케줄러(GitHub Actions) 트리거 토큰 — AF-103
# 비어 있으면 /api/internal/scheduler/** 가 503으로 닫힌다. 빈 값이 "토큰 불필요"가 되면
# 환경변수를 빠뜨린 순간 엔드포인트가 완전 공개되므로, 설정 누락의 기본값은 "닫힘"이다.
scheduler:
  trigger-token: ${SCHEDULER_TOKEN:}

```

- [ ] **Step 2: YAML이 깨지지 않았는지 확인한다**

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:compileKotlin --no-daemon -q && echo OK
```
Expected: `OK` (컴파일은 YAML을 읽지 않으므로 여기선 문법만 눈으로 확인한다. 실제 검증은 Task 3의 테스트가 한다.)

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/resources/application.yml
git commit -m "feat(af-103): 스케줄러 트리거 토큰 설정 키 추가"
```

---

### Task 2: SecurityConfig에 스케줄러 경로 개방

**Files:**
- Modify: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/SecurityConfig.kt`

- [ ] **Step 1: permitAll 목록에 스케줄러 경로를 추가한다**

`.requestMatchers("/api/broker/*/callback").permitAll()` 줄을 찾아, 그 **바로 아래**에 추가한다:

```kotlin
                    // AF-103: 외부 스케줄러(GitHub Actions) 트리거.
                    // Security는 통과시키고 SchedulerTriggerController가 X-Scheduler-Token을 검증한다.
                    // 어드민 JWT는 15분 만료라 CI가 들고 있을 수 없고, 어드민 비밀번호를 CI 시크릿에
                    // 넣으면 유출 시 전권이 넘어간다. 수집 트리거만 가능한 토큰이 폭발 반경이 가장 작다.
                    .requestMatchers("/api/internal/scheduler/**").permitAll()
```

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:compileKotlin --no-daemon -q && echo OK
```
Expected: `OK`

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/SecurityConfig.kt
git commit -m "feat(af-103): /api/internal/scheduler/** permitAll (토큰 검증은 컨트롤러가)"
```

---

### Task 3: SchedulerTriggerController — 실패 테스트 먼저

**Files:**
- Test: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/scheduler/SchedulerTriggerControllerTest.kt` (신규)

`FxRateAdminController`를 목으로 세우고 MockMvc standalone으로 컨트롤러만 검증한다.
standalone을 쓰는 이유는 Security 필터 체인을 태우지 않기 위해서다 — 이 테스트가 확인할 대상은
컨트롤러의 토큰 검증이지 Security 설정이 아니다(Security 쪽은 Task 6이 본다).

**`mockito-kotlin`은 이 저장소에 없다.** `build.gradle.kts`의 테스트 의존성은
`spring-boot-starter-test`뿐이고, 기존 테스트는 전부 순수 `org.mockito.Mockito`를 쓴다.
아래 코드도 그렇게 돼 있다 — `org.mockito.kotlin.*`를 import하면 컴파일이 깨진다.
의존성을 새로 추가하지 말 것.

- [ ] **Step 1: 실패 테스트를 작성한다**

```kotlin
package com.allfolio.api.scheduler

import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.fx.hana.HanaCollectSummary
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.LocalDate

class SchedulerTriggerControllerTest {

    // JUnit5는 테스트마다 인스턴스를 새로 만들므로 목이 테스트 간에 새지 않는다.
    private val admin: FxRateAdminController = mock(FxRateAdminController::class.java)

    private val summary = HanaCollectSummary(
        requestedDate = LocalDate.of(2026, 8, 12),
        baseDate = LocalDate.of(2026, 8, 12),
        roundNo = 286,
        currencies = 58,
        inserted = 58,
        updated = 0,
        unchanged = 0,
        skipped = 0,
    )

    private fun mvc(token: String) = MockMvcBuilders
        .standaloneSetup(SchedulerTriggerController(admin, token))
        .build()

    @Test
    fun `토큰이 맞으면 수집을 실행하고 요약을 돌려준다`() {
        `when`(admin.collectHana(null, false)).thenReturn(ResponseEntity.ok(summary))

        mvc("secret").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "secret")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.roundNo").value(286))
            .andExpect(jsonPath("$.currencies").value(58))

        // 스케줄 실행은 절대 force를 쓰지 않는다 — 2% 급변동 가드가 살아있어야 한다
        verify(admin).collectHana(null, false)
    }

    @Test
    fun `토큰이 틀리면 401이고 수집을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "wrong")
        ).andExpect(status().isUnauthorized)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    @Test
    fun `헤더가 없으면 401이고 수집을 부르지 않는다`() {
        mvc("secret").perform(post("/api/internal/scheduler/fx/hana-collect"))
            .andExpect(status().isUnauthorized)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // 이 테스트가 이 파일에서 가장 중요하다.
    // 빈 설정이 "토큰 불필요"로 해석되면 환경변수를 빠뜨린 순간 엔드포인트가 완전 공개된다.
    @Test
    fun `설정 토큰이 비어 있으면 요청 토큰이 무엇이든 503으로 닫는다`() {
        mvc("").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "anything")
        ).andExpect(status().isServiceUnavailable)

        mvc("").perform(post("/api/internal/scheduler/fx/hana-collect"))
            .andExpect(status().isServiceUnavailable)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // 위임이 실제로 어드민 컨트롤러의 상태 매핑을 물려받는지 확인한다.
    // 이게 없으면 SchedulerTriggerController가 조용히 500을 뱉어도 테스트가 통과한다.
    @Test
    fun `수집이 안전장치에 걸리면 422가 그대로 전달된다`() {
        `when`(admin.collectHana(null, false))
            .thenThrow(ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "2% 초과 변동"))

        mvc("secret").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "secret")
        ).andExpect(status().isUnprocessableEntity)
    }
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*SchedulerTriggerControllerTest*' --rerun-tasks --no-daemon
```
Expected: FAIL — `Unresolved reference: SchedulerTriggerController` (컴파일 실패)

- [ ] **Step 3: 커밋하지 않는다**

컴파일이 깨진 상태다. Task 4에서 구현과 함께 커밋한다.

---

### Task 4: SchedulerTriggerController 구현

**Files:**
- Create: `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/scheduler/SchedulerTriggerController.kt`

- [ ] **Step 1: 컨트롤러를 작성한다**

```kotlin
package com.allfolio.api.scheduler

import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.fx.hana.HanaCollectSummary
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 외부 스케줄러(GitHub Actions) 전용 트리거 (AF-103).
 *
 * Render 무료 플랜에는 크론 잡이 없고, 무료 웹 서비스는 15분 유휴 시 잠들어
 * 인스턴스 안의 `@Scheduled`만으로는 주기 실행이 성립하지 않는다.
 * 외부에서 깨워야 하므로, 그 신호를 곧 트리거로 쓴다.
 *
 * **어드민 JWT를 안 쓰는 이유**: 15분 만료라 CI가 들고 있을 수 없다.
 * CI가 매번 로그인하게 하면 어드민 비밀번호가 시크릿에 들어가고, 유출 시 전권이 넘어간다.
 * 수집 트리거만 가능한 토큰은 유출돼도 할 수 있는 일이 "멱등한 수집을 여러 번 도는 것"뿐이다.
 *
 * 이 경로는 SecurityConfig에서 permitAll이다 — 인증은 여기서 한다.
 */
@RestController
@RequestMapping("/api/internal/scheduler")
class SchedulerTriggerController(
    private val fxAdmin: FxRateAdminController,
    @Value("\${scheduler.trigger-token:}") private val configuredToken: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * POST /api/internal/scheduler/fx/hana-collect — 하나은행 고시환율 수집 트리거
     *
     * **`force`를 노출하지 않는다.** 스케줄 실행은 항상 `force = false`여야 한다.
     * AF-99의 2% 급변동 가드가 걸리면 422가 나가고 워크플로 잡이 실패하는데, 그게 의도한 동작이다 —
     * 진짜 크게 움직인 날은 사람이 값을 보고 판단해야 하고 Actions의 실패 표시가 그 신호다.
     * 스케줄러가 조용히 force로 뚫으면 파싱 오류로 튄 값이 그대로 저장된다.
     *
     * **날짜도 노출하지 않는다.** [FxRateAdminController.collectHana]가 null을 KST 오늘로 해석한다.
     * Render 컨테이너는 UTC라 이 기본값 처리가 없으면 09:10 KST 실행이 "어제"를 조회한다.
     *
     * 어드민 컨트롤러에 위임하는 이유는 그쪽의 예외→상태 매핑(422 안전장치 / 502 은행 응답 이상 /
     * 409 경합)이 Actions 로그를 읽는 사람에게 그대로 필요해서다. 복제하면 두 벌이 갈라지고,
     * 공용 헬퍼로 뽑으면 "이 엔드포인트에서만 이렇게 하는 이유"를 적은 주석들이 근거를 잃는다.
     * 컨트롤러가 컨트롤러를 주입받는 게 낯설다는 건 알지만 대안 둘 다 이보다 나쁘다.
     * **이 위임을 "정리"하지 말 것.**
     */
    @PostMapping("/fx/hana-collect")
    fun collectHanaFx(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<HanaCollectSummary> {
        authorize(token)
        return fxAdmin.collectHana(null, false)
    }

    /**
     * 설정 토큰이 비어 있으면 503으로 닫는다 — 이 메서드에서 가장 중요한 분기다.
     * 빈 값을 "토큰 불필요"로 해석하면 SCHEDULER_TOKEN을 빠뜨린 순간 엔드포인트가 완전 공개된다.
     * 설정 누락의 기본값은 "열림"이 아니라 "닫힘"이어야 한다.
     */
    private fun authorize(token: String?) {
        if (configuredToken.isBlank()) {
            log.warn("[Scheduler] scheduler.trigger-token 미설정 — 트리거 엔드포인트를 닫는다")
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "스케줄러 토큰이 설정되지 않았습니다.",
            )
        }
        // 상수 시간 비교. 길이는 새지만 내용은 새지 않는다.
        val presented = token?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        if (!MessageDigest.isEqual(presented, configuredToken.toByteArray(StandardCharsets.UTF_8))) {
            log.warn("[Scheduler] 트리거 토큰 불일치 — 거부")
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다.")
        }
    }

    companion object {
        private const val TOKEN_HEADER = "X-Scheduler-Token"
    }
}
```

- [ ] **Step 2: 테스트를 돌려 통과를 확인한다**

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*SchedulerTriggerControllerTest*' --rerun-tasks --no-daemon
```
Expected: PASS (5 테스트)

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/scheduler/SchedulerTriggerController.kt \
        allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/scheduler/SchedulerTriggerControllerTest.kt
git commit -m "feat(af-103): 스케줄러 트리거 엔드포인트 (토큰 미설정은 503으로 닫힘)"
```

---

### Task 5: 변이 테스트 — 테스트가 실제로 뭘 잡는지 확인

AF-99·AF-100에서 계획이 여섯 번 틀렸고 전부 이 절차가 잡았다. 건너뛰지 않는다.

**Files:**
- 임시로 `SchedulerTriggerController.kt`를 변형했다가 되돌린다

- [ ] **Step 1: 변이 A — 빈 토큰 가드를 제거한다**

`authorize`의 `if (configuredToken.isBlank()) { ... }` 블록 전체를 주석 처리한다.

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*SchedulerTriggerControllerTest*' --rerun-tasks --no-daemon
```
Expected: **FAIL** — `설정 토큰이 비어 있으면...` 테스트가 깨져야 한다.

통과해버리면 그 테스트가 무의미하다는 뜻이다. 테스트를 고친 뒤 다시 이 단계를 돌린다.

- [ ] **Step 2: 변이 A를 되돌린다**

주석을 해제해 원상복구한다.

- [ ] **Step 3: 변이 B — force를 true로 바꾼다**

`return fxAdmin.collectHana(null, false)` 를 `return fxAdmin.collectHana(null, true)` 로 바꾼다.

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*SchedulerTriggerControllerTest*' --rerun-tasks --no-daemon
```
Expected: **FAIL** — `verify(admin).collectHana(null, false)` 가 깨져야 한다.

통과하면 "스케줄 실행은 절대 force를 안 쓴다"는 이 설계의 핵심 제약이 테스트로 지켜지지 않는 것이다.

- [ ] **Step 4: 변이 B를 되돌린다**

`false`로 되돌리고, 파일이 Step 1 이전과 동일한지 확인한다:
```bash
git diff --stat allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/scheduler/SchedulerTriggerController.kt
```
Expected: 출력 없음 (변경 없음)

- [ ] **Step 5: 커밋할 것이 없다**

변이는 되돌렸으므로 커밋하지 않는다. 결과만 다음 태스크로 보고한다.

---

### Task 6: Security 통합 테스트 — 경로가 실제로 열렸는지

Task 3의 standalone 테스트는 Security 필터를 안 태운다. permitAll이 실제로 붙었는지는 별도로 봐야 한다.

**Files:**
- Modify: `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/SecurityConfigAdminTest.kt`

새 파일을 만들지 않고 기존 `SecurityConfigAdminTest`에 추가한다. 그 파일이 이미
`SecurityConfig` + `JwtUserIdFilter` + `SseTokenFilter` + `FxRateAdminController`를 올린
슬라이스 컨텍스트를 갖고 있고, `SchedulerTriggerController`가 필요로 하는 `FxRateAdminController`도
거기 있다. 같은 하네스를 한 벌 더 세울 이유가 없다.

- [ ] **Step 1: `classes` 목록에 컨트롤러를 추가한다**

`@SpringBootTest(classes = [...])`의 `FxRateAdminController::class,` 줄 **바로 아래**에 추가:

```kotlin
        SchedulerTriggerController::class,
```

파일 상단 import에 추가:

```kotlin
import com.allfolio.api.scheduler.SchedulerTriggerController
```

- [ ] **Step 2: 테스트를 추가한다**

파일 맨 끝(마지막 `@Test` 아래, 클래스 닫는 `}` 바로 위)에 추가한다.
이 파일은 Kotlin MockMvc DSL(`mockMvc.get {}` / `mockMvc.put {}`)을 쓰므로 `post`도 그 스타일로 맞춘다.

```kotlin
    /**
     * AF-103: 스케줄러 트리거는 Security를 통과해 컨트롤러까지 도달해야 한다.
     *
     * 이 컨텍스트에는 scheduler.trigger-token 프로퍼티가 없어 기본값 빈 문자열이 주입되고,
     * 컨트롤러가 503으로 닫는다. **503이 나온다는 것 자체가 요청이 컨트롤러에 닿았다는 증거다** —
     * permitAll이 빠져 있으면 Security가 먼저 401로 끊어 503이 나올 수 없다.
     */
    @Test
    fun `스케줄러 트리거는 Security를 통과해 컨트롤러까지 도달한다`() {
        mockMvc.post("/api/internal/scheduler/fx/hana-collect")
            .andExpect { status { isServiceUnavailable() } }
    }
```

import에 추가:

```kotlin
import org.springframework.test.web.servlet.post
```

- [ ] **Step 3: 테스트를 돌린다**

Run:
```bash
cd allfolio-backend && ./gradlew :backend-app:test --tests '*SecurityConfigAdminTest*' --rerun-tasks --no-daemon
```
Expected: PASS (기존 8개 + 신규 1개)

진단:
- **401** → Task 2의 permitAll 줄이 안 들어갔거나 경로 오타
- **500** → `scheduler.trigger-token` 키가 없어 `@Value` 주입 실패. Task 1 확인
  (`@Value("\${scheduler.trigger-token:}")`의 `:` 기본값이 빠지면 이렇게 된다)
- **컨텍스트 로딩 실패** → `SchedulerTriggerController`가 요구하는 빈이 `classes`에 없다

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/SecurityConfigAdminTest.kt
git commit -m "test(af-103): 스케줄러 경로가 Security를 통과하는지 검증"
```

---

### Task 7: GitHub Actions 워크플로

**Files:**
- Create: `.github/workflows/collect-fx.yml`

- [ ] **Step 1: 워크플로를 작성한다**

```yaml
name: Collect FX

# Render 무료 플랜에는 크론 잡이 없다(유료 인스턴스 전용). 무료 웹 서비스는 15분 유휴 시
# 잠들어 인스턴스 안의 @Scheduled만으로는 주기 실행이 성립하지 않는다.
# 이 워크플로의 호출이 인스턴스를 깨우고, 끝나면 다시 잠든다 — 인스턴스 시간 소모가 최소다.
#
# 시각은 KST 09:10 / 12:10 / 15:10 / 18:10 (평일) = UTC 00:10 / 03:10 / 06:10 / 09:10.
# 네 시각 모두 KST 09~18시라 UTC 같은 날짜에 떨어져 요일 매핑이 어긋나지 않는다.
#
# :00이 아니라 :10인 이유 — GitHub 예약 워크플로는 러너 혼잡 시 5~30분 밀리고 매시 정각이
# 가장 혼잡하다. 지연돼도 무해한 이유는 하나은행 수집이 "조회일자"가 아니라 응답이 준
# "기준일·회차"로 저장하기 때문이다(AF-99). 몇 분 밀려도 그 시점 회차가 정확히 기록된다.
on:
  schedule:
    - cron: "10 0,3,6,9 * * 1-5"
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  # 지연으로 두 실행이 겹치면 uk_hana_fx_quote 경합으로 409가 난다. 앞선 실행을 기다린다.
  group: collect-fx
  cancel-in-progress: false

jobs:
  hana-fx:
    name: 하나은행 고시환율 수집
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - name: Trigger collection
        env:
          BACKEND_URL: ${{ secrets.BACKEND_URL }}
          SCHEDULER_TOKEN: ${{ secrets.SCHEDULER_TOKEN }}
        run: |
          set -euo pipefail

          if [ -z "${BACKEND_URL}" ] || [ -z "${SCHEDULER_TOKEN}" ]; then
            echo "::error::BACKEND_URL 또는 SCHEDULER_TOKEN 시크릿이 없습니다."
            exit 1
          fi

          # 무료 인스턴스가 잠들어 있으면 첫 요청이 30~90초 걸리거나 502가 난다.
          # 재시도가 안전한 이유는 수집이 멱등하기 때문이다 — 같은 기준일·회차는
          # uk_hana_fx_quote가 막고, 서비스가 변경분만 갱신한다.
          #
          # -o/-w로 본문과 상태를 분리해 받는다. --fail을 쓰면 4xx/5xx에서 본문이 버려져
          # 왜 실패했는지(422 안전장치인지 502 은행 문제인지) 알 수 없게 된다.
          HTTP_CODE=$(curl -sS -X POST \
            "${BACKEND_URL}/api/internal/scheduler/fx/hana-collect" \
            -H "X-Scheduler-Token: ${SCHEDULER_TOKEN}" \
            --max-time 150 \
            --retry 4 \
            --retry-delay 30 \
            --retry-all-errors \
            -o response.json \
            -w '%{http_code}')

          echo "HTTP ${HTTP_CODE}"

          {
            echo "### 하나은행 고시환율 수집"
            echo ""
            echo "HTTP \`${HTTP_CODE}\`"
            echo ""
            echo '```json'
            cat response.json 2>/dev/null || echo '(본문 없음)'
            echo ""
            echo '```'
          } >> "$GITHUB_STEP_SUMMARY"

          if [ "${HTTP_CODE}" != "200" ]; then
            # 422는 2% 급변동 가드다. 이 실패는 버그가 아니라 신호다 —
            # 진짜 크게 움직인 날은 사람이 값을 보고 force로 다시 돌릴지 판단해야 한다.
            echo "::error::수집 실패 (HTTP ${HTTP_CODE}). 422=안전장치 / 502=하나은행 응답 이상 / 409=동시 실행 / 503=SCHEDULER_TOKEN 미설정"
            exit 1
          fi
```

- [ ] **Step 2: YAML 문법을 검증한다**

Run:
```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/collect-fx.yml')); print('YAML OK')"
```
Expected: `YAML OK`

- [ ] **Step 3: cron이 의도한 KST 시각인지 직접 계산해 확인한다**

Run:
```bash
python3 - <<'PY'
from datetime import datetime, timedelta, timezone
KST = timezone(timedelta(hours=9))
for h in (0, 3, 6, 9):
    utc = datetime(2026, 8, 12, h, 10, tzinfo=timezone.utc)
    kst = utc.astimezone(KST)
    print(f"UTC {utc:%a %H:%M} -> KST {kst:%a %H:%M}")
PY
```
Expected:
```
UTC Wed 00:10 -> KST Wed 09:10
UTC Wed 03:10 -> KST Wed 12:10
UTC Wed 06:10 -> KST Wed 15:10
UTC Wed 09:10 -> KST Wed 18:10
```
요일이 모두 같아야 한다 — 다르면 `1-5` 필터가 KST 기준 평일과 어긋난다.

- [ ] **Step 4: 커밋**

```bash
git add .github/workflows/collect-fx.yml
git commit -m "feat(af-103): 하나은행 고시환율 수집 워크플로 (평일 KST 09/12/15/18시)"
```

---

### Task 8: 전 모듈 검증 + PR

**Files:** 없음 (검증과 PR만)

- [ ] **Step 1: 전 모듈 테스트를 돌린다**

`main` 브랜치 보호의 필수 체크가 `Backend tests`(전 모듈)라, backend-app만 돌려서는 부족하다.

Run:
```bash
cd allfolio-backend && ./gradlew test --rerun-tasks --no-daemon
```
Expected: BUILD SUCCESSFUL, 전 모듈 통과

`AiConsultantServiceTest > submitChat`이 Mockito `UnnecessaryStubbingException`으로 깨지면
이 변경과 무관한 기존 간헐 실패다. 한 번 더 돌려본다.

- [ ] **Step 2: 변경 파일 목록을 확인한다**

Run:
```bash
git diff --stat main...HEAD
```
Expected: 정확히 8개 파일

| 파일 | 종류 |
|---|---|
| `docs/superpowers/specs/2026-08-12-collection-scheduler-design.md` | 문서 |
| `docs/superpowers/plans/2026-08-12-collection-scheduler.md` | 문서 |
| `.github/workflows/collect-fx.yml` | 워크플로 |
| `allfolio-backend/backend-app/src/main/resources/application.yml` | 설정 |
| `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/config/SecurityConfig.kt` | 코드 |
| `allfolio-backend/backend-app/src/main/kotlin/com/allfolio/api/scheduler/SchedulerTriggerController.kt` | 코드 |
| `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/api/scheduler/SchedulerTriggerControllerTest.kt` | 테스트 |
| `allfolio-backend/backend-app/src/test/kotlin/com/allfolio/config/SecurityConfigAdminTest.kt` | 테스트 |

**프로덕션 코드 파일이 2개(SecurityConfig, SchedulerTriggerController)를 넘으면 범위가 샌 것이다.**
특히 `HanaFxCollectService`·`FxRateAdminController`·`FxRateScheduler`가 diff에 있으면 안 된다 —
이 작업은 기존 수집 로직을 건드리지 않는다.

- [ ] **Step 3: 푸시하고 PR을 연다**

```bash
git push -u origin feat/af-103-collection-scheduler
```

```bash
gh pr create --base main --title "feat(af-103): 수집 스케줄러 — GitHub Actions cron 트리거" --body "$(cat <<'EOF'
## 요약

하나은행 고시환율 수집을 평일 4회(KST 09:10/12:10/15:10/18:10) 자동 실행한다.

## 원안이 왜 바뀌었나

시장 데이터 설계 문서는 Render Cron을 쓰기로 하고 "무료 크론 정책은 착수 전 확인"이라는
단서를 달아뒀는데, 확인 결과 전제 셋이 전부 달랐다.

- **Render 크론 잡은 무료 플랜에서 못 쓴다** — 유료 인스턴스 전용, 최소 $1/월
- **`market-data`는 배포돼 있지 않다** — `render.yaml`의 서비스는 `allfolio-api` 하나뿐
- **환율 수집기는 `backend-app`에 있다** — AF-99에서 그렇게 결정됨

저장소가 PUBLIC이라 Actions 분 제약이 없어 GitHub cron으로 대체했다.

## 설계 요점

- **새 수집 코드 없음.** 기존 `HanaFxCollectService`를 그대로 트리거한다. 마이그레이션도 없다.
- **전용 토큰**(`X-Scheduler-Token`, 상수시간 비교). 어드민 JWT는 15분 만료라 CI가 못 들고 있고,
  어드민 비밀번호를 시크릿에 넣으면 유출 시 전권이 넘어간다.
- **토큰 미설정은 503으로 닫는다.** 빈 값이 "토큰 불필요"가 되면 환경변수를 빠뜨린 순간
  엔드포인트가 완전 공개된다. 설정 누락의 기본값은 닫힘이어야 한다.
- **`force`를 노출하지 않는다.** 스케줄 실행은 항상 `force=false`라 2% 급변동 가드가 살아 있다.
  가드가 걸리면 422 → 워크플로 실패인데, 그게 의도한 신호다.
- **콜드 스타트 흡수** — 무료 인스턴스가 잠들어 있으면 첫 요청이 30~90초 걸리거나 502다.
  `--retry 4 --retry-delay 30`으로 받고, 수집이 멱등해서 재시도가 안전하다.

## 머지 후 필요한 조치

1. `openssl rand -hex 32`로 토큰 생성
2. **Render 대시보드**에 `SCHEDULER_TOKEN` 추가 → **수동 재배포**
   (`sync-render-env.yml`의 `RENDER_ENV_KEYS`에 없고, env PUT은 재배포를 유발하지 않는다)
3. **GitHub 저장소 시크릿**에 같은 값의 `SCHEDULER_TOKEN` + 백엔드 주소 `BACKEND_URL` 추가
4. Actions 탭에서 `Collect FX`를 `Run workflow`로 1회 수동 실행해 200과 요약 확인

## 범위 밖

`FxRateScheduler`(인스턴스 내부 60초 주기)는 건드리지 않았다. `FX_SCHEDULER_ENABLED=true`로
켜져 있지만 Binance에 KRW 마켓이 없어(`USDTKRW`·`USDKRW` 모두 `Invalid symbol`) 동작할 수 없고,
교체는 별도 과제로 분리돼 있다.

Spec: `docs/superpowers/specs/2026-08-12-collection-scheduler-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: CI를 확인한다**

Run:
```bash
gh pr checks --watch
```
Expected: `Backend tests` PASS, `Build backend JAR` PASS

**실패한 필수 체크를 `--admin`으로 우회하지 않는다.** 간헐 실패면 재실행한다.

---

## 완료 후 사용자에게 보고할 것

- PR 링크
- 위 "머지 후 필요한 조치" 4단계 (토큰은 사용자가 생성·등록한다 — 대신 하지 않는다)
- Task 5 변이 테스트 결과 (변이 A·B가 실제로 테스트를 깨뜨렸는지)
