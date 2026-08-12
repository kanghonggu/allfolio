-- AF-99 하나은행 회차별 고시환율 — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-08-12-hana-fx-quote.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.

CREATE TABLE IF NOT EXISTS hana_fx_quote (
    id            UUID          NOT NULL,
    base_date     DATE          NOT NULL,   -- 하나은행이 준 기준일. 조회일자가 아니다
    round_no      INT           NOT NULL,   -- 고시 회차
    currency      VARCHAR(10)   NOT NULL,   -- ISO 3자리
    base_rate     NUMERIC(18,4) NOT NULL,   -- 매매기준율
    cash_buy      NUMERIC(18,4),            -- 현찰 사실 때
    cash_sell     NUMERIC(18,4),            -- 현찰 파실 때
    remit_send    NUMERIC(18,4),            -- 송금 보낼 때
    remit_receive NUMERIC(18,4),            -- 송금 받을 때
    collected_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_hana_fx_quote PRIMARY KEY (id),
    CONSTRAINT uk_hana_fx_quote UNIQUE (base_date, round_no, currency)
);

-- 평가 경로가 쓰는 "그 통화의 가장 최근 고시" 한 건 조회
CREATE INDEX IF NOT EXISTS idx_hana_fx_quote_latest
    ON hana_fx_quote (currency, base_date DESC, round_no DESC);

-- 검증: 테이블만 생성되고 데이터는 어드민 수집 API로 채운다
SELECT COUNT(*) AS rows FROM hana_fx_quote;
