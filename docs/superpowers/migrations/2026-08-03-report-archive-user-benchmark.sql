-- 2026-08-03 라이브 QA P0 #1 — 운영 Neon 1회성 (즉시 실행 가능, 배포 불필요)
-- R1 #32(report_archive)·#35(user_benchmark)가 init.sql에만 추가되고
-- 운영 마이그레이션 파일이 누락되어 Neon에 미적용 → 리포트 6종 + /api/benchmark-config 500.
-- 자립형·멱등·무해: 신규 테이블 생성만 하므로 기존 데이터에 영향 없음.

-- ── report_archive ─────────────────────────────────────────────
-- 기관급 리포트 아카이브 (R1 #32): as-of 고정된 본문 JSON을 기간 단위로 보관
CREATE TABLE IF NOT EXISTS report_archive (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL,
    report_type   VARCHAR(30)  NOT NULL,
    period_start  DATE         NOT NULL,
    period_end    DATE         NOT NULL,
    as_of_date    DATE         NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    warnings      JSONB        NOT NULL DEFAULT '[]',
    body          JSONB        NOT NULL,
    pdf           BYTEA,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_report_archive UNIQUE (user_id, report_type, period_start, period_end)
);

CREATE INDEX IF NOT EXISTS idx_report_archive_user
    ON report_archive (user_id, report_type, period_end DESC);

-- ── user_benchmark ─────────────────────────────────────────────
-- 사용자 벤치마크 설정 (R1 #35): R-01/R-02 "BM 대비"의 기준 지수
CREATE TABLE IF NOT EXISTS user_benchmark (
    user_id     UUID        NOT NULL,
    index_type  VARCHAR(20) NOT NULL,
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_benchmark PRIMARY KEY (user_id)
);

-- 검증
SELECT table_name FROM information_schema.tables
WHERE table_name IN ('report_archive', 'user_benchmark') ORDER BY table_name;
