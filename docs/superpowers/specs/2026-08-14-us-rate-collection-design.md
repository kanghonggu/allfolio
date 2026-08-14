# 미국 금리 수집 설계 — FRED

- 작성일: 2026-08-14
- 선행: AF-102(한국 금리 수집) 가동 중, AF-104(시장 조회 API) 머지됨
- 노션: [시장] 금리 수집 — 미국(FRED) (P1 가시화, 규모 S, 영역 BE)
- 의존: FRED API 키 발급 (사용자 작업, 무료)

## 목적

시장 화면 금리 탭의 미국 단을 채운다. 연방기금금리와 미국채 2·10·30년, 그리고 조회 시 계산하는
한·미 기준금리차와 10년−2년 스프레드.

## AF-102가 미뤄 둔 판단을 이제 시험한다

AF-102는 소스 추상화(`RateSource` 포트)를 **일부러 만들지 않았다**. 그때 적은 이유는
"구현이 하나뿐인 추상화는 두 번째 구현에서 대체로 안 맞고, 그때 고치는 비용이 지금 만드는
비용보다 싸다"였다. 두 번째 구현이 왔으므로 이제 만든다.

**모델은 이미 저장소 안에 있다.** AF-100이 과거 환율에 `HistoricalRateSource`를 두고 ECOS와
Upbit 두 구현을 붙였고, 그 KDoc이 원칙을 이렇게 적어 두었다:

> 가져오기만 소스별이고 저장하기는 공용이다. 0건 중단·범위 밖 제거·중복 접기·계수는
> 서비스가 한 벌만 갖는다 — ECOS를 겪으며 생긴 방어지만 소스와 무관하게 옳다.

`RateCollectService`가 정확히 그 모양이다. ECOS에 묶인 것은 `client.fetch(EcosQuery(...))`
한 줄뿐이고, 그 뒤(구간 밖 필터·`emptySeries`·중복 접기·upsert·`inserted/updated/unchanged`
계수·종목별 실패 격리)는 전부 소스 무관이다. 그 방어들은 AF-102가 리뷰를 네 바퀴 돌며 다진
것이라 **소스마다 복제하면 안 된다** — 한쪽만 고치는 날이 온다.

## 소스 포트

```kotlin
/** 하루치 금리 한 건. 값은 연 %다 */
data class RateObservation(val quoteDate: LocalDate, val value: BigDecimal)

/** @param skipped 소스가 파싱 단계에서 버린 행 수 — 요약의 skippedRows로 그대로 나간다 */
data class RateFetch(val rows: List<RateObservation>, val skipped: Int)

interface RateSource {
    /** `market_rate.source`에 들어갈 값 */
    val sourceName: String

    /** 이 소스가 담당하는 canonical 코드. 설정에서 온다 */
    val codes: List<String>

    /** `from..to`는 포함 범위. 범위 밖 날짜가 섞여 와도 된다 — 서비스가 걸러낸다 */
    fun fetch(code: String, from: LocalDate, to: LocalDate): RateFetch
}
```

**환율 포트와 한 군데 다르다.** 환율은 호출자가 통화를 지목하므로 `supports(currency)`로 묻지만,
금리는 수집 대상이 설정에서 열거되므로 소스가 자기 코드 목록을 내놓는다. 서비스는
`sources.flatMap { s -> s.codes.map { s to it } }`를 돌면 되고, 어느 소스가 어느 코드를 갖는지
따로 알 필요가 없다.

**기존 ECOS 경로는 옮기기만 한다.** `RateCollectService` 안에 인라인으로 있던
`EcosQuery(...)` 조립과 `EcosValuePolicy.PERCENT` 지정이 `EcosRateSource`로 이사한다 —
동작이 바뀌는 곳은 없어야 하고, 기존 테스트가 그걸 지킨다. 새 소스가 붙는 것과
기존 소스가 이사하는 것을 **한 커밋에 섞지 않는다**: 섞으면 회귀가 났을 때 둘 중
어느 쪽 때문인지 가릴 수 없다.

## 설정을 소스별로 나눈다

```yaml
market-rate:
  ecos: [...]        # 기존 series[]를 이름만 바꾼다 (6종, 값은 그대로)
  fred:
    - { code: US_FFR,  series-id: DFF }
    - { code: UST_2Y,  series-id: DGS2 }
    - { code: UST_10Y, series-id: DGS10 }
    - { code: UST_30Y, series-id: DGS30 }
```

한 목록에 `source` 태그를 다는 대신 나눈다. 현재 `MarketRateProperties.validate()`가 검사하는
`statCode`·`itemCode`·`cycle`은 **FRED엔 셋 다 무의미하다.** 한 목록으로 두면 검증이
"ECOS면 이걸 보고 FRED면 저걸 본다"는 분기로 변하고 빈 필드가 섞인다.
소스별로 나누면 각 소스가 자기 설정 클래스와 검증을 온전히 소유한다.

`series` → `ecos` 이름 변경은 `MarketRatePropertiesYamlTest`가 잡는다(실제 yml을 단언한다).

## FRED가 결측을 `"."`으로 준다 — 확인된 사실

FRED는 관측이 없는 날의 `value`를 **마침표 문자열 `"."`** 으로 돌려준다. 휴일·미공표일이 그렇고,
시계열의 시작·끝 구간에서 특히 흔하다.

파서가 이걸 그냥 숫자로 읽으려 하면 실패하거나, 더 나쁘게는 0으로 해석한다.
**0%는 금리로서 말이 되는 값이라** — AF-102에서 `EcosValuePolicy.PERCENT`가 0과 마이너스를
일부러 통과시키게 만든 바로 그 이유 때문에 — 값 검증 단계에서는 절대 못 걸러낸다.

**`"."`은 파싱 단계에서 걸러 `skipped`로 센다.** 값 정책이 아니라 파서의 책임이다.
0으로 흘러들어가면 화면에 "미국채 10년 0.00%"가 그럴듯하게 뜬다.

출처: [fred/series/observations](https://fred.stlouisfed.org/docs/api/fred/series_observations.html)

## `T10Y2Y`와 한·미 기준금리차는 저장하지 않는다

FRED가 10년−2년 스프레드를 `T10Y2Y`라는 별도 시리즈로 제공하지만 **받지 않는다.**
`UST_10Y − UST_2Y`로 조회 시 계산한다. 한·미 기준금리차(`BASE_RATE − US_FFR`)도 같다.

이유는 둘이다. 하나는 AF-102의 판단 그대로 — 원본이 정정될 때 따라 안 고쳐지는 값을 저장하면
화석이 된다. 다른 하나는 두 벌이 어긋날 여지를 아예 없애는 것이다: 저장해 두면 어느 날
`UST_10Y - UST_2Y`와 `T10Y2Y`가 다른 값을 말하는 상황이 생기고, 그때 어느 쪽이 맞는지 가릴
방법이 없다.

## `EcosValuePolicy`의 이름이 이제 거짓말이다

FRED도 같은 정책(0·마이너스 허용, ±100 초과 차단)을 쓴다. `RateValuePolicy`로 바꾸고
중립적인 위치로 옮긴다. 기계적 변경이고 컴파일러가 호출부를 전수로 잡는다.

이름만 바꾸고 **판정 로직은 손대지 않는다** — `POSITIVE`(환율)와 `PERCENT`(금리) 둘 다 그대로다.

## 수집 시각은 기존 크론 하나 그대로

평일 KST 18:10(UTC 09:10) 한 번에 한·미를 다 받는다. 그 시각의 최신 미국 데이터는
**전 미국 영업일 것**이다 — 미국장이 아직 열리지도 않았다.

크론을 하나 더 두지 않는 이유: 항목마다 기준일을 표시하므로 거짓말이 아니고, 이미 한국
기준금리가 시장금리보다 이틀 늦게 나오고 있으며, 2주 재조회 창이 다음 날 알아서 메운다.
FRED 자체 공표 지연(`DFF`는 다음 영업일)도 있어 크론을 늦춰도 당일치가 안 나올 수 있다.

## 화면에서 한·미를 어떻게 가르나

`/api/market`의 `rates`에 미국 4종이 그냥 더 실린다. **응답에 국가 필드를 넣지 않는다.**
프런트가 어차피 코드를 한글 라벨로 매핑해야 하므로, 그 표에서 국가 구분도 같이 한다 —
AF-104가 세운 "이름은 프런트가 붙인다" 경계와 같다.

## 오류 처리

AF-102 정책을 그대로 물려받는다(서비스가 공용이므로 자동이다).

- 종목 하나가 실패해도 나머지를 진행한다. 요약의 `failures`에 `"UST_10Y: <사유>"`로 남는다
- 0건은 실패가 아니지만 `emptySeries`에 이름을 남긴다
- **FRED 키 미설정은 ECOS의 `NO_KEY`와 같은 취급이다** — 상류 장애가 아니라 우리 설정 누락이므로
  502가 아니라 500으로 나가야 한다(`GlobalExceptionHandler`가 code로 가른다)

## 테스트

- 파서: `"."`이 `skipped`로 세어지는지, 0과 마이너스는 살아남는지, 정상 행이 파싱되는지
- 소스: 설정의 `series-id`가 요청에 실리는지, 키가 없으면 우리 code로 예외가 나는지
- 서비스: 소스가 둘일 때 양쪽 코드가 모두 수집되는지, 한 소스가 통째로 실패해도 다른 소스가
  저장되는지(현재는 종목별 격리만 테스트돼 있다)
- 설정: `ecos`/`fred` 두 목록이 각각 바인딩되는지, 실제 yml에 미국 4종이 들어 있는지

## 확인이 필요한 것 (키 발급 후)

- **없는 `series_id`에 FRED가 무엇을 돌려주는가.** HTTP 400인지 빈 결과인지에 따라
  "코드가 틀렸다"와 "기간이 비었다"를 가를 수 있는지가 달라진다. ECOS는 0건을 줘서 못 갈랐고,
  그래서 탐색 엔드포인트를 만들어야 했다. FRED가 오류를 준다면 그 장치가 필요 없다
- `DFF`·`DGS2`·`DGS10`·`DGS30`이 실제로 그 이름인지 (문서 기준이지만 실호출로 확인한다)
- 값 단위가 연 %인지 (`DGS10`이 `4.25`인지 `0.0425`인지)
