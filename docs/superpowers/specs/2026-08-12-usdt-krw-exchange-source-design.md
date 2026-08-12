# USDT/KRW 시세 소스 교체 — Binance → 국내 거래소

작성일: 2026-08-12
선행: [AF-99 하나은행 고시환율 수집기](2026-08-12-hana-fx-collector-design.md)
관련: PR #135(거래소 자산 `currency="USDT"`), PR #136(하나은행 수집기)

## 배경

`BinanceFxApiClient`가 USDT/KRW를 한 번도 가져온 적이 없다. 버그가 아니라 **구조적으로 불가능하다.**

2026-08-12 라이브 확인:

| 요청 | 응답 |
|---|---|
| `GET api.binance.com/api/v3/ticker/price?symbol=USDTKRW` | `{"code":-1121,"msg":"Invalid symbol."}` |
| `GET api.binance.com/api/v3/ticker/price?symbol=USDKRW` | `{"code":-1121,"msg":"Invalid symbol."}` |
| `GET api.binance.com/api/v3/ticker/price?symbol=USDTUSD` | `{"symbol":"USDTUSD","price":"0.99893000"}` |

클라이언트의 두 경로가 모두 `USDKRW`를 필요로 한다 — 1차는 `USDTKRW` 직접, 2차는 `USDTUSD × USDKRW`.
**Binance는 한국 철수 후 KRW 마켓이 없다.** 두 경로 다 실패 → 예외 → `FxRateScheduler`가 잡아
로그만 남김 → Redis 비어 있음 → `RedisFxRateService`가 폴백 상수 `1350`을 반환.

### 왜 지금 문제인가

AF-99에서 자산 평가의 USD를 하나은행 공식 매매기준율로 전환하고 USDT는 거래소 시세로 남겼다.
근거는 "거래소에 USDT를 들고 있는 사용자에게 거래소 시세가 실현 가능한 값"이었는데,
**그 시세가 실제로는 하드코딩 상수 1350이다.**

PR #135로 거래소 자산(Binance·OKX·Bybit)이 `currency="USDT"`가 되면서 이 상수의 영향 범위가
명확해졌다. 실측 USDT/KRW는 **1408**이므로 거래소 자산이 **약 4.1% 저평가**되고 있다.

### 근거가 오히려 강해진다

설계 문서는 USDT를 분리하는 이유를 "Binance에 실제 USDT를 들고 있는 사용자에게는 실현 가능한 값"
이라고 적었다. 그런데 **Binance에는 KRW 마켓이 없으므로 그 사용자는 어떤 Binance 시세로도 KRW를
실현할 수 없다.** 실제 실현 경로는 국내 거래소로 전송해 `KRW-USDT`에 파는 것이다.

즉 Upbit은 Binance의 아쉬운 대체재가 아니라, **AF-99의 논거가 처음부터 가리키던 값**이다.
이 교체는 분리 근거를 약화시키지 않고 강화한다.

## 함께 발견한 것

### testnet 하드코딩 — 첫 번째 버그에 가려져 있던 두 번째 버그

`BinanceFxApiClient`는 `binanceProperties.baseUrl`을 주입받는데, 그 기본값이 **testnet**이다.
기본값이 두 겹으로 박혀 있다.

- `application.yml:196` — `base-url: https://testnet.binance.vision` (`${...}` 플레이스홀더 없음)
- `BinanceProperties.kt:21` — `@DefaultValue("https://testnet.binance.vision")`

Binance에 KRW 마켓이 있었더라도 **운영이 testnet 가격으로 자산을 평가했을 것이다.**
지금은 testnet도 같은 `Invalid symbol`을 돌려주므로 가려져 있을 뿐이다.

한편 `BinanceSyncAdapter`는 `https://api.binance.com`을 **3곳**(39·96·121행)에 따로 하드코딩한다.
`unified-asset`은 `backend-app`에 의존하지 않으므로 이 3곳이 `BinanceProperties`를 그냥 읽을 수 없다 —
통일하려면 공유 모듈에 프로퍼티를 새로 두어야 한다. **이번 범위에서 제외한다.**

### TTL 경합 — 클라이언트만 고치면 살아남는 버그

```
RedisFxRateService:  TTL = Duration.ofSeconds(60)
FxRateScheduler:     @Scheduled(fixedDelayString = "${fx.scheduler.delay-ms:60000}")
```

`fixedDelay`는 **직전 실행이 끝난 시점**부터 잰다. 따라서 다음 쓰기는 직전 쓰기로부터
`60초 + fetch 소요시간` 뒤에 일어나는데, 키는 정확히 60초에 만료된다.
**매 주기마다 키가 갱신되기 직전에 반드시 만료된다.**

fetch가 200ms면 1분마다 약 200ms 동안 `getUsdtToKrw()`가 수집기가 멀쩡한데도 1350을 돌려준다.
지금은 키가 애초에 안 써지므로 보이지 않는다. 클라이언트만 고치면
**영구적 4% 오차가 간헐적 4% 오차로 바뀔 뿐이다.**

TTL은 폴링 주기와 같으면 안 되고 배수여야 한다.

### 테스트가 없다

`BinanceFxApiClientTest.kt`가 존재하지 않는다. 동작할 수 없는 클라이언트가 배포된 공정상의 이유다.

## 대체 소스 실측

둘 다 무료·무인증으로 동작한다.

| 소스 | 엔드포인트 | 값 | 레이트리밋 |
|---|---|---|---|
| Upbit | `GET api.upbit.com/v1/ticker?markets=KRW-USDT` | 1408.00 | `remaining-req: group=ticker; min=600; sec=8` |
| Bithumb | `GET api.bithumb.com/public/ticker/USDT_KRW` | 1409 | 응답에 미표기 |

60초 폴링 대비 Upbit의 예산은 약 600배 여유다. 두 값이 1408 vs 1409로 일치하므로
상호 검증도 된다.

참고로 하나은행 공식 매매기준율은 1414.6이다. USDT가 공식 USD보다 **낮게** 거래되고 있다
(김치 프리미엄이 음수). 이 격차는 정상이며, 두 값이 벌어지는 것이 AF-99가 의도한 동작이다.

## 설계

### 1. 포트는 유지하고 소스를 그 아래에 둔다

`FxApiClient`를 구현하는 빈이 둘이면 `FxRateScheduler`의 주입이 깨진다. 체인을 한 단계 아래에 만든다.

```kotlin
/** 개별 거래소 시세 소스. FxApiClient가 아니라 그 구현체의 부품이다. */
interface FxQuoteSource {
    val sourceName: String
    fun fetchUsdtKrw(): BigDecimal
}

/** 유일한 FxApiClient 빈. 소스를 순서대로 시도한다. */
class ExchangeFxApiClient(
    private val sources: List<FxQuoteSource>,   // 순서: Upbit, Bithumb
) : FxApiClient
```

`getUsdtKrw()`는 소스를 순서대로 시도하고, 어느 소스가 답했는지 로그에 남기고,
**전부 실패했을 때만 예외를 던진다** — `FxRateScheduler`의 기존 catch 계약이 그대로 유지된다.
`FxRateScheduler`와 `FxApiClient` 인터페이스는 손대지 않는다.

소스 순서는 생성자 주입 리스트의 순서로 정한다. Spring의 `@Order`로 결정하면 순서가 클래스에
흩어져 읽기 어려우므로, 설정 클래스에서 리스트를 명시적으로 조립한다.

### 2. 소스 구현

| 클래스 | 파싱 대상 |
|---|---|
| `UpbitFxSource` | 배열 첫 원소의 `trade_price` |
| `BithumbFxSource` | `status == "0000"` 확인 후 `data.closing_price` |

Bithumb의 `status` 확인이 필요한 이유: 이 API는 실패해도 HTTP 200에 `status`만 바꿔 돌려준다.
확인하지 않으면 파싱 실패가 0으로 흘러든다.

HTTP는 기존 패턴을 따른다 — `by lazy` WebClient, `block(TIMEOUT)`, companion 상수
(`HanaFxClient`·`EcosStatisticSearchClient`와 동일). base-url은 각각 프로퍼티로 빼서
환경변수로 덮을 수 있게 한다.

### 3. 유효 범위 가드

소스가 `0`이나 파싱 쓰레기를 돌려주는 것은 실패보다 나쁘다. 조용히 모든 평가를 오염시키기 때문이다.
각 소스의 결과가 타당한 범위(`500 < rate < 5000`)를 벗어나면 **그 소스는 실패로 취급하고 다음으로 넘어간다.**

`HanaFxGuards`가 이미 쓰는 방식이다. 범위를 넓게 잡는 이유는 이 가드가 "환율이 이상하다"를 잡으려는
것이 아니라 "파싱이 깨졌다"를 잡으려는 것이기 때문이다. 좁게 잡으면 실제 급변동 때 환율이 얼어붙는다.

### 4. 파싱을 HTTP에서 분리한다

응답 → `BigDecimal` 추출은 순수 함수로 두고 **기록된 응답 픽스처로 단위 테스트한다.**
HTTP는 얇은 껍데기만 남긴다.

`HanaFxParser`를 분리한 것과 같은 이유이고, 무엇보다 **동작할 수 없는 클라이언트가 배포된 원인**이
이 자리에 테스트가 없었던 것이다. 픽스처는 이 문서 작성 시점에 실제로 받은 응답을 쓴다.

`ExchangeFxApiClient`의 체인 동작(1차 실패 → 2차 성공, 전부 실패 → 예외, 범위 밖 → 다음 소스)은
가짜 `FxQuoteSource`로 네트워크 없이 검증한다.

### 5. 삭제와 설정

- **`BinanceFxApiClient` 삭제.** 동작할 수 없는 코드를 남기면 되살아난다.
- `FX_SCHEDULER_ENABLED` 기본값 `false` → `true`. Upbit은 인증키가 필요 없으므로
  이 플래그가 지키던 이유(자격증명 없이 켜면 실패)가 사라진다. 기본 `false`로 두면
  수정을 배포해도 Render에서 손으로 켜기 전까진 여전히 1350이다 — 즉 조용히 안 고쳐진다.
- `FX_SCHEDULER_ENABLED`·`FX_USDT_KRW_FALLBACK`을 `render.yaml` `envVars`에 명시한다.
  현재 `render.yaml`에는 `FX_*`가 하나도 없어 대시보드 전용 설정이 코드에 문서화되어 있지 않다.
- `binance.base-url` → `${BINANCE_API_BASE_URL:https://api.binance.com}`
  (`application.yml:196`과 `BinanceProperties`의 `@DefaultValue` 양쪽).
  FX 클라이언트가 Binance를 떠나므로 결합은 자연히 끊기고, 남은 사용처
  (`BinanceApiClient`의 `myTrades`)는 운영 기본값이 맞다.

  **환경변수 이름을 `BINANCE_BASE_URL`로 쓰면 안 된다.** `market-data`의
  `application.yml:46`이 그 이름을 이미 WebSocket 스트림 주소
  (`wss://stream.binance.com:9443`)에 쓰고 있다. 재사용하면 한 변수가 `https://`와 `wss://`를
  동시에 뜻하게 되어 두 서비스를 같은 환경에 올리는 순간 한쪽이 깨진다.

  같은 맥락에서 `binance` 프리픽스가 `BinanceProperties`(backend-app)와
  `BinanceWsProperties`(market-data) 양쪽에 걸쳐 있고, `BinanceWsAdapter:72`가
  `baseUrl.contains("testnet")`으로 WS 엔드포인트를 고른다. 다만 **backend-app은
  `market-data`에 의존하지 않으므로**(`build.gradle.kts` 35~46행) 이번 변경이 그쪽에 닿지 않는다.
- Redis TTL 60초 → **180초.** 폴링 2회를 놓쳐도 상수로 떨어지지 않는다.
- `fx.usdt-krw.fallback-rate` 기본값 1350 → **1400.**

### 6. 단기 완화책

**대체 구현과 무관하게 지금 바로 할 수 있다.** Render 대시보드에서
`FX_USDT_KRW_FALLBACK=1400`으로 설정하면 재시작 한 번으로 오차가 4.1% → 0.6%로 줄어든다.
배포가 필요 없다.

**이것은 해결이 아니다.** 상수는 다시 어긋나고, 실제 실패가 얼마나 시끄러운지를 가릴 뿐이다.
PR이 리뷰를 도는 동안 비용 없이 손해를 줄이는 목적으로만 쓴다.

## 범위 밖

- `BinanceSyncAdapter`의 `api.binance.com` 하드코딩 3곳 — 모듈 경계를 건드려야 하므로 별건
- 하나은행 USD 경로 — AF-99에서 이미 완료
- 환율 이력 저장 — 이 경로는 Redis 캐시만 쓴다
- 알림 — 인프라가 없다. WARN/ERROR 로그로 드러낸다 (AF-99·AF-100과 동일)

## 확인 필요 (사용자 조치)

Render CLI 토큰이 만료되었고 `/actuator/env`는 401, `/api/admin/fx`는 403이라
**아래 두 가지를 코드 쪽에서 확인할 수 없었다.**

1. `FX_SCHEDULER_ENABLED`가 현재 Render에서 켜져 있는지
2. 로그에 `[FxScheduler] FX update failed`가 60초마다 쌓이고 있는지

설계에는 영향이 없다 — 꺼져 있었다면 조용히 1350이었고, 켜져 있었다면 시끄럽게 1350이었으며,
어느 쪽이든 고칠 코드는 같다. 다만 현재 손해 규모와 로그 노이즈 여부를 판단하려면 필요하다.
