-- W5 (AF-143) · 시계 평가 캐시
--
-- watchpricedata `/api/valuation` 응답을 일 1회 복제해 둔다. **사용자 요청 시점에 외부를
-- 부르지 않는다**(설계 7절) — 그쪽은 EC2 단일 인스턴스라 우리 조회 지연이 그 서비스의
-- 가용성에 묶인다.
--
-- `market_commodity_quote`(금) · `rtms_deals_cache`(부동산)와 같은 자리의 표다.
-- 셋 다 "원본 시세 캐시"이고, 평가 결과는 `ua_assets.current_value`에 따로 쓴다.
--
-- **(ref_key, as_of)로 이력을 남긴다.** 한 ref당 한 행으로 덮어쓰지 않는 이유는 금과 같다 —
-- 수집이 하루 실패해도 직전 값으로 폴백할 수 있어야 하고(`as_of <= :asOf ORDER BY DESC`),
-- 과거 화면을 재현할 수 있어야 한다.
--
-- 적용: `ddl-auto: none`이므로 배포 전에 Neon 콘솔에서 직접 실행한다.

CREATE TABLE IF NOT EXISTS watch_valuation_cache (
    id                 UUID          PRIMARY KEY,
    -- watchpricedata가 정규화한 base ref (W3). 사용자가 등록한 원본 ref가 아니라
    -- **매칭 키**다 — `ua_assets.symbol`에 이 값이 그대로 들어간다.
    ref_key            VARCHAR(64)   NOT NULL,
    -- 🔴 **관측일이 아니라 조회일이다.** 응답의 `asOf`는 30일 창의 끝이고, 그 창 안에서
    -- 가장 최근 매물이 언제 것인지는 응답에 없다. 화면이 "이 날짜 시세"라고 말할 때
    -- 무엇을 말하는지 헷갈리지 않도록 컬럼명을 `price_as_of`가 아니라 `as_of`로 둔다.
    as_of              DATE          NOT NULL,
    window_days        SMALLINT      NOT NULL,
    sample_size        INT           NOT NULL,
    -- 평균이 아니라 중앙값이다. 빈티지 1건이 평균을 5배로 흔든 실측이 설계 7절에 있다.
    median_krw         BIGINT        NOT NULL,
    p25_krw            BIGINT,
    p75_krw            BIGINT,
    -- (p75-p25)/median. 신뢰도 판정의 근거라 함께 보관한다 — 나중에 임계치를 바꿀 때
    -- 원본 없이 재판정할 수 있다
    dispersion         NUMERIC(6,4),
    -- 정가. 프리미엄 표시용이고 없을 수 있다(실측: 16233은 null, 126300은 14,100,000)
    official_price_krw BIGINT,
    confidence         VARCHAR(10)   NOT NULL,
    -- 항상 ASK다. 그래도 컬럼으로 두는 이유는 소스가 늘면 섞이기 때문이다(설계 1절 원칙 4)
    price_basis        VARCHAR(10)   NOT NULL,
    -- DB DEFAULT를 두지 않는다 — 컨테이너가 UTC라 앱이 정한 시각을 쓴다
    collected_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_watch_valuation_cache UNIQUE (ref_key, as_of)
);

-- 폴백 조회(`ref_key = ? AND as_of <= ? ORDER BY as_of DESC LIMIT 1`)는 위 유니크
-- 인덱스가 그대로 받는다. Postgres가 btree를 역방향으로도 비용 없이 스캔하므로
-- **별도 인덱스를 만들지 말 것** (금 시세와 같은 판단, 설계 3절).
