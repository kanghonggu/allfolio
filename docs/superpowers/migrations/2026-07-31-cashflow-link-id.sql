-- R-06 환전/계좌간이체 Phase 1 — 운영 Neon 1회성 (백엔드 배포 "전" 실행)
-- 자립형·멱등·무해: cash_flow 테이블이 없으면 생성(init.sql 정의와 동일, link_id 포함),
-- 이미 있으면 link_id 컬럼만 추가. 어느 경우든 안전하게 동작.
--
-- ※ "relation cash_flow does not exist" 오류가 났다면 아래 진단부터:
--    SELECT table_schema, table_name FROM information_schema.tables WHERE table_name = 'cash_flow';
--   - 행이 나오면: 해당 스키마가 search_path에 없을 수 있음 → SET search_path TO <schema>; 후 재실행
--     (또는 이 스크립트의 cash_flow 를 <schema>.cash_flow 로 한정).
--   - 행이 없으면: 운영 DB에 아직 미생성 → 아래 CREATE 가 만들어 줌(정상).

CREATE TABLE IF NOT EXISTS cash_flow (
    id           UUID           PRIMARY KEY,
    user_id      UUID           NOT NULL,
    account_id   UUID,
    flow_date    DATE           NOT NULL,
    flow_type    VARCHAR(20)    NOT NULL,
    amount       NUMERIC(30,10) NOT NULL,
    currency     VARCHAR(10)    NOT NULL,
    amount_krw   NUMERIC(30,10) NOT NULL,
    memo         VARCHAR(500),
    link_id      UUID,
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cash_flow_user_date
    ON cash_flow (user_id, flow_date);

-- 테이블이 이미(link_id 없이) 존재하던 경우를 위한 컬럼 추가(멱등).
ALTER TABLE cash_flow ADD COLUMN IF NOT EXISTS link_id UUID;

-- 검증
SELECT column_name, data_type FROM information_schema.columns
WHERE table_name = 'cash_flow' ORDER BY ordinal_position;
