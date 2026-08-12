# AF-103 수집 스케줄러 설계

- 작성일: 2026-08-12
- 관련: AF-99(하나은행 고시환율 수집기), AF-100(ECOS 과거 환율 백필), 후속 AF-101·102·104

## 배경 — 원안이 성립하지 않는다

시장 데이터 설계 문서는 **Render Cron**으로 수집을 돌리기로 하고, 착수 전 무료 크론 정책을 확인하라는
단서를 달아뒀다. 확인한 결과 전제 세 가지가 전부 현실과 다르다.

1. **Render 크론 잡은 무료 플랜에서 못 쓴다.** 크론 잡·백그라운드 워커는 유료 인스턴스만 선택할 수 있고
   최소 $1/월부터다. 2026-04-23 가격 개편에서 Hobby 플랜 내용이 크게 바뀌었다.
2. **`market-data`는 배포돼 있지 않다.** `render.yaml`의 서비스는 `allfolio-api` 하나뿐이다.
   "두 서비스 합계 ~440h" 시간 예산 계산은 두 개가 돈다는 전제였다.
3. **환율 수집기는 `backend-app`에 있다.** 설계 문서는 "market-data가 수집, backend-app은 DB만 읽기"였지만
   AF-99에서 일회성 백필이 본체라는 이유로 `backend-app`에 두기로 결정했고 그렇게 구현됐다.

따라서 AF-103은 "Render Cron을 붙인다"가 아니라 **"무료 플랜에서 주기 실행을 어떻게 얻는가"** 를 다시
정하는 일이다. GitHub Actions 예약 워크플로를 쓰기로 했다 — 저장소가 PUBLIC이라 Actions 분(minute)
제약이 없고, 이미 CI로 쓰고 있어 새 인프라가 늘지 않는다.

## 결정과 근거

### 트리거: GitHub Actions cron → 백엔드 엔드포인트

인스턴스 안에서 `@Scheduled`만 두는 방식은 성립하지 않는다. 무료 웹 서비스는 15분 유휴 시 잠들고,
잠든 인스턴스에서는 `@Scheduled`가 돌지 않는다. 깨워줄 외부 신호가 어차피 필요하다.

그렇다면 신호가 곧 트리거인 편이 낫다. GitHub Actions가 수집 엔드포인트를 직접 호출하면
호출 자체가 인스턴스를 깨우고, 수집이 끝나면 다시 잠든다 — Render 인스턴스 시간(750h 워크스페이스 공유)
소모가 최소가 된다. "깨우기 핑 + 인스턴스 내부 `@Scheduled`" 하이브리드는 장중 내내 깨어 있어야 해
같은 일을 하면서 시간만 더 쓴다.

### 주기: 평일 4회 (이벤트 지점)

KST 09:10 / 12:10 / 15:10 / 18:10, 평일.

`10 0,3,6,9 * * 1-5` (UTC). 네 시각 모두 KST 09~18시라 UTC 같은 날짜에 떨어져 요일 매핑이 어긋나지 않는다.

**정각을 피한 이유**: GitHub 예약 워크플로는 러너 혼잡 시 5~30분 밀리고 가끔 스킵되며, 매시 정각이 가장
혼잡하다. `:10`으로 밀어 지연 확률을 낮춘다.

**10분 주기를 안 쓴 이유**: GitHub cron의 지연 폭이 10분보다 크다. 명목 주기가 촘촘할수록 실제 간격만
불규칙해질 뿐 정보량은 늘지 않는다. 게다가 10분 주기는 장중 내내 인스턴스를 깨워 둔다.
USD/KRW는 하루 1% 안팎 움직이므로 포트폴리오 평가에는 4회로 충분하다.
AF-104 시장 화면이 일중 차트를 실제로 요구하면 그때 워크플로 cron 한 줄만 고치면 된다.

**지연을 감수할 수 있는 이유**: 하나은행은 조회일자가 아니라 **응답이 준 기준일·회차**로 저장한다(AF-99).
호출이 몇 분 밀려도 그 시점의 회차가 정확히 기록되며, 같은 회차가 두 번 들어가지 않는다.

### 인증: 전용 스케줄러 토큰

어드민 JWT는 15분 만료라 CI가 들고 있을 수 없다. GitHub Actions가 `/api/auth/login`으로 매번 로그인해
JWT를 받는 방법도 있지만, 그러면 **어드민 비밀번호**가 CI 시크릿에 들어간다 — 유출 시 전권이 넘어간다.

수집 트리거만 가능한 별도 토큰이 폭발 반경이 가장 작다. 이 토큰이 유출돼도 할 수 있는 일은
멱등한 수집을 여러 번 돌리는 것뿐이다.

- 새 컨트롤러 `SchedulerTriggerController`, 경로 `/api/internal/scheduler/**`
- SecurityConfig에서 `permitAll` (Spring Security는 통과시키고, 토큰 검사는 컨트롤러가 한다)
- 헤더 `X-Scheduler-Token`을 설정값 `scheduler.trigger-token`(env `SCHEDULER_TOKEN`)과 비교
- 비교는 `MessageDigest.isEqual`로 **상수 시간**. 길이는 새지만 내용은 안 샌다

**토큰 설정이 비어 있으면 503으로 닫는다.** 이게 이 설계에서 가장 중요한 한 줄이다.
빈 설정이 "토큰 불필요"로 해석되면 환경변수를 깜빡 빠뜨린 순간 엔드포인트가 완전 공개된다.
설정 누락은 "열림"이 아니라 "닫힘"이어야 한다.

CSRF는 이미 전역 비활성이라 POST가 막히지 않는다(`SecurityConfig.csrf(disable)`).

### `force`는 쓰지 않는다

스케줄 실행은 항상 `force = false`다. AF-99의 2% 급변동 가드가 살아 있어야 한다.

가드가 걸리면 422가 오고 **워크플로 잡이 실패한다 — 이게 의도한 동작이다.** 진짜 2% 넘게 움직인 날은
사람이 값을 보고 판단해야 하고, Actions 탭의 빨간 X가 그 신호다. 스케줄러가 조용히 `force`로 뚫으면
가드를 만든 이유가 사라진다(파싱 오류로 튄 값이 그대로 저장된다).

### 콜드 스타트

무료 인스턴스가 잠들어 있으면 첫 요청이 30~90초 걸리거나 502가 난다. 워크플로에서 흡수한다:

```
curl --max-time 150 --retry 4 --retry-delay 30 --retry-all-errors
```

재시도가 안전한 이유는 수집이 멱등하기 때문이다 — 같은 기준일·회차는 `uk_hana_fx_quote`가 막고,
서비스가 변경분만 갱신한다.

## 구조

```
.github/workflows/collect-fx.yml   (cron: 10 0,3,6,9 * * 1-5 + workflow_dispatch)
        │  POST + X-Scheduler-Token
        ▼
SchedulerTriggerController          /api/internal/scheduler/fx/hana-collect
        │  토큰 검증 → 위임
        ▼
FxRateAdminController.collectHana(date = null, force = false)
        │
        ▼
HanaFxCollectService.collect(오늘(KST), force = false)
```

### 어드민 컨트롤러에 위임하는 이유

`collectHana`는 예외를 상태 코드로 옮기는 로직을 30줄쯤 들고 있다 — 422(안전장치)·502(은행 응답 이상)·
409(동시 실행 경합). 이 구분은 **Actions 로그를 읽는 사람에게 그대로 필요하다**: 은행을 확인하러 갈지,
값을 보고 `force`를 쓸지, 그냥 재실행할지가 갈린다.

그래서 복제하지 않고 위임한다. 복제하면 두 벌이 갈라지고, 한 벌로 뽑아내자니 그 안의 주석들이
"이 엔드포인트에서만 이렇게 하는 이유"를 설명하고 있어 공용 헬퍼로 옮기면 근거가 뜬다.
컨트롤러가 컨트롤러를 주입받는 게 낯설다는 건 인정하지만, 대안 둘 다 이보다 나쁘다.
**이 위임을 "정리"하지 말 것.**

기본 날짜(KST 오늘)도 `collectHana` 안에 있어 그대로 재사용된다. Render 컨테이너는 UTC라
`LocalDate.now()`를 쓰면 KST 오전 9시 이전 호출이 어제를 조회한다 — 09:10 KST 실행이 정확히 그 구간이다.

### 확장

AF-101(지수)·AF-102(금리)는 같은 워크플로에 잡을 하나씩, 같은 네임스페이스에 엔드포인트를 하나씩
추가한다. 범용 디스패처(`POST /scheduler/run?job=xxx`)는 만들지 않는다 — 수집기가 셋뿐이고
각자 주기가 달라, 추상화가 얻는 것보다 잃는 게 많다(어떤 잡이 언제 도는지 YAML만 보고 알 수 없게 된다).

## 관측

응답 JSON(`baseDate`·`roundNo`·`currencies`·`inserted`·`updated`·`unchanged`·`skipped`)을
`$GITHUB_STEP_SUMMARY`에 찍는다. 로그를 열지 않고 Actions 목록에서 결과가 보여야
"매일 돌고는 있는데 값이 들어오는지는 모르는" 상태를 피할 수 있다.

## 테스트

`SchedulerTriggerController` 단위 테스트(`@WebMvcTest` 또는 MockMvc standalone):

| 케이스 | 기대 |
|---|---|
| 토큰 일치 | 200 + 요약 JSON |
| 토큰 불일치 | 401 |
| 헤더 누락 | 401 |
| **설정 토큰이 빈 문자열** | **503** (요청 토큰이 무엇이든) |
| 수집 서비스가 `IllegalStateException` | 422 (위임 경로가 살아있는지) |

마지막 줄이 중요하다 — 위임이 실제로 상태 매핑을 물려받는지 확인하지 않으면
`SchedulerTriggerController`가 조용히 500을 뱉어도 테스트가 통과한다.

SecurityConfig 변경은 통합 테스트로 확인한다: 인증 없이 `/api/internal/scheduler/**`에 POST했을 때
401(Security가 막음)이 아니라 컨트롤러까지 도달해야 한다.

## 배포 순서

새 테이블·마이그레이션 없음. 순서 제약도 없다(엔드포인트가 늘 뿐, 기존 경로는 그대로).

1. 토큰 생성 — `openssl rand -hex 32`
2. Render 대시보드에 `SCHEDULER_TOKEN` 추가 → **수동 재배포**
   (`sync-render-env.yml`의 `RENDER_ENV_KEYS`에 없고, env PUT은 재배포를 유발하지 않는다)
3. GitHub 저장소 시크릿에 같은 값으로 `SCHEDULER_TOKEN` 추가, `BACKEND_URL`도 추가
4. 머지
5. `workflow_dispatch`로 수동 1회 실행해 200과 요약을 확인

## 범위 밖 (의도적)

- **`FxRateScheduler`(인스턴스 내부 60초 주기)** — `FX_SCHEDULER_ENABLED=true`로 켜져 있지만
  그 Binance FX 클라이언트는 동작할 수 없다. Binance에 KRW 마켓이 없어 `USDTKRW`·`USDKRW` 둘 다
  `Invalid symbol`을 돌려준다(실측 확인). 지금 60초마다 ERROR가 쌓이고 USDT 환율은 폴백 1350이다.
  교체는 별도 과제로 분리돼 있다. **AF-103에서는 건드리지 않는다.**
- AF-100 백필의 스케줄 — 백필은 일회성 소급 작업이라 주기 실행 대상이 아니다.
- 실패 알림 채널(슬랙 등) — Actions 실패 메일로 충분하다. 필요해지면 그때.
- 지수·금리 수집(AF-101·102) — 수집기 자체가 아직 없다.
