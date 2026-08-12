-- AF-100 과거 환율 시계열 (ECOS) — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-08-11-fx-rate-daily.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.

CREATE TABLE IF NOT EXISTS fx_rate_daily (
    id         UUID           NOT NULL,
    base_date  DATE           NOT NULL,
    currency   VARCHAR(10)    NOT NULL,
    rate_krw   NUMERIC(18, 6) NOT NULL,   -- 통화 1단위당 KRW. JPY 같은 100단위 고시는 수집기가 1단위로 정규화해 넣는다
    source     VARCHAR(20)    NOT NULL DEFAULT 'ECOS',
    created_at TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_fx_rate_daily PRIMARY KEY (id),
    CONSTRAINT uk_fx_rate_daily UNIQUE (base_date, currency)
);

-- 체결일 조회는 "그 날짜 이하의 가장 최근 고시" 한 건 — 주말·공휴일이 직전 영업일로 이어진다
CREATE INDEX IF NOT EXISTS idx_fx_rate_daily_lookup
    ON fx_rate_daily (currency, base_date DESC);

-- 검증: 테이블만 생성되고 데이터는 어드민 백필 API로 채운다
SELECT COUNT(*) AS rows FROM fx_rate_daily;
