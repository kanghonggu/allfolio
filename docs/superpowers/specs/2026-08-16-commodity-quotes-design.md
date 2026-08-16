# 원자재 시세 탭 — 설계

- 작성일: 2026-08-16
- 화면: `/unified/market` 다섯 번째 탭 「원자재」
- 선행 참고: `2026-08-13-market-data-redistribution-review.md` (AF-108)

## 1. 실시간은 불가능하다 — 먼저 못박는다

이 설계에서 가장 중요한 사실이다. **무료 + 재배포 가능 + 실시간, 셋은 동시에 성립하지 않는다.** 원자재 실시간 시세는 거래소(CME·ICE·LME)의 상품이고 그들이 그것을 팔아서 운영된다.

측정한 사실:

- **FRED 원자재 카테고리 1,969개 중 일간은 정확히 12개**이고 전부 EIA 에너지다(원유 2·천연가스 1·정제유 9). 구리·니켈·아연·알루미늄·밀·옥수수·대두·커피·설탕·면화·철광석·우라늄은 **일간 시리즈가 존재하지 않는다** — 품목별로 `filter_variable=frequency&filter_value=Daily` 검색해 전건 0을 확인했다
- **KRX Open API도 전부 "일별매매정보"다.** 금시장·석유·배출권·지수 모두 장 마감 후 확정분이다. 권리자에게 직접 받아도 실시간은 없다
- **인베스팅닷컴이 되는 이유는 라이선스를 샀기 때문이다.** 그쪽 약관이 *"It is prohibited to use, store, reproduce, display, modify, transmit or distribute the data"*라고 명시한다 — `store`와 `display`가 우리가 하려는 그것이다. 그리고 그쪽도 *"not necessarily real-time nor accurate… prices are indicative"*라고 스스로 고지한다

**EIA만 예외인 이유**는 미국 정부 기관이 에너지 가격을 공공재로 발표하기 때문이다. 금속·농산물에는 그런 기관이 없다.

> **이 절을 지우지 말 것.** "왜 실시간이 아니냐"는 질문은 반드시 다시 나온다. 답이 "안 알아봤다"가 아니라 "알아봤고 구조적으로 불가능하다"임을 남긴다.

## 2. 소스 — 셋, 클라이언트는 이미 둘 다 있다

| 소스 | 무엇 | 이용 조건 | 기존 코드 |
|---|---|---|---|
| **FRED (EIA)** | 일간 에너지 | 미국 정부 자료 | `FredApiClient` (AF-FRED) |
| **FRED (IMF)** | 월간 금속·농산물 | IMF 발행분 | 같은 클라이언트 |
| **공공데이터포털 (금융위)** | 금 — KRX 금시장 | **이용허락범위 제한 없음**(포털 표기 확인) | `FscStockClient` — **같은 베이스 URL·같은 인증키** |

`FscStockClient`가 이미 `https://apis.data.go.kr/1160100/service`를 `FSC_API_KEY`로 호출한다. 일반상품시세정보는 같은 기관의 다른 오퍼레이션이라 **새 인증 방식·새 베이스 URL이 필요 없다.**

> **⚠️ "새 키가 필요 없다"고 단정했던 것은 틀렸다 (2026-08-16 정정).** 공공데이터포털은 **데이터셋별로 활용신청이 따로**다. 기존 `FSC_API_KEY`가 주식시세정보(15094808)에만 승인돼 있으면 일반상품시세정보(15094805)는 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`가 계속 난다. 같은 포털·같은 키 문자열이라고 같은 권한이 아니다.
>
> 오퍼레이션 경로는 실측으로 확정했다: **`/GetGeneralProductInfoService/getGoldPriceInfo`**. 근거는 오류 코드가 갈린다는 것이다 — 포털은 경로가 없으면 키를 보기 전에 `NO_OPENAPI_SERVICE_ERROR`(12)를 내고, 경로가 있으면 키 검증까지 간다. 대조군(존재하지 않는 오퍼레이션)이 12를 내는데 `getGoldPriceInfo`는 키 오류까지 갔다. `getGoldPrcInfo`·`getGoldMarketPriceInfo`는 둘 다 12 — 오답이다.
>
> **응답 필드·단위·상장 종목 구성은 아직 확인 못 했다.** 로컬 `.env`의 `FSC_API_KEY`가 빈 값이라 호출이 안 된다(실제 키는 GitHub Secrets/Render에만 있다). 확인 전까지 파서를 쓰지 않는다.

**다만 코드가 없는 것은 아니다** — 오퍼레이션(경로·파라미터·응답 필드)이 다르므로 호출 메서드는 새로 쓴다. 재사용되는 것은 인증과 베이스이지 파싱이 아니다.

**응답 필드를 추측하지 말 것.** AF-101이 등락률의 단위와 부호 규약을 맞힌 이유는 *"파서를 쓰기 전에 원본 응답 한 건을 눈으로 봤기 때문"*이다. 구현 첫 태스크는 금시세 오퍼레이션을 한 번 호출해 원본을 보는 것이다.

### 귀금속은 은·백금·팔라듐 전부 불가

**FRED의 IMF Primary Commodity Prices 릴리스 189건 전체를 받아 확인했다. 금·은·백금·팔라듐이 하나도 없다.** IMF 자체 데이터셋에는 포함되지만 FRED가 실어 오지 않는다 — LBMA/IBA 삭제(2022-01-31)와 같은 이유로 보는 것이 자연스럽다.

> **"IMF PCPS에 금·은이 있다"는 2차 자료를 근거로 가능성을 열어 뒀다가 틀렸다.** IMF가 발행하는 것과 FRED가 재배포하는 것은 다르다. `PGOLDUSDM`·`PSILVERUSDM` 같은 ID를 명명 규약으로 추측해 스펙에 박지 않은 이유가 이것이다.

금만 예외인 것은 **KRX 금시장이 국내 거래소 상품**이기 때문이다.

## 3. 범위 — 세 층 17종

시리즈 ID는 전부 **FRED API로 실재를 확인한 것**이다. 추측한 ID가 하나도 없다.

### 일간 (FRED/EIA) — 지연 영업일 3일

| 코드 | series_id | 단위 | 2026-08-11 실측 |
|---|---|---|---|
| `WTI` | `DCOILWTICO` | USD/bbl | 84.77 |
| `BRENT` | `DCOILBRENTEU` | USD/bbl | 93.26 |
| `NATGAS` | `DHHNGSP` | USD/MMBtu | 2.79 |

> **WTI가 84.77이다.** 설계 당시 "~70"으로 적었던 것보다 이미 높고, `PERCENT`(≤100)의 상한에
> 15달러밖에 안 남았다. 유가가 100달러를 넘는 것은 흔한 일이므로, 정책을 안 갈랐다면 WTI마저
> 예고 없이 사라졌을 것이다 — "WTI는 우연히 통과한다"는 진단이 실측으로 확인됐다.

정제유 9종(경유 3·휘발유 3·난방유·항공유·프로판)은 **뺀다.** 미국 걸프 경유 가격이 이 화면 사용자에게 줄 정보가 없다.

### 일별 (공공데이터포털) — D+1 13시 이후

| 코드 | 소스 | 단위 |
|---|---|---|
| `GOLD_KRX` | 금융위 일반상품시세정보 · 금시세 오퍼레이션 | **KRW/g** |

### 월간 (FRED/IMF) — **지연 2개월** (2026-08-16 기준 최신 관측 2026-06-01)

| 코드 | series_id | 단위 | 2026-06 실측 |
|---|---|---|---|
| `COPPER` | `PCOPPUSDM` | USD/MT | 13,552.04 |
| `NICKEL` | `PNICKUSDM` | USD/MT | 17,588.29 |
| `ZINC` | `PZINCUSDM` | USD/MT | 3,538.74 |
| `ALUMINUM` | `PALUMUSDM` | USD/MT | 3,438.85 |
| `IRON_ORE` | `PIORECRUSDM` | USD/MT | 103.79 |
| `COAL_AU` | `PCOALAUUSDM` | USD/MT | 150.36 |
| `URANIUM` | `PURANUSDM` | USD/lb | 69.11 |
| `WHEAT` | `PWHEAMTUSDM` | USD/MT | 199.65 |
| `CORN` | `PMAIZMTUSDM` | USD/MT | 195.78 |
| `SOYBEANS` | `PSOYBUSDM` | USD/MT | 414.54 |
| `SUGAR` | `PSUGAISAUSDM` | US cents/lb | 13.91 |
| `COFFEE` | `PCOFFOTMUSDM` | US cents/lb | 307.83 |
| `ALL_INDEX` | `PALLFNFINDEXM` | Index 2016=100 | 194.85 |

IMF 월간은 63품목이 있으나 50종(새우·양모·원목·바나나 등)은 **뺀다.** 화면이 시세판이 아니라 통계표가 된다. 목록은 확보돼 있으므로 필요해지면 설정 몇 줄로 늘린다.

## 4. 저장 — `market_commodity_quote`

확정본은 `docs/superpowers/migrations/2026-08-16-market-commodity-quote.sql`이다. 설계 초안에서 셋이 바뀌었고, 셋 다 **형제 표를 실제로 열어 보고** 고친 것이다.

```sql
CREATE TABLE IF NOT EXISTS market_commodity_quote (
    id            UUID          NOT NULL,
    code          VARCHAR(20)   NOT NULL,
    trade_date    DATE          NOT NULL,
    price         NUMERIC(18,4) NOT NULL,
    unit          VARCHAR(20)   NOT NULL,   -- USD/bbl · USD/MMBtu · USD/MT · USD/lb · USc/lb · KRW/g · index
    frequency     VARCHAR(1)    NOT NULL,   -- D | M
    prev_close    NUMERIC(18,4),
    change_value  NUMERIC(18,4),
    change_rate   NUMERIC(9,4),
    source        VARCHAR(20)   NOT NULL,   -- FRED | FSC. EIA/IMF 구분은 frequency가 진다
    collected_at  TIMESTAMP     NOT NULL,
    CONSTRAINT pk_market_commodity_quote PRIMARY KEY (id),
    CONSTRAINT uk_market_commodity_quote UNIQUE (code, trade_date)
);
```

**초안에서 바뀐 것 셋 (2026-08-16 정정)**

1. **PK가 `(code, trade_date)` 복합 자연키 → 대리키 `id`.** DB만 놓고 보면 자연키가 낫지만, 형제 시세 표 둘(`market_rate`·`market_index_quote`)이 **둘 다 대리키 + `uk_` 패턴**이고 그대로 옮겨 오기로 한 `RateCollectService`의 upsert가 그 리포지터리 모양에 붙어 있다. 유니크 제약이 같은 보장을 주므로 형제 쪽에 맞췄다.
2. **`source`가 `FRED_EIA | FRED_IMF | FSC` → `FRED | FSC`.** 구현할 `FredCommoditySource`의 `sourceName`은 하나이고 `FredRateSource`도 그렇다. 열 주석은 이 표를 쿼리하는 사람의 유일한 계약이라, 저장되지 않을 값을 적으면 `WHERE source = 'FRED_IMF'`가 조용히 0행을 낸다. EIA/IMF 구분은 `frequency`가 이미 진다.
3. **`collected_at`의 `DEFAULT NOW()` 제거.** 형제 표 중 가장 나중인 `market_rate`가 같은 열에서 DEFAULT를 **의도적으로 뺐고** 이유를 적어 뒀다 — 앱이 항상 채우므로 DEFAULT는 정상 경로에서 절대 발화하지 않고, 발화하는 유일한 경우는 앱이 빠뜨린 사고일 때인데, 그때 **에러 대신 값이 들어가 사고가 사고로 안 보인다.** 열에 무엇이 빠졌는지가 아니라 "이 행이 언제 수집됐는지 아무도 모른다"가 문제이고, DEFAULT는 그걸 그럴듯한 타임스탬프로 덮는다. (초안은 *"KST로 만든 값과 섞여 아홉 시간 어긋난다"*고 적었는데 **그건 틀렸다** — 앱도 이 열엔 `LocalDateTime.now(ZoneOffset.UTC)`를 쓴다. 저장소 관례가 `collected_at`은 UTC다. 결론은 그대로지만 근거의 절반이 사실이 아니었다.) (`market_index_quote`·`hana_fx_quote`엔 DEFAULT가 있지만 그 둘이 **먼저**다 — 오래된 쪽을 따라가면 학습이 되돌아간다.)

**`slot`을 만들지 않는다.** 세 소스 다 하루(또는 한 달) 한 값이라 `OPEN/MID/CLOSE` 개념이 없다. `market_index_quote`를 재사용하면 `slot`에 `CLOSE`를 억지로 채우게 되고, 그러면 "종가"라는 말이 원자재 행에서만 다른 뜻이 된다 — 나중에 지수와 원자재를 같이 조회할 때 조용히 틀린다.

**`unit`과 `frequency`를 행에 저장한다.** 코드에 상수로 들고 있으면 소스가 단위나 주기를 바꾼 날 저장은 멀쩡한데 화면만 틀린다. 관측과 함께 온 속성이므로 관측과 함께 남긴다.

**`prev_close`·`change_*`는 nullable이다.** 첫 관측이거나 직전 값이 없으면 채울 것이 없다. AF-104가 배운 대로 **`0`과 `null`은 다르다** — 무변동(0)을 "직전 값 없음"으로 표시하면 안 된다.

**월간 행의 `trade_date`는 그 달의 1일**이다(IMF 관측일 규약 그대로). 월말로 바꾸지 않는다 — 소스가 준 날짜를 우리가 해석해 옮기면 그 해석이 어디에도 안 적힌다.

**별도 인덱스를 만들지 않는다.** 다만 이유가 초안과 다르다 — PK 선두 열은 **랜덤 UUID라 조회에 아무 도움이 안 된다.** 코드별 최신 조회(`code = ? ORDER BY trade_date DESC LIMIT 1`)와 구간 조회를 둘 다 받는 것은 `uk_market_commodity_quote (code, trade_date)`가 만드는 btree다. Postgres는 btree를 역방향으로도 비용 없이 스캔하므로 별도 DESC 인덱스가 필요 없다.

## 5. 소스 포트 — `CommoditySource`

`RateSource`(AF-FRED)·`HistoricalRateSource`(AF-100)와 같은 모양이다. **가져오기만 소스별이고 저장하기는 공용이다.**

```kotlin
interface CommoditySource {
    val sourceName: String
    val codes: List<String>
    fun fetch(code: String, from: LocalDate, to: LocalDate): CommodityFetch
}
```

구현 둘:

- `FredCommoditySource` — 일간·월간 모두. 기존 `FredApiClient` 재사용. FRED가 결측을 `"."`로 주는 것과 인증키가 쿼리 파라미터에 실리는 것은 AF-FRED가 이미 방어한다

### `FredApiClient`를 일반화한다 — 지금 그대로는 17종 중 13종이 버려진다

`FredApiClient.fetch()`의 마지막 줄이 `parser.parse(body, RateValuePolicy.PERCENT)`다. `PERCENT`는 `|value| ≤ 100`을 요구하므로:

| 품목 | 값 | 통과 |
|---|---|---|
| 천연가스 ~3 · WTI ~70 | 100 이하 | ✅ **우연히** |
| 구리 13,552 · 금 ~150,000 · 종합지수 194.9 | 100 초과 | ❌ |

**WTI가 우연히 통과하는 것이 더 나쁘다** — 유가가 100달러를 넘는 날 조용히 사라진다.

그래서 `fetch(seriesId, from, to, valuePolicy)`로 **정책을 인자로 받게** 하고, `RateValuePolicy`에 원자재용을 더한다:

```kotlin
/** 시세 — 0 이하는 파싱 사고다. 상한은 걸지 않는다 (구리 13,552·금 150,000·지수 194.9가 다 정상) */
PRICE { override fun accepts(value: BigDecimal) = value > BigDecimal.ZERO }
```

**`POSITIVE`를 재사용하지 않는다.** 값은 같지만 뜻이 다르다 — `POSITIVE`의 KDoc은 "0원짜리 환율은 없다"는 환율 도메인의 진술이다. 같은 술어를 공유했다가 환율 쪽 판단이 바뀌면 원자재가 따라 움직인다.

**대가**: 반환 타입이 `RateFetch`인데 원자재를 담게 되어 이름이 거짓이 된다. 감수한다 — `RateValuePolicy` 자신이 *"패키지가 아직 `fx`인 것은… 세 번째 소스가 붙으면 중립 패키지로 옮길 것"*이라고 같은 미루기를 이미 기록해 뒀다. 지금 `RateFetch`→중립 이름 리팩터링을 하면 8개 파일과 AF-102·AF-FRED 테스트를 끌고 오고, 원자재 작업이 금리 리팩터링이 된다.

**버리지 않은 대안**: 별도 `FredCommodityClient`. HTTP 안전장치(URL 로깅 금지·cause 금지·본문 미리보기 금지)를 복제하게 되고, 그 방어는 한쪽만 고쳐지는 날이 온다. 그 파일 주석이 왜 그렇게 했는지를 길게 적어 둔 것이 복제하면 안 되는 이유다.

### 상한이 없으면 단위 오인을 못 잡는다 — 눈으로 보는 수밖에 없다

`PRICE`는 0 초과만 본다. 소스가 USD/MT를 USD/kg로 바꾸면 값이 1,000분의 1이 되는데 그건 여전히 양수다. `RateValuePolicy.PERCENT`의 KDoc이 *"반대 방향 단위 오인은 구조적으로 못 잡는다… 시계열 코드를 확정할 때 눈으로 한 번 확인해야 하는 몫"*이라고 적은 것과 같다.

**그래서 구현 첫 태스크가 17종을 한 번씩 호출해 값과 단위를 눈으로 대조하는 것이다.**
- `FscCommoditySource` — 금. 기존 `FscStockClient`와 같은 베이스·키

**수집 서비스의 방어는 한 벌뿐이다.** 소스가 늘어도 복제하지 않는다 — AF-102가 `RateCollectService`에 세운 규약 그대로다.

## 6. 화면 — 다섯 번째 탭

`/unified/market`에 「원자재」 탭을 더한다. **한 탭 안에서 두 섹션으로 시각적으로 가른다:**

- 위 **「시세」** — 일간 3종 + 금
- 아래 **「월간 지표」** — 13종. 섹션 머리에 *"국제기구 월평균이라 두 달가량 늦습니다"*

**섹션을 나누는 이유는 신선도가 층마다 다르기 때문이다.** "사흘 전"과 "두 달 전"이 같은 표에 놓이면 사용자가 숫자를 믿는 방식이 망가진다. 항목마다 기준일을 찍는 것은 AF-104 금리 탭이 이미 쓰는 방식이다(기준금리 08-12 vs 국고채 08-14).

**자릿수는 `lib/market-format.ts`의 `fixed()`를 쓴다.** 새 포맷터를 만들지 않는다. 단위는 값 옆에 붙인다.

> **⚠️ "라벨 맵에 단위 표기를 더한다"고 적었던 것은 틀렸다 (2026-08-16 정정).** 그건 바로 위 §4가 못박은 것과 정면으로 어긋난다 — *"코드에 상수로 들고 있으면 소스가 바꾼 날 저장은 멀쩡한데 화면만 조용히 틀린다"*. 그래서 `unit`을 행에 저장하기로 한 것인데, 화면이 라벨 맵의 상수를 쓰면 그 저장이 무의미해진다. **화면은 행에 실려 온 `unit`을 쓴다.** 구현이 이쪽을 택했고 그게 맞다.

**노출 플래그**: `market.commodities-enabled`. `render.yaml`에는 **`value:`가 아니라 `sync: false`** — blueprint에 값을 적으면 대시보드에서 끈 것이 다음 sync 때 조용히 되살아난다(AF-104가 그 함정을 주석으로 못박아 뒀다).

### 각주 두 줄 — 없애지 말 것

- *"은·백금은 국제 시세 재배포 라이선스 때문에 싣지 않습니다."*
- *"금은 KRX 금시장 원/g 기준이라 국제 금값(USD/oz)과 다릅니다."*

빼기만 하면 "왜 없지"·"왜 숫자가 다르지"가 남고, 다음 사람이 같은 조사를 다시 한다.

## 7. 테스트 — 무엇을 못박나

1. **단위가 행에 실린다** — 변이: `unit`을 코드 상수로 되돌리면 실패해야 한다
2. **`0`과 `null`이 구별된다** — 무변동(0)과 직전 값 없음(null)이 화면에서 같아 보이면 안 된다. AF-104가 이 사고를 겪었다
3. **월간 행의 `trade_date`가 소스가 준 날짜 그대로다** — 월말로 옮기지 않는다
4. **FRED 결측(`"."`)이 파싱 단계에서 걸러진다** — AF-FRED의 규약을 그대로 따르는지
5. **`PRICE` 정책이 상한을 안 건다** — 변이: `PERCENT`로 바꾸면 구리·금·지수 테스트가 실패해야 한다. **WTI만으로 검증하지 말 것** — 100 이하라 통과해 버린다
6. **기존 금리 경로가 `PERCENT`를 그대로 쓴다** — 변이: 금리 소스가 `PRICE`를 쓰게 바꾸면 AF-102의 단위 오인 방어가 사라지므로 그 테스트가 실패해야 한다
7. **노출 플래그가 실패-닫힘인가** — 미설정이면 탭이 안 뜨는지
8. **응답 타입 대조** — AF-104에서 FE 타입이 `string`인데 BE가 JSON 숫자를 보내 스케일이 날아갔다. 실제 페이로드로 확인한다

## 8. 범위 밖

- **실시간을 시도하지 않는다** (§1)
- **은·백금·팔라듐을 넣지 않는다** — 무료·재배포 가능 경로가 없다
- **IMF 월간 나머지 50종을 넣지 않는다** — 필요해지면 설정으로 늘린다
- **KRX Open API로 갈아타지 않는다.** 같은 KRX 데이터인데 신선도 이득이 없고, 약관을 못 읽었으며(사이트가 JS라 본문 접근 실패), 인증키에 관리자 승인이 필요하다. **약관 확인은 사용자만 할 수 있는 일**이라 AF-108 문서에 남긴다
- **지수 소스 교체(공공데이터포털)는 별건이다** — AF-108 문서에 기록했고 이 작업과 독립적이다

## 관련

- AF-108 시세 재배포 약관 검토 — 이 설계의 소스 선택 근거
- AF-104 시장 화면 — 탭 구조·자릿수·플래그 규약
- AF-FRED 미국 금리 — `FredApiClient`와 `RateSource` 포트의 원형
- AF-102 금리 수집 — `RateCollectService`의 방어 한 벌 규약
