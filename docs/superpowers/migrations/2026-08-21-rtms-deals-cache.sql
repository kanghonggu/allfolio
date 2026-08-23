-- A1 실물자산 평가 v3(부동산) — 국토부 실거래가 캐시. 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-08-21-rtms-deals-cache.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.
--
--
-- ## 왜 표가 둘인가
--
-- 국토부 API는 **단지로 물을 수 없다.** 질의 단위가 `(시군구 5자리, 계약년월)`이고 응답은
-- 그 달 그 시군구의 **모든 아파트 거래**다. 그래서 시군구-월을 통째로 받아 로컬에서 거른다.
--
-- 그러면 "어느 (시군구, 월)을 이미 받아 왔는가"를 따로 기록해야 한다. 거래 표만 있으면
-- **거래가 0건인 달**과 **아직 안 받아 온 달**이 구분되지 않는다. 앞은 그 달에 거래가
-- 없었던 것이고 뒤는 우리가 안 물어본 것인데, 둘을 섞으면 영원히 다시 묻거나 영원히 안 묻는다.
-- (금 시세에서 `null`과 0을 가른 것과 같은 판단이다.)
--
-- **일 1,000회 제한**이 이 기록을 필수로 만든다. 한 조합이 200건을 넘으면 페이징으로
-- 호출이 더 든다 — 실측에서 분당(41135) 2026-07이 450건이라 3콜이었다.
--
--
-- ## 자연키를 무엇으로 하는가
--
-- 응답에 거래 고유 ID가 없다. 그래서 `(단지, 전용면적, 계약일, 층, 금액)`을 자연키로 쓴다.
-- 같은 단지·같은 층·같은 면적·같은 날·같은 금액인 거래가 둘일 수는 없다.
--
-- **덮어쓰기(upsert)여야 한다.** 같은 거래가 처음엔 정상으로 왔다가 나중에 해제로 바뀐다
-- (실측 2,698건 중 71건 2.6%가 해제). 한 번 넣고 마는 구조면 취소된 거래가 영원히
-- 시세에 남는다. 그래서 아래 유니크 제약이 그 갱신 경로를 진다.
--
--
-- ## 금액을 원 단위 BIGINT로 저장한다
--
-- 응답은 **만원 단위 콤마 문자열**(`"55,000"` = 5.5억)이다. 파서가 원으로 환산해 넣는다.
-- 문자열이나 만원 단위로 저장하면 읽는 쪽마다 환산을 다시 해야 하고, 한 곳이라도 빠뜨리면
-- 10,000배 틀린 값이 조용히 나간다.
--
--
-- ## 전용면적은 NUMERIC(10,4)이다
--
-- 매칭이 **정확 일치**다. 같은 단지 안에서 평형이 1㎡ 미만으로 붙어 있는 쌍이 실측 146건
-- (`84.6`↔`84.75` · `84.83`↔`84.86` · `114.81`↔`115.0`). 반올림하면 다른 평형이 합쳐진다.

CREATE TABLE IF NOT EXISTS rtms_deals_cache (
    id                UUID           NOT NULL,
    apt_seq           VARCHAR(20)    NOT NULL,   -- 단지일련번호 {시군구5}-{일련} (11110-132)
    apt_name          VARCHAR(200)   NOT NULL,
    exclusive_area_m2 NUMERIC(10,4)  NOT NULL,   -- 전용면적. 매칭 키 — 반올림 금지
    deal_date         DATE           NOT NULL,
    deal_amount_krw   BIGINT         NOT NULL,   -- 원 단위. 응답은 만원 콤마 문자열이다
    floor             INTEGER        NOT NULL,
    build_year        INTEGER,
    sgg_code          VARCHAR(5)     NOT NULL,   -- 수집 단위이자 재수집 대상 조회 키
    umd_name          VARCHAR(100)   NOT NULL,
    is_cancelled      BOOLEAN        NOT NULL DEFAULT FALSE,  -- 해제(취소) 거래 — 중앙값에서 뺀다
    cancelled_on      DATE,                      -- 응답은 두 자리 연도(26.07.13)로 준다
    collected_at      TIMESTAMP      NOT NULL,   -- 앱이 항상 채운다. DEFAULT를 두면 빠뜨렸을 때 조용히 틀린다
    CONSTRAINT pk_rtms_deals_cache PRIMARY KEY (id),
    -- 거래 고유 ID가 없어 만든 자연키. upsert 경로가 이 제약을 탄다
    CONSTRAINT uk_rtms_deals_cache UNIQUE (apt_seq, exclusive_area_m2, deal_date, floor, deal_amount_krw)
);

-- 평가는 (단지, 면적)으로 최근 N개월을 훑는다. 이 순서 그대로가 조회 경로다.
-- deal_date를 뒤에 두는 것은 앞 둘이 등치로 고정되기 때문이다 —
-- Postgres는 btree를 역방향으로도 비용 없이 스캔하므로 DESC 인덱스를 따로 만들지 않는다.
CREATE INDEX IF NOT EXISTS idx_rtms_deals_apt_area_date
    ON rtms_deals_cache (apt_seq, exclusive_area_m2, deal_date);

-- 어느 (시군구, 월)을 이미 받아 왔는지. **거래 0건과 미수집을 가르는 표다.**
CREATE TABLE IF NOT EXISTS rtms_fetch_log (
    sgg_code     VARCHAR(5)  NOT NULL,
    deal_ym      VARCHAR(6)  NOT NULL,   -- yyyyMM. API 파라미터 형식 그대로 둔다
    deal_count   INTEGER     NOT NULL,   -- 그 조합에서 받은 거래 수. 0도 유효한 값이다
    api_calls    INTEGER     NOT NULL,   -- 페이징 포함 호출 수 — 일 1,000콜 예산 추적용
    fetched_at   TIMESTAMP   NOT NULL,
    CONSTRAINT pk_rtms_fetch_log PRIMARY KEY (sgg_code, deal_ym)
);

COMMENT ON TABLE  rtms_deals_cache                 IS '국토부 아파트 매매 실거래가 캐시 — (시군구, 년월) 단위로 받아 로컬에서 (단지, 전용면적)으로 거른다';
COMMENT ON COLUMN rtms_deals_cache.deal_amount_krw IS '원 단위. 응답은 만원 단위 콤마 문자열("55,000"=5.5억)이라 파서가 환산한다';
COMMENT ON COLUMN rtms_deals_cache.is_cancelled    IS '해제(취소) 거래. 실측 2.6%. 중앙값에서 제외할 것 — 성사되지 않은 가격이다';
COMMENT ON COLUMN rtms_deals_cache.exclusive_area_m2 IS '전용면적(㎡). 정확 일치 매칭 키 — 같은 단지에 1㎡ 미만으로 붙은 평형이 실측 146쌍이라 반올림 금지';
COMMENT ON TABLE  rtms_fetch_log                   IS '수집한 (시군구, 년월) 기록 — 거래 0건과 미수집을 가른다. 일 1,000콜 제한 때문에 재수집을 막는 것이 목적';

-- ── 검증 ────────────────────────────────────────────────────────────────────
SELECT
    (SELECT count(*) FROM rtms_deals_cache) AS deals,
    (SELECT count(*) FROM rtms_fetch_log)   AS fetched_combos;
