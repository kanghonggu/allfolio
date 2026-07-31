-- P3 #22+#25 마감 워크플로우 wf_ 5테이블 + 시드 — 운영 Neon 1회성 (백엔드 배포 "전" 실행)
-- 자립형·멱등·무해: 신규 테이블 생성 + ON CONFLICT DO NOTHING 시드.
-- 정의(단계·하위단계)는 데이터, 실행 액션은 코드 빈(action_ref 매칭) — v 스펙 2026-07-31-closing-workflow-design.md

CREATE TABLE IF NOT EXISTS wf_step (
    step_cd           VARCHAR(20)  NOT NULL,
    step_seq          INT          NOT NULL,
    step_name         VARCHAR(100) NOT NULL,
    step_group        VARCHAR(50),
    term_gb           VARCHAR(1)   NOT NULL,   -- D/M/Q
    cutoff_start      VARCHAR(5),
    cutoff_end        VARCHAR(5),
    essential_step_cd VARCHAR(20),             -- 선행 필수 단계 (soft ref)
    url               VARCHAR(200),
    holiday_except_yn BOOLEAN      NOT NULL DEFAULT TRUE,
    use_yn            BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_wf_step PRIMARY KEY (step_cd)
);

CREATE TABLE IF NOT EXISTS wf_sub_step (
    step_cd           VARCHAR(20)  NOT NULL,
    sub_step_cd       VARCHAR(20)  NOT NULL,
    sub_step_seq      INT          NOT NULL,
    sub_step_name     VARCHAR(100) NOT NULL,
    auto_manual       VARCHAR(1)   NOT NULL,   -- A/M
    closing_check_yn  BOOLEAN      NOT NULL DEFAULT TRUE,
    date_term         INT,                     -- M/Q: n번째(음수=역산), D: null
    date_gb           VARCHAR(1),              -- S(달력일)/B(영업일)
    action_type       VARCHAR(10)  NOT NULL,   -- CHAIN/POLL/MANUAL
    action_ref        VARCHAR(100),            -- WfAction 빈 ref (MANUAL이면 null)
    timeout_sec       INT          NOT NULL DEFAULT 300,
    poll_interval_sec INT          NOT NULL DEFAULT 10,
    use_yn            BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_wf_sub_step PRIMARY KEY (step_cd, sub_step_cd)
);

CREATE TABLE IF NOT EXISTS wf_job_log (
    id           UUID         NOT NULL,
    ymd          DATE         NOT NULL,
    step_cd      VARCHAR(20)  NOT NULL,
    sub_step_cd  VARCHAR(20)  NOT NULL,
    exec_seq     INT          NOT NULL,        -- 재작업 차수
    status       VARCHAR(10)  NOT NULL,        -- PENDING/RUNNING/SUCCESS/ERROR/PAUSED
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    auto_manual  VARCHAR(1)   NOT NULL,
    executor     VARCHAR(100) NOT NULL,        -- SYSTEM 또는 userId
    remark       VARCHAR(500),
    error_detail TEXT,
    CONSTRAINT pk_wf_job_log PRIMARY KEY (id),
    CONSTRAINT uq_wf_job_log UNIQUE (ymd, step_cd, sub_step_cd, exec_seq)
);
CREATE INDEX IF NOT EXISTS idx_wf_job_log_ymd ON wf_job_log (ymd);

CREATE TABLE IF NOT EXISTS wf_def_hist (
    id          UUID        NOT NULL,
    entity_type VARCHAR(10) NOT NULL,   -- STEP/SUB_STEP
    entity_key  VARCHAR(50) NOT NULL,
    crud        VARCHAR(1)  NOT NULL,   -- C/U/D
    snapshot    TEXT,
    changed_by  UUID,
    changed_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_wf_def_hist PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS wf_holiday (
    day     DATE         NOT NULL,
    country VARCHAR(2)   NOT NULL DEFAULT 'KR',
    name    VARCHAR(100),
    CONSTRAINT pk_wf_holiday PRIMARY KEY (day, country)
);

-- ── 시드: 단계 S010~S060 ──────────────────────────────────────
INSERT INTO wf_step (step_cd, step_seq, step_name, step_group, term_gb, cutoff_start, cutoff_end, essential_step_cd, url, holiday_except_yn) VALUES
('S010', 10, '브로커 동기화',   '수집',   'D', '00:05', '00:30', NULL,   '/unified/accounts/sync',  FALSE),
('S020', 20, '사전검증',        '검증',   'D', '00:30', '00:45', 'S010', '/unified/recon',          FALSE),
('S030', 30, '포지션·스냅샷',   '계산',   'D', '00:45', '01:00', 'S020', NULL,                      FALSE),
('S040', 40, '대사',            '검증',   'D', '01:00', '01:30', 'S030', '/unified/recon',          FALSE),
('S050', 50, '마감 확인',       '마감',   'D', '09:00', '18:00', 'S040', '/unified/admin/closing',  FALSE),
('S060', 60, '월마감(리포트)',  '마감',   'M', '22:00', '23:59', 'S050', '/unified/reports',        TRUE)
ON CONFLICT (step_cd) DO NOTHING;

-- ── 시드: 하위단계 (액션 ref는 코드 빈 — PR C에서 구현) ────────
INSERT INTO wf_sub_step (step_cd, sub_step_cd, sub_step_seq, sub_step_name, auto_manual, closing_check_yn, date_term, date_gb, action_type, action_ref) VALUES
('S010', 'S010-1', 1, '전 계좌 재동기화',            'A', TRUE, NULL, NULL, 'CHAIN',  'SYNC_ALL_ACCOUNTS'),
('S020', 'S020-1', 1, '검증 룰 실행(전 사용자)',     'A', TRUE, NULL, NULL, 'CHAIN',  'RECON_VALIDATION'),
('S030', 'S030-1', 1, 'NAV 스냅샷(전 사용자)',       'A', TRUE, NULL, NULL, 'CHAIN',  'NAV_SNAPSHOT'),
('S040', 'S040-1', 1, '포지션 대사(전 사용자)',      'A', TRUE, NULL, NULL, 'CHAIN',  'RECON_POSITION'),
('S050', 'S050-1', 1, '운영자 마감 확인',            'M', TRUE, NULL, NULL, 'MANUAL', NULL),
('S060', 'S060-1', 1, '월간 리포트 아카이브 생성',   'A', TRUE, -1,  'B',   'CHAIN',  'MONTHLY_REPORTS')
ON CONFLICT (step_cd, sub_step_cd) DO NOTHING;

-- ── 시드: 2026년 KR 공휴일 (주말은 계산에서 자동 제외 — 별도 등록 불필요) ──
-- ※ 설/추석은 음력 기준 확정일. 운영자가 wf_holiday에서 조정 가능(데이터).
INSERT INTO wf_holiday (day, country, name) VALUES
('2026-01-01', 'KR', '신정'),
('2026-02-16', 'KR', '설날 연휴'),
('2026-02-17', 'KR', '설날'),
('2026-02-18', 'KR', '설날 연휴'),
('2026-03-02', 'KR', '삼일절 대체공휴일'),
('2026-05-05', 'KR', '어린이날'),
('2026-05-25', 'KR', '부처님오신날 대체공휴일'),
('2026-08-17', 'KR', '광복절 대체공휴일'),
('2026-09-24', 'KR', '추석 연휴'),
('2026-09-25', 'KR', '추석'),
('2026-10-05', 'KR', '개천절 대체공휴일'),
('2026-10-09', 'KR', '한글날'),
('2026-12-25', 'KR', '성탄절')
ON CONFLICT (day, country) DO NOTHING;

-- 검증
SELECT (SELECT count(*) FROM wf_step) AS steps,
       (SELECT count(*) FROM wf_sub_step) AS sub_steps,
       (SELECT count(*) FROM wf_holiday) AS holidays;
