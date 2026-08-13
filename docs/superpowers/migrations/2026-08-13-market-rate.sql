-- AF-102 금리 수집 (한국 ECOS) — 운영 Neon 1회성 마이그레이션
-- 실행: Neon 콘솔 SQL 편집기에서 실행한다.
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등.

CREATE TABLE IF NOT EXISTS market_rate (
    id           UUID          NOT NULL,
    rate_code    VARCHAR(20)   NOT NULL,   -- 우리가 정한 canonical (KTB_3Y, CALL_ON 등)
    quote_date   DATE          NOT NULL,   -- ECOS가 준 기준일(TIME). 수집(조회)한 날짜가 아니다
    rate_value   NUMERIC(9,4)  NOT NULL,   -- 연 %. 마이너스 금리가 실재해 부호를 제한하지 않는다
    source       VARCHAR(20)   NOT NULL,   -- ECOS | FRED(예정). 재수집 시 값과 함께 덮인다
    collected_at TIMESTAMP     NOT NULL,   -- 앱이 항상 채운다 — DEFAULT를 두면 빠뜨렸을 때 조용히 틀린 값이 들어간다
    CONSTRAINT pk_market_rate PRIMARY KEY (id),
    -- (rate_code, quote_date) btree는 유일성뿐 아니라 조회도 겸한다: 구간 조회
    -- (rate_code = ? AND quote_date BETWEEN ? AND ?)와 최신값 조회
    -- (rate_code = ? ORDER BY quote_date DESC LIMIT 1) 둘 다 이 인덱스 하나로 충분하다 —
    -- Postgres는 btree를 역방향으로도 비용 없이 스캔한다. rate_code가 등치로 고정되는 한
    -- 별도 DESC 인덱스는 쓸 일이 없으니 다시 추가하지 않는다.
    CONSTRAINT uk_market_rate UNIQUE (rate_code, quote_date)
);

-- 검증: 테이블만 생성되고 데이터는 어드민 수집 API로 채운다
SELECT COUNT(*) AS rows FROM market_rate;
