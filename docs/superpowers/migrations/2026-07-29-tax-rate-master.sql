-- R-03 원천징수 세율 마스터 (SCR-RPT-06) — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-07-29-tax-rate-master.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.

CREATE TABLE IF NOT EXISTS tax_rates (
    id              UUID          NOT NULL,
    country         VARCHAR(2)    NOT NULL,
    income_type     VARCHAR(20)   NOT NULL,
    rate            NUMERIC(6,3)  NOT NULL,
    effective_start DATE          NOT NULL,
    effective_end   DATE,
    updated_by      UUID,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_tax_rates PRIMARY KEY (id),
    CONSTRAINT uk_tax_rates_ver UNIQUE (country, income_type, effective_start)
);

-- (country, income_type)당 현행(open) 행은 최대 1개 — 동시 등록 시 open 중복 방어
CREATE UNIQUE INDEX IF NOT EXISTS uk_tax_rates_open
    ON tax_rates (country, income_type) WHERE effective_end IS NULL;

INSERT INTO tax_rates (id, country, income_type, rate, effective_start) VALUES
  (gen_random_uuid(), 'US', 'DIVIDEND', 15,     '2000-01-01'),
  (gen_random_uuid(), 'KR', 'DIVIDEND', 15.4,   '2000-01-01'),
  (gen_random_uuid(), 'KR', 'INTEREST', 15.4,   '2000-01-01'),
  (gen_random_uuid(), 'JP', 'DIVIDEND', 15.315, '2000-01-01')
ON CONFLICT (country, income_type, effective_start) DO NOTHING;

-- 검증: 시드 4행
SELECT country, income_type, rate, effective_start FROM tax_rates ORDER BY country, income_type;
