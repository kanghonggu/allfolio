-- 2026-08-04 recon 4테이블 확정 생성 — 운영 Neon 1회성 (배포 불필요)
-- 증상: /api/recon/* 전부 500. 진단 결과 recon_run 미존재(컬럼 조회 빈 결과).
-- 자립형·멱등·무해: public 스키마에 명시적으로 CREATE IF NOT EXISTS.
-- 기존 3테이블(summary/detail/kd)이 있으면 no-op, 없거나 recon_run만 없으면 생성.
-- init.sql의 recon 정의와 100% 동일 (엔티티와 컬럼 일치 확인됨).

SET search_path TO public;

CREATE TABLE IF NOT EXISTS public.recon_run (
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
CREATE INDEX IF NOT EXISTS idx_recon_run_user ON public.recon_run (user_id, run_date DESC);

CREATE TABLE IF NOT EXISTS public.recon_result_summary (
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
CREATE INDEX IF NOT EXISTS idx_recon_summary_run ON public.recon_result_summary (run_id);

CREATE TABLE IF NOT EXISTS public.recon_result_detail (
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
CREATE INDEX IF NOT EXISTS idx_recon_detail_summary ON public.recon_result_detail (summary_id);

CREATE TABLE IF NOT EXISTS public.recon_kd (
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
CREATE INDEX IF NOT EXISTS idx_recon_kd_user ON public.recon_kd (user_id);

-- 검증: 4개 테이블 + recon_run 컬럼 10개가 나와야 정상
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_name LIKE 'recon%' ORDER BY table_name;

SELECT column_name FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'recon_run' ORDER BY ordinal_position;
