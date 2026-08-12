# BTC·ETH KRW 시세 수집 — 하드코딩 상수 제거

작성일: 2026-08-12
선행: [USDT/KRW 시세 소스 교체](2026-08-12-usdt-krw-exchange-source-design.md) (PR #140, 머지·배포·검증 완료)

## 배경

USDT/KRW를 고치면서 같은 계열의 문제가 하나 더 드러났다. **BTC·ETH는 폴백 상수를 쓰고 있고,
그 상수를 갱신하는 주체가 없다.**

`RedisFxRateService.setCryptoToKrw`를 호출하는 곳은 어드민 엔드포인트
`PUT /api/admin/fx/crypto/{symbol}` **하나뿐이다.** 수집기도, 스케줄러도 없다.
따라서 Redis 키는 정상 상태에서 항상 비어 있고, `getCryptoToKrw`는 사실상 **언제나 상수를 반환한다.**

TTL은 이 문제의 원인이 아니었다. TTL을 늘리면 사람이 손으로 넣은 값이 더 오래 버틸 뿐이다.
**없는 것은 피드다.**

### 오차 (2026-08-12 실측)

| | 상수 | Upbit | Bithumb | 오차 |
|---|---|---|---|---|
| BTC | 90,000,000 | 89,825,000 | 89,880,000 | +0.2% |
| ETH | **4,500,000** | **2,663,000** | 2,664,000 | **+69%** |

**ETH가 69% 과대평가되고 있다.** 방금 고친 USDT의 4.1%보다 훨씬 크다.
BTC가 0.2%로 맞는 것은 상수를 잘 골라서가 아니라 우연이다 — 상수를 박은 시점의 시세가
지금과 비슷할 뿐이고, 코인은 언제든 그 관계가 깨진다.

거래소 간 값은 BTC 0.06%, ETH 0.04% 차이로 일치하므로 상호 검증도 된다.

### 영향 범위는 미확인

`CurrencyConverter.toKrw`의 `"BTC", "ETH"` 분기가 이 값을 쓴다. 자산의 `currency`가 BTC·ETH가
되는 경로는 거래소 어댑터의 quote 통화 파싱이다(`BinanceTradeMapper`·`BybitWsAdapter`·`AssetIdResolver` —
예: ETHBTC 페어의 quote는 BTC). **실제로 그런 자산이 몇 건인지는 확인하지 못했다** — DB가 Neon이라
Render CLI로 조회할 수 없다. 라이브 로그에도 크립토 폴백 경고가 없어 현재는 잠복일 가능성이 있다.

잠복이어도 고친다. 69% 틀린 값이 조건만 맞으면 평가에 그대로 들어간다.

## 설계

### 1. 포트를 "한 번에 여러 마켓"으로 일반화한다

```kotlin
interface FxQuoteSource {
    val sourceName: String
    /** 심볼 → KRW. 키는 "USDT" | "BTC" | "ETH". 실패 시 [FxQuoteException]. */
    fun fetchKrwRates(): Map<String, BigDecimal>
}
```

`FxApiClient`도 같이 바꾼다.

```kotlin
interface FxApiClient {
    fun fetchKrwRates(): Map<String, BigDecimal>
}
```

**HTTP 호출이 늘지 않는다.** 두 거래소 모두 한 번의 요청으로 세 마켓을 다 준다.

- Upbit: `GET /v1/ticker?markets=KRW-USDT,KRW-BTC,KRW-ETH` — 배열 3개
- Bithumb: `GET /public/ticker/ALL_KRW` — `data` 맵에서 세 키만 꺼낸다

`FxRateScheduler`는 결과를 받아 USDT는 `setUsdtToKrw`, BTC·ETH는 `setCryptoToKrw`로 쓴다.

**PR #140에서 건드리지 않기로 했던 `FxApiClient`·`FxRateScheduler`를 이번엔 바꾼다.**
그때의 제약은 변경 범위를 좁히려는 것이었고, 이번 요구사항은 포트 확장 그 자체다.

### 2. 심볼 단위로 해소한다 — 전부-아니면-전무가 아니다

이 설계에서 제일 중요한 부분이다. Upbit이 USDT·BTC는 정상인데 ETH만 빠졌거나 범위를 벗어났다면,
**ETH만 Bithumb에서 가져오고 나머지 둘은 Upbit 값을 쓴다.** 전부 버리지도, 나쁜 ETH를 받지도 않는다.

```
for source in sources:
    rates = source.fetchKrwRates()          # 실패하면 다음 소스로
    for (symbol, rate) in rates:
        if symbol not yet resolved and rate in range:
            resolved[symbol] = rate
    if all symbols resolved: break

if resolved is empty: throw          # 스케줄러가 잡아 기존 캐시를 지킨다
else: return resolved                # 일부만 채워졌으면 채운 것만 쓴다
```

부분 성공을 그대로 돌려주는 이유: ETH 하나 때문에 멀쩡한 USDT·BTC 갱신을 막으면
**한 심볼의 장애가 나머지 둘을 낡게 만든다.** 못 채운 심볼은 WARN으로 남기고
그 심볼의 Redis 값은 이전 것이 유지된다.

### 3. 범위 가드는 심볼별로 둔다

현행 500~5000은 BTC(8,900만)에 무의미하다. 가드의 목적은 "시세가 이상하다"가 아니라
**"파싱이 깨졌다"**를 잡는 것이므로 일부러 넓게 잡는다.

| 심볼 | 하한 | 상한 | 현재 |
|---|---|---|---|
| USDT | 500 | 5,000 | 1,409 |
| BTC | 1,000,000 | 1,000,000,000 | 89,825,000 |
| ETH | 100,000 | 100,000,000 | 2,663,000 |

범위 밖 값은 그 소스에서만 무시하고 다음 소스로 넘어간다(§2와 같은 규칙).

### 4. 상수를 없애고 마지막 값을 유지한다

- `fx.btc-krw.fallback-rate` · `fx.eth-krw.fallback-rate` **삭제**
- 크립토 Redis 키의 TTL은 **24시간** (USDT는 180초 그대로)

TTL을 길게 두는 이유는 USDT와 사정이 다르기 때문이다. USDT는 폴백 상수가 남아 있어
만료가 "상수로 떨어짐"을 뜻하지만, 크립토는 상수가 없으므로 만료가 곧 **데이터 없음**이다.
60초마다 덮어쓰므로 24시간 TTL은 사실상 "수집이 하루 종일 죽어 있었다"는 뜻이고,
그건 만료보다 훨씬 먼저 드러나야 할 사건이다.

**Postgres 테이블은 만들지 않는다.** 세 행을 위해 `infra/postgres/init.sql`을 고치면
Neon에 수동 마이그레이션이 필요한데, 수집기가 60초마다 돌고 Redis가 외부 서비스라
앱 재시작에도 값이 살아남는다. 얻는 것에 비해 운영 부담이 크다.

### 5. 진짜로 값이 없으면 예외를 던진다

상수를 없앴으므로 데이터가 정말 없을 때 정직한 숫자가 존재하지 않는다.
`getCryptoToKrw`는 심볼을 담은 메시지와 함께 예외를 던진다.

이 창은 실질적으로 거의 없다 — `@Scheduled(fixedDelay)`는 컨텍스트 기동 직후 한 번 즉시 돌므로
트래픽이 오기 전에 Redis가 채워진다. 기동 시점에 **두 거래소가 동시에** 죽어 있어야 도달한다.

대안이었던 "해당 자산을 건너뛴다"는 채택하지 않았다. NAV 총액이 조용히 줄어드는 쪽이
오류보다 나쁘다 — 사용자가 틀린 줄 모른다.

### 6. 어드민 수동 설정은 유지한다

`PUT /api/admin/fx/crypto/{symbol}`은 그대로 둔다. 다음 수집 주기(≤60초)에 피드 값이 덮어쓴다.
피드가 도는 한 사람이 넣은 값보다 피드가 정확하므로 이게 맞다. 지금도 사실상 이 동작이다.

`getCryptoToKrw`의 심볼 검증(BTC·ETH 외에는 `IllegalArgumentException`)은 유지한다.

## 변경 파급 (미리 확인함)

- `FxApiClient`의 **운영 소비자는 `FxRateScheduler` 하나**, 구현체는 `ExchangeFxApiClient` 하나다.
  포트를 바꿔도 번지지 않는다. (`FxRateAdminController.getUsdtKrw`는 이름만 같은 HTTP 핸들러로,
  `FxRateService`를 부르므로 무관하다.)
- **상수를 지워도 깨지는 테스트가 없다.** 테스트에 보이는 `90000000`·`4500000`은 전부
  `getCryptoToKrw`를 오버라이드한 **가짜 구현의 자체 값**이지 `RedisFxRateService`의 상수를
  검증하는 단언이 아니다. `RedisFxRateService`를 직접 생성하는 테스트도 없다(Spring만 만든다).
  따라서 생성자에서 `btcFallback`·`ethFallback` 두 파라미터를 빼도 컴파일이 깨지지 않는다.
- `FxRateService`를 구현하는 테스트 가짜가 10곳이지만, 이번 변경은 **인터페이스에 메서드를
  추가하지 않으므로** 그 가짜들을 건드릴 필요가 없다.

## 확인한 사실 (구현 시 함정)

- **Bithumb `ALL_KRW`의 `data`에는 `date` 키가 섞여 있다.** 481개 키 중 480개가 코인,
  하나가 문자열 타임스탬프다. 맵을 순회하면 그 키에서 깨진다. **세 심볼을 키로 직접 꺼내면
  이 문제가 구조적으로 사라진다** — 순회하지 않는다.
- **`ALL_KRW` 응답이 169KB다.** 현재 WebClient 코덱 한도가 256KB라 지금은 통과하지만
  여유가 1.5배뿐이다. 코인당 약 350바이트이므로 상장이 265개만 늘어도 한도를 넘는다.
  **Bithumb 소스의 `maxInMemorySize`를 1MB로 올린다.** (Upbit은 3개만 받으므로 256KB 유지.)
- Upbit `markets=` 는 쉼표로 여러 마켓을 받고 배열로 돌려준다. 순서를 보장한다는 문서는 없으므로
  **인덱스가 아니라 `market` 필드로 매칭한다.**

## 범위 밖

- USDT 경로 — PR #140에서 완료. TTL 180초와 폴백 1400을 그대로 둔다
- BTC·ETH 외 코인 — 지금 `CurrencyConverter`가 그 둘만 다룬다 (YAGNI)
- 과거 시세 — 이 경로는 현재가만 쓴다
- 알림 — 인프라가 없다. WARN/ERROR 로그로 드러낸다
