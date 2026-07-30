-- R-07 사용자 배제리스트 (SCR-RPT-11) — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-07-30-exclusion-list.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none). 신규 테이블이라 기존 백엔드엔 무해. 멱등. 시드 없음(사용자 데이터).

CREATE TABLE IF NOT EXISTS ua_exclusion_lists (
    id          UUID          NOT NULL,
    user_id     UUID          NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    category    VARCHAR(30)   NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_exclusion_lists PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ua_exclusion_lists_user ON ua_exclusion_lists (user_id);

CREATE TABLE IF NOT EXISTS ua_exclusion_items (
    id       UUID         NOT NULL,
    list_id  UUID         NOT NULL,
    symbol   VARCHAR(40)  NOT NULL,
    memo     VARCHAR(300),
    added_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_exclusion_items PRIMARY KEY (id),
    CONSTRAINT fk_ua_exclusion_items_list FOREIGN KEY (list_id)
        REFERENCES ua_exclusion_lists(id) ON DELETE CASCADE,
    CONSTRAINT uk_ua_exclusion_items UNIQUE (list_id, symbol)
);
CREATE INDEX IF NOT EXISTS idx_ua_exclusion_items_list ON ua_exclusion_items (list_id);

-- 검증
SELECT to_regclass('ua_exclusion_lists') AS lists, to_regclass('ua_exclusion_items') AS items;
