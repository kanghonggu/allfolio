-- AF-101 지수 시세 — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-08-12-market-index-quote.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.

CREATE TABLE IF NOT EXISTS market_index_quote (
    id               UUID          NOT NULL,
    index_code       VARCHAR(20)   NOT NULL,   -- 우리가 정한 canonical (KOSPI, KOSDAQ, KOSPI200)
    trade_date       DATE          NOT NULL,   -- 시장 현지 거래일
    slot             VARCHAR(10)   NOT NULL,   -- OPEN | MID | CLOSE
    price            NUMERIC(18,4) NOT NULL,
    prev_close       NUMERIC(18,4) NOT NULL,   -- price - change로 역산
    change_value     NUMERIC(18,4) NOT NULL,   -- "change"는 예약어 충돌 소지가 있어 접미사를 붙인다
    change_rate      NUMERIC(9,4)  NOT NULL,   -- %
    prev_close_date  DATE,                     -- 전일 종가의 날짜. 모르면 NULL
    market_status    VARCHAR(10)   NOT NULL,   -- 장중 | 장마감 | 개장전
    source           VARCHAR(20)   NOT NULL,   -- KIS | TWELVE_DATA
    collected_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_market_index_quote PRIMARY KEY (id),
    CONSTRAINT uk_market_index_quote UNIQUE (index_code, trade_date, slot)
);

-- 화면이 쓰는 "그 지수의 가장 최근 한 건" 조회
CREATE INDEX IF NOT EXISTS idx_market_index_quote_latest
    ON market_index_quote (index_code, trade_date DESC, slot DESC);

-- 검증: 테이블만 만들고 데이터는 수집 API로 채운다
SELECT COUNT(*) AS rows FROM market_index_quote;
