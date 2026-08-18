-- D1 공시 연동 — 운영 Neon 1회성 마이그레이션
-- 실행: Neon 콘솔 SQL 편집기. 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none).
-- 신규 테이블만 추가하므로 기존 백엔드엔 무해. 멱등.
--
-- 실측 표본: list.json 8,667건(2026-08-11~08-18, 6영업일), elestock 3,922행(30개사).
-- 전체 설계는 docs/superpowers/specs/2026-08-18-dart-disclosure-design.md.
--
-- rcept_no가 VARCHAR인 이유: 14자리 숫자형으로 선언하면 선행 0이 소실되어 원문 링크가
-- 깨지고 중복 판정이 무너진다. 실측 rcept_no 예: 20260818000094.
--
-- stock_code가 nullable인 이유: 비상장사(corp_cls=E) 공시가 실측 8,667건 중 3,273건 들어온다.
-- OpenDART는 이걸 NULL이 아니라 빈 문자열로 주므로 앱이 NULL로 정규화해 넣는다 —
-- 그러지 않으면 아래 부분 인덱스가 무용지물이 되고, 비상장 공시가 조인에서 자연 제외되지도 않는다.
--
-- material_tier 5는 정기보고서다(사업·반기·분기·감사보고서). Tier 2에 두면 제출 시즌에
-- 피드가 그것만으로 찬다 — 실측 6영업일 상장사 5,394건 중 2,846건이 Tier 5였고,
-- 그중 8/14(반기보고서 법정기한) 하루가 2,275건이었다(6영업일 전체 T5 2,846 − 8/14 제외 T5 571).
-- T5도 is_material=true이며 저장·조인은 그대로 되고 기본 정렬에서만 뒤로 간다 — 버리지 않는다.

-- dart_corp_map: corp_code ↔ stock_code. corpCode.xml(ZIP)에서 주 1회 갱신한다.
CREATE TABLE IF NOT EXISTS dart_corp_map (
    corp_code   VARCHAR(8)   PRIMARY KEY,
    corp_name   VARCHAR(200) NOT NULL,
    stock_code  VARCHAR(6),
    modify_date DATE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_corp_map_stock
    ON dart_corp_map (stock_code) WHERE stock_code IS NOT NULL;

-- dart_disclosure: 공시 원장. is_material=false 건도 전부 적재한다 —
-- 무엇을 걸렀는지 되짚을 수 없으면 화이트리스트 튜닝이 불가능하다(설계 1절 원칙 4).
CREATE TABLE IF NOT EXISTS dart_disclosure (
    rcept_no       VARCHAR(14)  PRIMARY KEY,
    corp_code      VARCHAR(8)   NOT NULL,
    corp_name      VARCHAR(200) NOT NULL,
    stock_code     VARCHAR(6),
    corp_cls       VARCHAR(1),                -- Y/K/N/E
    report_nm      TEXT         NOT NULL,     -- 원문 그대로 (trim만)
    report_nm_norm TEXT         NOT NULL,     -- 3단 정규화 결과 (trim → 접두어 제거 → 구분자 통일)
    rcept_dt       DATE         NOT NULL,
    flr_nm         VARCHAR(200),
    rm             VARCHAR(20),
    is_material    BOOLEAN      NOT NULL DEFAULT FALSE,
    material_tier  SMALLINT,                  -- 1~5, NULL=비대상
    is_correction  BOOLEAN      NOT NULL DEFAULT FALSE,
    collected_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 보유종목 피드 조회 전용: 부분 인덱스라 is_material=false·비상장 건은 인덱스에 안 들어간다.
CREATE INDEX IF NOT EXISTS idx_disclosure_feed
    ON dart_disclosure (stock_code, rcept_dt DESC)
    WHERE is_material AND stock_code IS NOT NULL;
-- 날짜 기준 전량 수집·조회용 (설계 1절 원칙 1: 종목별 조회 금지, 날짜 기준 전량 적재).
CREATE INDEX IF NOT EXISTS idx_disclosure_dt ON dart_disclosure (rcept_dt DESC);

-- dart_insider_trade: 임원·주요주주 소유변동. elestock 응답에 변동사유 필드가 없어
-- "매수/매도"를 말할 수 없다 — 소유수량 변동 사실(증감수량·지분율)만 담는다(설계 6절).
CREATE TABLE IF NOT EXISTS dart_insider_trade (
    id                BIGSERIAL    PRIMARY KEY,
    rcept_no          VARCHAR(14)  NOT NULL REFERENCES dart_disclosure(rcept_no),
    corp_code         VARCHAR(8)   NOT NULL,
    stock_code        VARCHAR(6),
    repror            VARCHAR(200) NOT NULL,  -- 보고자
    officer_position  VARCHAR(100),           -- "-" → NULL
    is_registered     BOOLEAN,                -- 등기/비등기, "-" → NULL
    major_holder_type VARCHAR(50),            -- 원문 보존("10%이상주주"·"사실상지배주주" 등), "-" → NULL
    report_date       DATE         NOT NULL,  -- 접수일(rcept_dt). elestock에 변동일 필드가 없다
    owned_qty         BIGINT,
    change_qty        BIGINT,                 -- 음수 가능
    owned_rate        NUMERIC(7,2),
    change_rate       NUMERIC(7,2),
    collected_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 실측 3,922행에서 rcept_no 단독이 이미 전건 고유였다(보고서당 1행).
    -- repror를 붙이는 것은 한 보고서에 보고자가 둘인 경우가 나와도 깨지지 않게 하려는 여유분이다.
    CONSTRAINT uq_insider UNIQUE (rcept_no, repror)
);
CREATE INDEX IF NOT EXISTS idx_insider_feed
    ON dart_insider_trade (stock_code, report_date DESC);

-- dart_collection_run: 배치 실행 기록. status="013"(공휴일 등 조회 데이터 없음)은
-- 실패가 아니라 SUCCESS/new_count=0으로 기록한다 — 그러지 않으면 공휴일마다 배치가 빨갛게 된다.
CREATE TABLE IF NOT EXISTS dart_collection_run (
    id             BIGSERIAL   PRIMARY KEY,
    run_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    bgn_de         DATE        NOT NULL,
    end_de         DATE        NOT NULL,
    pages_fetched  INT         NOT NULL DEFAULT 0,
    api_calls      INT         NOT NULL DEFAULT 0,
    new_count      INT         NOT NULL DEFAULT 0,
    elestock_calls INT         NOT NULL DEFAULT 0,
    status         VARCHAR(20) NOT NULL,      -- SUCCESS / PARTIAL / FAILED
    error_msg      TEXT,
    finished_at    TIMESTAMPTZ
);

-- 검증: 테이블 4개만 생성되고 데이터는 배치·수집 API로 채운다
SELECT
    (SELECT COUNT(*) FROM dart_corp_map)       AS corp_map_rows,
    (SELECT COUNT(*) FROM dart_disclosure)     AS disclosure_rows,
    (SELECT COUNT(*) FROM dart_insider_trade)  AS insider_trade_rows,
    (SELECT COUNT(*) FROM dart_collection_run) AS collection_run_rows;
