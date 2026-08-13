-- AF-102 금리 수집 (한국 ECOS)
-- ddl-auto: none 이므로 Neon에 직접 적용한다. 재실행 가능하게 IF NOT EXISTS로 쓴다.

CREATE TABLE IF NOT EXISTS market_rate (
    id           uuid         NOT NULL,
    rate_code    varchar(20)  NOT NULL,
    quote_date   date         NOT NULL,
    rate_value   numeric(9,4) NOT NULL,
    source       varchar(20)  NOT NULL,
    collected_at timestamp    NOT NULL,

    CONSTRAINT pk_market_rate PRIMARY KEY (id),
    CONSTRAINT uk_market_rate UNIQUE (rate_code, quote_date)
);

-- 최신값 조회와 구간 조회 양쪽에 듣는다
CREATE INDEX IF NOT EXISTS idx_market_rate_lookup
    ON market_rate (rate_code, quote_date DESC);

-- 적용 확인
SELECT COUNT(*) AS rows FROM market_rate;
