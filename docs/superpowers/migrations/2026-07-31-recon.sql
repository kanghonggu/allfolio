-- P2 #12 대사·검증 엔진 recon_ 4테이블 — 운영 Neon 1회성 (백엔드 배포 "전" 실행)
-- 자립형·멱등·무해: 신규 테이블 생성만 하므로 기존 데이터에 영향 없음.
-- 룰은 코드(Spring 빈)라 룰 테이블 없음 — rule_code 문자열로 식별 (v2 스펙).

CREATE TABLE IF NOT EXISTS recon_run (
    id              UUID         NOT NULL,
    user_id         UUID         NOT NULL,
    run_date        DATE         NOT NULL,
    run_type        VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    trigger_type    VARCHAR(20)  NOT NULL,
    internal_as_of  DATE,
    external_as_of  TIMESTAMP,
    started_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    finished_at     TIMESTAMP,
    CONSTRAINT pk_recon_run PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_recon_run_user ON recon_run (user_id, run_date DESC);

CREATE TABLE IF NOT EXISTS recon_result_summary (
    id              UUID         NOT NULL,
    run_id          UUID         NOT NULL,
    rule_code       VARCHAR(50)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    checked_cnt     INT          NOT NULL DEFAULT 0,
    diff_cnt        INT          NOT NULL DEFAULT 0,
    kd_absorbed_cnt INT          NOT NULL DEFAULT 0,
    error_msg       VARCHAR(500),
    elapsed_ms      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_recon_result_summary PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_recon_summary_run ON recon_result_summary (run_id);

CREATE TABLE IF NOT EXISTS recon_result_detail (
    id             UUID           NOT NULL,
    summary_id     UUID           NOT NULL,
    symbol         VARCHAR(50),
    field_name     VARCHAR(30),
    diff_type      VARCHAR(30)    NOT NULL,
    internal_value NUMERIC(30,10),
    external_value NUMERIC(30,10),
    diff_value     NUMERIC(30,10),
    extras         TEXT,
    kd_id          UUID,
    CONSTRAINT pk_recon_result_detail PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_recon_detail_summary ON recon_result_detail (summary_id);

CREATE TABLE IF NOT EXISTS recon_kd (
    id            UUID           NOT NULL,
    user_id       UUID           NOT NULL,
    kd_code       VARCHAR(50)    NOT NULL,
    target_symbol VARCHAR(50),
    target_field  VARCHAR(30),
    value_type    VARCHAR(10)    NOT NULL,
    allow_value   NUMERIC(30,10) NOT NULL,
    reason        VARCHAR(300)   NOT NULL,
    apld_strt_dt  DATE           NOT NULL,
    apld_end_dt   DATE           NOT NULL DEFAULT '9999-12-31',
    use_yn        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_recon_kd PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_recon_kd_user ON recon_kd (user_id);

-- 검증
SELECT table_name FROM information_schema.tables
WHERE table_name IN ('recon_run','recon_result_summary','recon_result_detail','recon_kd')
ORDER BY table_name;
