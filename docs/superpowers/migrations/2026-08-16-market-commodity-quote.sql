-- AF-108 원자재 시세 — 운영 Neon 1회성 마이그레이션
-- 실행: Neon 콘솔 SQL 편집기에서 실행한다.
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.
--
-- 원자재 시세 (AF-108 이후 소스 재선정). 지수와 다른 테이블인 이유는 slot과 unit이다.
--
-- slot이 없다: 세 소스(FRED/EIA 일간·FRED/IMF 월간·공공데이터포털 금) 모두 하루(또는 한 달)
-- 한 값이라 OPEN/MID/CLOSE 개념이 없다. market_index_quote를 재사용하면 slot에 CLOSE를
-- 억지로 채우게 되고, 그러면 "종가"라는 말이 원자재 행에서만 다른 뜻이 된다.
--
-- unit과 frequency를 행에 저장한다: 코드에 상수로 들고 있으면 소스가 바꾼 날 저장은
-- 멀쩡한데 화면만 조용히 틀린다. 관측과 함께 온 속성이므로 관측과 함께 남긴다.
--
-- prev_close·change_*는 nullable이다: 첫 관측이거나 직전 값이 없으면 채울 것이 없다.
-- 0(무변동)과 null(직전 값 없음)은 다르다 — AF-104가 이 구분을 놓쳐 사고를 냈다.
--
-- 월간 행의 trade_date는 그 달의 1일이다(IMF 관측일 규약 그대로). 월말로 옮기지 않는다.
--
-- id를 대리키로 쓴다: 형제 시세 표 둘(market_rate·market_index_quote)이 둘 다
-- 대리키 PK + uk_ 유니크 제약 패턴이고, Task 5에서 그대로 옮겨 오는 수집 서비스가
-- 그 리포지터리 모양(대리키 PK로 upsert)에 붙어 있다. 자연키 유일성은 아래
-- uk_market_commodity_quote 제약이 그대로 진다.

CREATE TABLE IF NOT EXISTS market_commodity_quote (
    id            UUID           NOT NULL,
    code          VARCHAR(20)    NOT NULL,
    trade_date    DATE           NOT NULL,
    price         NUMERIC(18,4)  NOT NULL,
    unit          VARCHAR(20)    NOT NULL,
    frequency     VARCHAR(1)     NOT NULL,   -- D | M
    prev_close    NUMERIC(18,4),
    change_value  NUMERIC(18,4),
    change_rate   NUMERIC(9,4),
    source        VARCHAR(20)    NOT NULL,   -- FRED | FSC. EIA/IMF 구분은 frequency가 진다
    collected_at  TIMESTAMP      NOT NULL,   -- 앱이 항상 채운다 — DEFAULT를 두면 빠뜨렸을 때 조용히 틀린 값이 들어간다
    CONSTRAINT pk_market_commodity_quote PRIMARY KEY (id),
    -- (code, trade_date) btree는 유일성뿐 아니라 조회도 겸한다: 코드별 최신값 조회
    -- (code = ? ORDER BY trade_date DESC LIMIT 1)와 구간 조회
    -- (code = ? AND trade_date BETWEEN ? AND ?) 둘 다 이 인덱스 하나로 충분하다 —
    -- Postgres는 btree를 역방향으로도 비용 없이 스캔한다. code가 등치로 고정되는 한
    -- 별도 DESC 인덱스는 쓸 일이 없으니 다시 추가하지 않는다.
    CONSTRAINT uk_market_commodity_quote UNIQUE (code, trade_date)
);

COMMENT ON TABLE  market_commodity_quote            IS '원자재 시세 — 에너지(일간)·금(D+1)·월간 지표';
COMMENT ON COLUMN market_commodity_quote.unit       IS 'USD/bbl · USD/MMBtu · USD/MT · USD/lb · USc/lb · KRW/g · index — USD/lb와 USc/lb는 100배 다르다';
COMMENT ON COLUMN market_commodity_quote.frequency  IS 'D=일간 M=월간. 화면이 섹션을 가르는 기준';

SELECT count(*) AS existing_rows FROM market_commodity_quote;
