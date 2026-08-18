# 공시 연동 (D1) — 설계

- 작성일: 2026-08-18
- 범위: ① 보유종목 주요공시 피드, ② 임원·주요주주 소유변동
- 원안: 노션 「ALLFOLIO 공시 연동 설계 — 2026-08-16」. **이 문서는 그 원안을 S0·S1 실측으로 개정한 것이고, 충돌하면 이쪽이 맞다.**
- 데이터 소스: OpenDART 단독 (무료·합법·인증키만 필요)

## 0. 실측 표본 — 무엇을 보고 썼는가

추측으로 쓴 스키마가 하나도 없다. 아래가 근거의 전부다.

| 대상 | 표본 | 기간 |
|---|---|---|
| `list.json` | **8,667건** (상장 5,394 / 비상장 3,273) | 2026-08-11 ~ 08-18, 6영업일 |
| `elestock.json` | **3,922행 / 30개사** | 회사별 전체 이력(약 2년) |

일자별 건수: 8/11 898 · 8/12 1,085 · 8/13 2,034 · **8/14 4,555** · 8/17 0 · 8/18 95.

8/14가 다섯 배인 것은 **반기보고서 법정기한**이고, 8/17이 0인 것은 **광복절 대체공휴일**이다. 이 두 날이 각각 3절·5절의 설계를 바꿨다.

## 1. 설계 원칙

원안 그대로다. 판단이 갈리면 여기로 돌아온다.

1. **종목별 조회 금지** — `list.json`을 날짜 기준으로 전량 적재하고, 보유종목 결합은 **조회 시점 조인**으로 한다. 사용자별 피드를 사전 계산하지 않는다.
2. **API 한도는 병목이 아니다** — 실제 병목은 Neon CU-hours와 Render 인스턴스 시간이다.
3. **판별 불가 데이터는 표시하지 않는다** — 이 원칙이 6절에서 ②의 범위를 잘라냈다.
4. **필터링한 데이터도 저장한다** — `is_material=false` 건도 전부 적재한다. 무엇을 걸렀는지 되짚을 수 없으면 튜닝이 불가능하다.

## 2. 아키텍처

배치 위치는 **`backend-app`**. `market-data`는 장중 기동 스케줄인데 공시는 15:30~19:00에 몰려 정반대이고, 공시는 최종적으로 보유종목과 조인되어 사용자 피드로 나가므로 도메인상으로도 `backend-app`이 맞다. `market-data`는 시세 전용으로 남긴다.

```
GitHub Actions cron (매일 19:00 KST)
  → POST /internal/dart/collect  (SCHEDULER_TOKEN 인증)
    → list.json 수집 (bgn_de=D-1, end_de=D, page_count=100 전 페이지)
    → dart_disclosure INSERT ... ON CONFLICT DO NOTHING RETURNING   [TX1]
      → 화이트리스트 판정 (is_material / material_tier)
    → Outbox 적재                                                    [TX2]
    → Outbox 소비 → 임원·주요주주 보고서만 elestock 호출             [TX3]
      → dart_insider_trade 적재
조회 시점: stock_code 조인 → 사용자 피드
```

`RETURNING`이 **실제 삽입된 행만** 반환한다. 이 결과가 곧 델타이고, 후속 처리는 오직 이것만 소비한다. 배치를 몇 번 재실행해도 부작용이 없다.

`elestock` 호출 실패가 공시 수집을 롤백시키면 안 된다.

> **⚠️ Outbox는 만들지 않았다 (2026-08-18 구현 결과).** 위 도식의 `[TX2] Outbox 적재` · `[TX3] Outbox 소비`는 **구현되지 않았다.** 실제로는 `DartRunPlan.run`이 델타(`List<String>`)를 메모리로 넘겨 두 서비스를 순차 호출하고, `elestock` 단계 실패는 try/catch로 격리해 `InsiderCollectSummary.failures`에 담는다. Outbox 테이블도 별도 소비자도 없다.
>
> 대가는 9절에 적어 둔 **② 부분 처리 결손**이다 — 중간에 죽으면 뒤쪽 회사의 소유변동이 영구히 안 채워진다. Outbox가 있으면 재처리로 복구되지만, 그것을 만드는 비용과 이 결손의 빈도(배치 중간 사망)를 견줘 지금은 감수하기로 했다.
>
> **이 도식을 근거로 Outbox가 있다고 가정하지 말 것.**

## 3. 수집 정책

| 항목 | 값 | 비고 |
|---|---|---|
| 실행 주기 | 매일 19:00 KST (10:00 UTC), 월~토 | 단일 배치 |
| 조회 범위 | `bgn_de = D-1`, `end_de = D` | 이틀치 고정. 늦게 반영된 건 자동 회수 |
| 페이지 | `page_count=100`, 전 페이지 순회 | 조기중단 없음 |
| 트리거 | GitHub Actions cron → HTTP | Spring `@Scheduled` 금지 (Render sleep) |
| API 콜 | 평시 **20~30**, 정기보고서 마감 다음날 **90+** | 한도 20,000의 0.5% 미만 |

**원안의 "일 60~80콜"은 평시 기준으로만 맞다.** 실측 최대일(8/14)은 하루만으로 46페이지고, D-1+D 이틀치면 90페이지를 넘는다. 한도에는 여유가 있지만 Render 인스턴스 시간에는 영향이 있으니 타임아웃을 넉넉히 잡는다.

### `status: "013"`은 실패가 아니다

공휴일에 OpenDART는 `{"status":"013","message":"조회된 데이타가 없습니다."}`를 준다. 2026-08-17(광복절 대체공휴일) 응답이 이것이다.

**이걸 에러로 다루면 공휴일마다 배치가 빨갛게 된다.** `013`은 정상 공백으로 처리하고 `dart_collection_run`에 `status=SUCCESS, new_count=0`으로 기록한다. 실패로 볼 것은 `000`·`013` 외의 status와 HTTP 오류뿐이다.

> 콜드 스타트 재시도 예산은 기존 예약 워크플로 관례(300초/retry 1/timeout 14분)를 그대로 따른다. 재시도가 서버 수집을 겹쳐 돌려 이미 성공한 실행을 실패로 만든 전례가 있다.

## 4. 데이터 모델

Flyway 도입은 **이 범위에서 하지 않는다.** 기존 `docs/superpowers/migrations/*.sql`을 손으로 Neon에 적용하는 방식을 그대로 쓴다. `backend-app`은 `ddl-auto: none`이라 **테이블은 아무도 대신 만들어 주지 않는다** — 배포 전 적용 여부를 반드시 확인한다.

### `dart_corp_map` — corp_code ↔ stock_code

```sql
CREATE TABLE dart_corp_map (
    corp_code   VARCHAR(8)   PRIMARY KEY,
    corp_name   VARCHAR(200) NOT NULL,
    stock_code  VARCHAR(6),
    modify_date DATE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_corp_map_stock
    ON dart_corp_map (stock_code) WHERE stock_code IS NOT NULL;
```

`corpCode.xml`(ZIP)에서 주 1회 갱신한다.

**실측 (2026-08-18 실제 호출):**

| 항목 | 값 |
|---|---|
| ZIP | 3,596,918 bytes (3.4 MB) |
| 압축 해제 | 30,059,956 bytes (**28.7 MB**), 엔트리명 `CORPCODE.xml`(대문자) |
| 행 수 | **118,712** |
| `stock_code` 있음 | **3,983** (3.4%) |
| `stock_code` 공백 | 114,729 (96.6%) — 빈 문자열이 아니라 **공백 한 칸 `' '`** |
| `modify_date` 파싱 불가 | 0 |
| 태그 | `corp_code` · `corp_name` · `corp_eng_name` · `stock_code` · `modify_date` |

**상장사만 적재한다.** 이 테이블의 용도 둘(수집 시점 스냅샷 보정 · `corp_code` 역방향 조회)이 모두 `stock_code`를 전제하므로, 없는 114,729행은 어느 쪽에도 기여하지 않는다. Neon CU-hours가 병목(1절 원칙 2)인데 30배를 실을 이유가 없다.

**28.7 MB를 DOM으로 올리지 않는다.** Render 무료 인스턴스는 512MB다. StAX로 훑으면서 상장사만 남긴다.

### `dart_disclosure` — 공시 원장

```sql
CREATE TABLE dart_disclosure (
    rcept_no       VARCHAR(14)  PRIMARY KEY,   -- 반드시 VARCHAR
    corp_code      VARCHAR(8)   NOT NULL,
    corp_name      VARCHAR(200) NOT NULL,
    stock_code     VARCHAR(6),                 -- 빈 문자열은 NULL로 정규화
    corp_cls       VARCHAR(1),                 -- Y/K/N/E
    report_nm      TEXT         NOT NULL,      -- 원문 그대로 (trim만)
    report_nm_norm TEXT         NOT NULL,      -- 5절 정규화 결과
    rcept_dt       DATE         NOT NULL,
    flr_nm         VARCHAR(200),
    rm             VARCHAR(20),
    is_material    BOOLEAN      NOT NULL DEFAULT FALSE,
    material_tier  SMALLINT,                   -- 1~5
    is_correction  BOOLEAN      NOT NULL DEFAULT FALSE,
    collected_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_disclosure_feed
    ON dart_disclosure (stock_code, rcept_dt DESC)
    WHERE is_material AND stock_code IS NOT NULL;
CREATE INDEX idx_disclosure_dt ON dart_disclosure (rcept_dt DESC);
```

**`rcept_no`는 반드시 VARCHAR.** 14자리 숫자형으로 선언하면 선행 0이 소실되어 원문 링크가 깨지고 중복 판정이 무너진다.

**원안에 있던 `pblntf_ty`·`pblntf_detail_ty`는 뺐다.** `list.json` 응답에 그 필드가 없다 — 실측 필드는 `corp_code · corp_name · stock_code · corp_cls · report_nm · rcept_no · flr_nm · rcept_dt · rm` 아홉 개뿐이다. 상세유형 코드는 별도 오퍼레이션을 봐야 하고, 5절대로 v1은 키워드 매칭이므로 지금은 필요 없다.

`rm` 실측 값은 조합형이다: `''` · `유` · `코` · `공` · `정` · `코정` · `넥` · `공정` · `연` · `유정` · `정연` · `채`. 원문 보존만 하고 해석하지 않는다.

### `dart_insider_trade` — 임원·주요주주 소유변동

**원안에서 가장 크게 바뀐 부분이다.** 근거는 6절.

```sql
CREATE TABLE dart_insider_trade (
    id                BIGSERIAL    PRIMARY KEY,
    rcept_no          VARCHAR(14)  NOT NULL REFERENCES dart_disclosure(rcept_no),
    corp_code         VARCHAR(8)   NOT NULL,
    stock_code        VARCHAR(6),
    repror            VARCHAR(200) NOT NULL,  -- 보고자
    officer_position  VARCHAR(100),           -- isu_exctv_ofcps    ("-" → NULL)
    is_registered     BOOLEAN,                -- isu_exctv_rgist_at  등기/비등기, "-" → NULL
    major_holder_type VARCHAR(50),            -- isu_main_shrholdr   원문 보존, "-" → NULL
    report_date       DATE         NOT NULL,  -- rcept_dt (elestock은 하이픈 포맷)
    owned_qty         BIGINT,                 -- sp_stock_lmp_cnt       콤마 제거
    change_qty        BIGINT,                 -- sp_stock_lmp_irds_cnt  음수 가능
    owned_rate        NUMERIC(7,2),           -- sp_stock_lmp_rate
    change_rate       NUMERIC(7,2),           -- sp_stock_lmp_irds_rate
    collected_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_insider UNIQUE (rcept_no, repror)
);
CREATE INDEX idx_insider_feed ON dart_insider_trade (stock_code, report_date DESC);
```

원안 대비:

- **`change_reason_raw`·`change_type`·`is_displayable` 삭제** — 채울 소스가 없다 (6절)
- **`change_date` → `report_date`** — elestock에 변동일 필드가 없다. 접수일뿐이다
- **`is_major_holder BOOLEAN` → `major_holder_type VARCHAR`** — 값이 `10%이상주주`·`사실상지배주주` 두 종이다. 불리언으로 접으면 정보가 사라진다
- **지분율 2개 추가** — 수량만으로는 규모를 읽을 수 없다
- **`uq_insider`를 `(rcept_no, repror)`로 축소** — 3,922행에서 `rcept_no` 단독이 이미 전건 고유였다(보고서당 1행). 보고자를 붙이는 것은 한 보고서에 보고자가 둘인 경우가 나와도 깨지지 않게 하려는 여유분이다. 원안의 `(rcept_no, repror, change_date, change_qty)`는 존재하지 않는 컬럼을 참조한다

### `dart_collection_run` — 배치 실행 기록

```sql
CREATE TABLE dart_collection_run (
    id             BIGSERIAL   PRIMARY KEY,
    run_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    bgn_de         DATE        NOT NULL,
    end_de         DATE        NOT NULL,
    pages_fetched  INT         NOT NULL DEFAULT 0,
    api_calls      INT         NOT NULL DEFAULT 0,
    new_count      INT         NOT NULL DEFAULT 0,
    elestock_calls INT         NOT NULL DEFAULT 0,
    status         VARCHAR(20) NOT NULL,       -- SUCCESS / PARTIAL / FAILED
    error_msg      TEXT,
    finished_at    TIMESTAMPTZ
);
```

## 5. 화이트리스트

v1은 `pblntf_detail_ty` 코드가 아니라 `report_nm` 키워드 매칭으로 간다. 코드값이 응답에 없기도 하고, 키워드 방식이 로그를 보며 즉시 튜닝 가능하다.

### 정규화 — 3단이고, 순서가 있다

```
1. trim              (앞뒤 공백 제거)
2. 접두어 제거        (^\[[^\]]+\])
3. 구분자 통일        (ㆍ U+318D, ・ U+30FB → · U+00B7)
```

**3단계가 원안의 결함을 고친다.** DART는 구분자로 `ㆍ`(U+318D HANGUL LETTER ARAEA)를 쓰는데 원안 키워드는 `·`(U+00B7 MIDDLE DOT)로 적혀 있었다. 실측 8,667건에서 `ㆍ` 2,856회 · `·` 1회다. 원문 그대로 매칭하면 아래가 **전부 0건**이었다:

| 원안 키워드 | 실제 표기 | 놓치던 건수 |
|---|---|---|
| `단일판매·공급계약체결` | `단일판매ㆍ공급계약체결` | 52 |
| `현금·현물배당결정` | `현금ㆍ현물배당결정` | 26 |
| `소송등의제기·신청` | `소송등의제기ㆍ신청` | 표본 내 0 |
| `횡령·배임` | `횡령ㆍ배임` | 표본 내 0 |
| `유형자산 취득·처분결정` | `유형자산취득결정` / `유형자산처분결정` | 공백까지 불일치, 별개 공시 |

Tier 1의 핵심인 단일판매·공급계약체결이 통째로 빠지는 것은 피드 품질 문제가 아니라 기능 부재다.

**키워드만 고치지 않고 정규화를 넣는 이유** — 다음에 `・`(U+30FB)나 새 변종이 나오면 같은 일이 반복된다. 키워드는 `·`로 통일해 적고, 입력을 정규화해서 맞춘다.

**1단계가 필요한 이유** — `report_nm` 뒤에 공백이 붙은 건이 887건(10%)이다: `'단일판매ㆍ공급계약체결              '`. trim이 없으면 완전일치 매칭이 무너진다.

**2단계를 열거가 아니라 패턴으로 하는 이유** — 실측 접두어는 `[기재정정]`875 · `[첨부정정]`29 · `[첨부추가]`20 · `[발행조건확정]`4 · `[변경등록]`1의 5종이다. 원안이 적은 `[정정]` 단독은 0건이고, `[발행조건확정]`·`[변경등록]`은 원안에 없다. 열거하면 또 새는다. 접두어 존재 여부는 `is_correction`에 별도로 남긴다.

### Tier

| Tier | 분류 | 키워드 (정규화 후 부분일치) |
|---|---|---|
| 1 | 주가 직결 | 유상증자결정 / 무상증자결정 / 감자결정 / 전환사채권발행결정 / 신주인수권부사채권발행결정 / 교환사채권발행결정 / 자기주식취득결정 / 자기주식처분결정 / 주식소각결정 / 회사합병결정 / 회사분할결정 / 영업양수도결정 / 단일판매·공급계약체결 / 유형자산취득결정 / 유형자산처분결정 / 타법인주식및출자증권취득결정 / 최대주주변경 |
| 2 | 재무·실적 | 매출액또는손익구조 / 현금·현물배당결정 |
| 3 | 위험 | 소송등의제기·신청 / 부도발생 / 회생절차개시신청 / 자본잠식 / 관리종목지정 / 상장폐지 / 매매거래정지 / 횡령·배임 / 조회공시요구 |
| 4 | ② 트리거 | 임원·주요주주특정증권등소유상황보고서 |
| **5** | **정기보고서** | **사업보고서 / 반기보고서 / 분기보고서 / 감사보고서** |
| — | 제외 | 투자설명서 / 증권발행실적보고서 / 기업설명회(IR)개최 / 주주총회소집공고 / 각종 신고서·확인서 |

**Tier 5는 신설이다.** 원안에서 정기보고서는 Tier 2에 있었는데, 정규화 수정 후 실측하니 T2 2,895건 중 **2,738건이 `반기보고서`** 하나였다. 정기보고서는 제출 시즌에 전 종목이 한꺼번에 올라오므로, 보유 10종목이면 이틀간 피드가 반기보고서로만 채워진다.

분리하되 **버리지는 않는다** — `is_material`은 T5도 true다. 저장·조인은 그대로 되고 기본 정렬에서만 뒤로 간다. 실적 발표는 놓치면 안 되는 정보다.

정규화 수정 + T5 분리 후 상장사 기준 실측:

| 구간 | 건수 | T1 | T2 | T3 | T4 | T5 | is_material |
|---|---|---|---|---|---|---|---|
| 6영업일 전체 | 5,394 | 211 | 49 | 111 | 290 | 2,846 | 65% |
| 8/14(반기마감) 제외 | 2,192 | 160 | 37 | 43 | 187 | 571 | 46% |

`material_tier`는 피드 정렬 우선순위이자, 추후 사용자별 알림 레벨 설정에 그대로 재사용한다.

### `stock_code` 빈 문자열 → NULL

실측상 `stock_code`가 비어 있는 3,273건은 **빈 문자열 `""`이지 NULL이 아니고, 전부 `corp_cls=E`(비상장)**다. 적재 시 NULL로 정규화하지 않으면 `WHERE stock_code IS NOT NULL` 부분 인덱스가 무용지물이 되고, 비상장 공시가 조인에서 자연 제외되지도 않는다.

## 6. ② 임원 소유변동 — 매수/매도를 말하지 않는다

**`elestock` 응답에 변동사유 필드가 없다.** 30개사 3,922행의 필드 집합이 단일하고, 취득/처분 방법에 해당하는 키가 하나도 없다:

```
rcept_no · rcept_dt · corp_code · corp_name · repror
isu_exctv_rgist_at · isu_exctv_ofcps · isu_main_shrholdr
sp_stock_lmp_cnt · sp_stock_lmp_irds_cnt · sp_stock_lmp_rate · sp_stock_lmp_irds_rate
```

원안 7절이 예고한 최악 분기가 확정됐다. 따라서:

- **`change_type` 분류기(원안 S10)는 성립하지 않는다.** 태스크째로 드롭한다
- **화면에는 "소유수량 변동" 사실만 낸다** — 증감수량 · 지분율 · 직위 · 등기여부 · 주요주주 유형
- **UI 카피에서 "매수"·"매도"·"장내매수" 표현을 쓰지 않는다.** 무상증자나 스톡옵션 행사를 매수로 오표기하는 것은 금융 서비스에서 회복 불가능한 신뢰 손상이다 (원칙 3). 규제 측면에서도 "주목 종목"·"매수 신호" 류 큐레이션 뉘앙스는 유사투자자문 소지가 있다

원문 링크로 사유를 확인할 수 있게 하는 것이 대안이자 원칙이다.

### elestock 호출 규약

- **`rcept_no`가 응답에 포함된다** — 원안 7절 항목 1은 해결. Outbox 델타와 직접 매칭한다
- **기간 파라미터가 없어 회사 전체 이력(약 2년)이 온다.** 실측 최대 3,395행(삼성전자). **델타의 `rcept_no` 집합에 포함된 행만 취한다** — 이 필터가 없으면 재호출마다 중복이 쌓인다
- **`rcept_dt` 포맷이 `list.json`과 다르다.** `list.json`은 `20260818`, `elestock`은 `2026-08-14`. 같은 이름 필드를 같은 파서로 읽으면 깨진다
- **수량은 콤마 포함 문자열**(`"1,288,273"`, `"-13,007"`), **결측은 `"-"`**. 파싱 전 정규화한다
- 호출 대상은 델타 중 `임원·주요주주특정증권등소유상황보고서`의 `corp_code`뿐이다. 실측 6영업일 델타 기준 150개사

## 7. 조회

### 보유종목 조인

```sql
SELECT d.*
FROM dart_disclosure d
JOIN <보유 테이블> h ON h.stock_code = d.stock_code
WHERE h.user_id = :userId
  AND d.is_material
  AND d.rcept_dt >= :from
ORDER BY d.material_tier, d.rcept_dt DESC;
```

**원안이 참조한 `user_holdings` 테이블은 존재하지 않는다.** 실제 보유 테이블은 `position_daily`와 `ua_assets`다. 어느 쪽을 조인할지(또는 둘 다인지)는 구현 시 두 테이블의 실제 스키마를 보고 정한다 — 지금 추측해서 스펙에 박지 않는다.

사용자별 피드 테이블은 만들지 않는다. 종목 매매 시마다 재계산해야 하고 사용자 수만큼 행이 불어난다.

### 원문 링크

```
https://dart.fss.or.kr/dsaf001/main.do?rcpNo={rcept_no}
```

요약 생성 대신 링크 제공이 원칙이다. 저작권·정확성·규제 리스크를 동시에 회피한다.

### 정정공시

`[기재정정]`은 **새 `rcept_no`를 발급받는다.** 원본 참조는 본문 XML에만 있고 `list.json`에는 없다. 완벽한 연결은 원문 파싱이 필요하나 값어치가 없다.

실용안: `(corp_code, report_nm_norm)`으로 그룹핑해 **최신 건만 피드 노출**, 이전 건은 접어둔다. 정규화가 접두어를 떼므로 원본과 정정본이 같은 그룹에 들어간다.

## 8. 작업 순서

- [x] **S0** OpenDART 인증키 + `list.json` 응답 구조 확인 — 2026-08-18 완료, 결과는 0·3·5절
- [x] **S1** `elestock` 선행 검증 — 2026-08-18 완료, 결과는 6절
- [ ] **S3** `dart_*` 테이블 4개 마이그레이션 작성·적용 (`docs/superpowers/migrations/`)
- [ ] **S4** `corpCode.xml` ZIP 파서 + `dart_corp_map` 적재 배치 (주 1회)
- [ ] **S5** `list.json` 수집기 — 페이지네이션, `013` 처리, `ON CONFLICT ... RETURNING`, `dart_collection_run` 기록
- [ ] **S6** 화이트리스트 판정기 — 3단 정규화, Tier 1~5 분류
- [ ] **S7** `/internal/dart/collect` 엔드포인트 + `SCHEDULER_TOKEN` 인증 (공개 금지)
- [ ] **S8** GitHub Actions cron 워크플로 (`0 10 * * 1-6`)
- [ ] **S9** Outbox 소비 → `elestock` 호출 → `dart_insider_trade` 적재 (델타 `rcept_no` 필터 포함)
- [ ] **S11** 조회 API — 보유종목 조인, Tier 정렬, 페이징
- [ ] **S12** FE 피드 화면 — 공시 카드, 원문 링크, 소유변동 섹션
- [ ] **S13** 1주 운영 후 화이트리스트 튜닝 (`is_material=false` 로그 검토 + 아래 기지 오분류 2건)

**S2(Flyway 도입)는 이 범위에서 제외**했다. 공시 연동과 독립적이고, prod 스키마 baseline 덤프는 그 자체로 별건이다. 기존 수동 마이그레이션 패턴을 그대로 쓴다.

**S10(`change_type` 분류기)은 드롭**했다. 6절 참조.

### S13이 손댈 기지 오분류 — 부분일치는 부정형을 못 본다

구현 중 표본 8,667건으로 키워드별 매치를 전수 확인하다 나왔다. **고치지 않고 남긴 것이지 못 본 것이 아니다** — 5절 표의 Tier 분포가 이 상태에서 산출된 공표값이고, 키워드를 바꾸면 그 숫자가 전부 달라진다.

**① `매매거래정지`(T3) — 27행 중 17행(63%)이 "위험"이 아니다**

| 건수 | 정규화된 이름 |
|---|---|
| 7 | 주권매매거래정지**해제** (액면병합 주권 변경상장) |
| 7 | 주권매매거래정지 (주식의 병합, 분할 등 전자등록 변경, 말소) |
| 2 | 주권매매거래정지**해제** (상장폐지에 따른 정리매매 개시) |
| 2 | 매매거래정지및정지**해제**(풍문등조회공시) |
| 2 | 주권매매거래정지기간변경 (상장적격성 실질심사 대상(사유발생)) |
| 1×5 | …**해제** (감자·액면분할 변경상장 / 실질심사 대상 제외 결정) 등 |

두 부류다. **부정형** — `정지해제`는 정지가 풀린 것인데 `정지`를 부분일치로 잡는다(순수 해제 12행). **행정적 정지** — 액면병합·액면분할·감자에 따른 변경상장은 결제 사무이지 T3가 뜻하는 위험(부도·자본잠식·횡령·배임)이 아니다.

**② `상장폐지`(T3) — `기타시장안내 (정기보고서 미제출 관련 상장폐지 절차 미진행)` 1건**

절차가 **진행되지 않는다**는 안내인데 T3로 잡힌다. 같은 부정형 문제다.

**튜닝 방향** — 부정형은 제외 키워드(`해제` · `미진행`)를 두거나 완전일치 쪽으로 좁히는 것이 후보다. 다만 영향은 **정렬 순위에 한정**된다: 화면에는 `report_nm` 원문이 그대로 나가고(9절이 큐레이션성 카피를 금지한다) 사용자는 이름 자체를 읽는다. 거짓 주장이 아니라 순서가 어색한 것이라 급하지 않다. 손댈 때 5절 표를 함께 다시 산출할 것.

## 9. 미결 사항

| 항목 | 내용 | 대응 |
|---|---|---|
| 보유 테이블 | `position_daily` vs `ua_assets` 중 조인 대상 미정 | S11 착수 시 실제 스키마 확인 |
| `pblntf_detail_ty` | `list.json`에 없음 | v1은 키워드 매칭. 코드 전환은 별도 오퍼레이션 확인 후 v2 |
| `rm` 코드표 | 12종 조합형, 의미 미확인 | 원문 보존만. 해석하지 않음 |
| elestock 이력 범위 | 약 2년(2024-08 ~ 2026-08)으로 보이나 공식 문서 미확인 | 델타 필터가 있으므로 영향 없음 |
| OpenDART 반영 지연 | 접수 → API 노출 지연 미측정 | 19:00 배치 + D-1 재수집으로 흡수 |
| ② 부분 처리 결손 | Task 11이 150개사를 도는 중 죽으면 **뒤쪽 회사의 소유변동이 영구히 안 채워진다.** 델타는 Task 8이 이번 실행에서 새로 넣은 `rcept_no`이고, 다음 실행에는 그 `rcept_no`가 다시 오지 않는다(`ON CONFLICT DO NOTHING`이 건너뜀). 그 회사가 나중에 또 Tier 4 공시를 내도 옛 `rcept_no`는 그때의 델타 집합에 없어 필터에 걸린다 | **현재는 감수하고 `failures`로 노출만 한다.** 여러 회사를 한 `@Transactional`로 묶는 것은 해법이 아니다 — 한 회사 실패가 나머지를 롤백해 "회사별 실패 격리"와 정면 충돌한다. **더 나은 대안: 작업 목록을 인메모리 델타가 아니라 DB에서 도출한다** — `dart_disclosure`에서 `material_tier=4`이면서 대응하는 `dart_insider_trade` 행이 없는 것을 조회하면 자가 치유가 된다. 설계 2절이 델타 구동으로 못 박아 둔 것을 바꾸는 일이라 별도 결정이 필요하다 |
| `@Transactional` 회귀 | `JdbcDisclosureStore`의 `@Transactional`을 지워도 **단위 테스트 9개가 전부 통과한다** — 가짜가 클래스를 직접 생성해 스프링 AOP 프록시를 안 거친다 | 레포에 Testcontainers·Postgres 통합 테스트 기반이 없어 이 층에서는 못 막는다. Postgres 레벨 실증(청크1 커밋 후 청크2 실패 시 트랜잭션 없으면 3행 영구 잔존, 있으면 `(0 rows)`)으로 대체했다. **이 애너테이션을 지우면 아무 테스트도 안 깨지고, 깨지는 건 운영에서 배치가 중간에 죽는 날이다** |

## 부록 — ③ 국민연금/연기금 수급을 제외한 근거

원안 부록 그대로 유효하다. 정당하게 취득할 경로가 없어 범위에서 제외했다. 요약하면:

- DART 5%룰은 국민연금 단순투자 목적 시 **변동보고 기한이 익월 10일** → 최대 40일 지연. 기금운용본부 연간 포트폴리오는 **약 9개월 지연**
- KRX `MDCSTAT02401`(투자자별 순매수상위종목)이 이상적이나 **공식 `openapi.krx.co.kr` 서비스 목록에 없다**(2026-08-16 확인). `data.krx.co.kr` 내부 엔드포인트는 계정 로그인 필수로 변경돼 약관 위반 소지
- KRX 「데이터 구입」과 「데이터 분배」는 별도 계약. 서비스 화면 노출은 **분배**에 해당할 가능성이 높다

재도입 시 표기 원칙: **`연기금등` ≠ `국민연금`**이다. 사학연금·공무원연금이 포함되고 국민연금 위탁운용 물량은 투신·금융투자로 분류된다. 반드시 원 카테고리명과 기준일을 병기한다.

**부수 관찰** — `elestock`의 `repror`에 `국민연금공단`이 등장한다(`isu_main_shrholdr=10%이상주주`). 10% 이상 보유한 종목에 한해 ② 배치의 부산물로 국민연금 지분 변동이 잡힌다. 커버리지가 좁아 기능으로 세울 수는 없지만, 데이터는 이미 들어온다.
