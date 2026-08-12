-- ============================================================
-- Allfolio Schema — INSERT ONLY 설계
-- ============================================================

-- ── app_users / app_refresh_tokens ─────────────────────────────
-- Keycloak 제거 후 Allfolio 자체 인증에서 사용하는 사용자/refresh token 저장소
CREATE TABLE IF NOT EXISTS app_users (
    id            UUID          NOT NULL,
    email         VARCHAR(255)  NOT NULL,
    password_hash VARCHAR(255)  NOT NULL,
    display_name  VARCHAR(100),
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    role          VARCHAR(20)   NOT NULL DEFAULT 'USER',
    CONSTRAINT pk_app_users PRIMARY KEY (id),
    CONSTRAINT uk_app_users_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS app_refresh_tokens (
    id           UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    token_hash   VARCHAR(255) NOT NULL,
    expires_at   TIMESTAMP    NOT NULL,
    revoked_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_app_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT fk_app_refresh_tokens_user FOREIGN KEY (user_id)
        REFERENCES app_users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_app_refresh_tokens_user
    ON app_refresh_tokens (user_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_refresh_tokens_hash
    ON app_refresh_tokens (token_hash);

-- ── portfolios ────────────────────────────────────────────────
-- 사용자별 포트폴리오 소유권 원장. trade_raw 등 기존 portfolio_id 사용 테이블과의 FK는 앱 레벨에서 관리한다.
CREATE TABLE IF NOT EXISTS portfolios (
    id             UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    name           VARCHAR(255) NOT NULL,
    base_currency  VARCHAR(10)  NOT NULL DEFAULT 'KRW',
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMP,
    CONSTRAINT pk_portfolios PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_portfolios_user_active
    ON portfolios (user_id)
    WHERE deleted_at IS NULL;

-- ── trade_raw ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS trade_raw (
    id               UUID        NOT NULL,
    portfolio_id     UUID        NOT NULL,
    asset_id         UUID        NOT NULL,
    trade_type       VARCHAR(10) NOT NULL,
    quantity         NUMERIC(30, 10) NOT NULL,
    price            NUMERIC(30, 10) NOT NULL,
    fee              NUMERIC(30, 10) NOT NULL,
    trade_currency   VARCHAR(10) NOT NULL,
    executed_at      TIMESTAMP   NOT NULL,
    created_at       TIMESTAMP   NOT NULL,
    CONSTRAINT pk_trade_raw PRIMARY KEY (id)
);

-- 핵심 쿼리 패턴: 포트폴리오별 시간순 Trade 조회 (Snapshot 재계산)
CREATE INDEX IF NOT EXISTS idx_trade_raw_portfolio_executed
    ON trade_raw (portfolio_id, executed_at ASC);

-- 자산별 포지션 계산용
CREATE INDEX IF NOT EXISTS idx_trade_raw_portfolio_asset
    ON trade_raw (portfolio_id, asset_id);

-- ── position_daily ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS position_daily (
    tenant_id       UUID           NOT NULL,
    portfolio_id    UUID           NOT NULL,
    asset_id        UUID           NOT NULL,
    date            DATE           NOT NULL,
    quantity        NUMERIC(30, 10) NOT NULL,
    average_cost    NUMERIC(30, 10) NOT NULL,
    realized_pnl    NUMERIC(30, 10) NOT NULL,
    unrealized_pnl  NUMERIC(30, 10) NOT NULL,
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_position_daily PRIMARY KEY (tenant_id, portfolio_id, asset_id, date)
);

CREATE INDEX IF NOT EXISTS idx_position_daily_portfolio_date
    ON position_daily (portfolio_id, date DESC);

-- ── performance_daily ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS performance_daily (
    tenant_id            UUID           NOT NULL,
    portfolio_id         UUID           NOT NULL,
    date                 DATE           NOT NULL,
    nav                  NUMERIC(30, 10) NOT NULL,
    daily_return         NUMERIC(30, 10) NOT NULL,
    cumulative_return    NUMERIC(30, 10) NOT NULL,
    benchmark_return     NUMERIC(30, 10),
    alpha                NUMERIC(30, 10),
    created_at           TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_performance_daily PRIMARY KEY (tenant_id, portfolio_id, date)
);

CREATE INDEX IF NOT EXISTS idx_performance_daily_portfolio_date
    ON performance_daily (portfolio_id, date DESC);

-- ── risk_daily ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS risk_daily (
    tenant_id            UUID           NOT NULL,
    portfolio_id         UUID           NOT NULL,
    date                 DATE           NOT NULL,
    volatility           NUMERIC(30, 10) NOT NULL,
    annualized_volatility NUMERIC(30, 10) NOT NULL,
    var95                NUMERIC(30, 10) NOT NULL,
    max_drawdown         NUMERIC(30, 10) NOT NULL,
    created_at           TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_risk_daily PRIMARY KEY (tenant_id, portfolio_id, date)
);

CREATE INDEX IF NOT EXISTS idx_risk_daily_portfolio_date
    ON risk_daily (portfolio_id, date DESC);

-- ── binance_sync_cursor ────────────────────────────────────────
-- Binance 거래 중복 방지용 커서 (portfolio_id, symbol) → last_trade_id
CREATE TABLE IF NOT EXISTS binance_sync_cursor (
    portfolio_id UUID        NOT NULL,
    symbol       VARCHAR(20) NOT NULL,
    last_trade_id BIGINT     NOT NULL DEFAULT 0,
    synced_count  BIGINT     NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_binance_sync_cursor PRIMARY KEY (portfolio_id, symbol)
);

-- ── outbox_event ──────────────────────────────────────────────
-- Outbox 패턴: Trade 트랜잭션과 동일한 TX에서 INSERT
-- status 전이: PENDING → PROCESSED (성공) | FAILED (재시도 중) | DEAD (MAX_RETRIES 초과)
CREATE TABLE IF NOT EXISTS outbox_event (
    id             UUID         NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count    INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    processed_at   TIMESTAMP,
    error_message  VARCHAR(500),
    CONSTRAINT pk_outbox_event PRIMARY KEY (id)
);

-- 기존 테이블에 retry_count 추가 (운영 환경 마이그레이션)
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;

-- Processor 폴링 핵심 인덱스: PENDING/FAILED 이벤트 빠른 조회
-- retry_count 포함 → findRetryableForUpdate() 인덱스 활용
CREATE INDEX IF NOT EXISTS idx_outbox_status_created
    ON outbox_event (status, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_outbox_retryable
    ON outbox_event (status, retry_count, created_at ASC)
    WHERE status IN ('PENDING', 'FAILED');

-- ── trade_raw dedup 컬럼 ──────────────────────────────────────
ALTER TABLE trade_raw ADD COLUMN IF NOT EXISTS broker_type       VARCHAR(20);
ALTER TABLE trade_raw ADD COLUMN IF NOT EXISTS external_trade_id VARCHAR(100);

-- 브로커 체결 내역 중복 방지 (partial unique index: NULL 제외)
CREATE UNIQUE INDEX IF NOT EXISTS idx_trade_raw_broker_dedup
    ON trade_raw (broker_type, external_trade_id)
    WHERE broker_type IS NOT NULL AND external_trade_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_trade_raw_asset
    ON trade_raw (asset_id);

-- ── broker_sync_state ─────────────────────────────────────────
-- 멀티 브로커 증분 동기화 커서 (BinanceSyncCursor 대체)
CREATE TABLE IF NOT EXISTS broker_sync_state (
    portfolio_id   UUID         NOT NULL,
    broker_type    VARCHAR(20)  NOT NULL,
    account_id     VARCHAR(100) NOT NULL,
    cursor_value   VARCHAR(200) NOT NULL DEFAULT '',
    synced_count   BIGINT       NOT NULL DEFAULT 0,
    last_synced_at TIMESTAMP,
    CONSTRAINT pk_broker_sync_state PRIMARY KEY (portfolio_id, broker_type, account_id)
);

-- ── broker_auth ───────────────────────────────────────────────
-- OAuth2 토큰 저장 (Toss, Samsung 등 OAuth2 브로커)
CREATE TABLE IF NOT EXISTS broker_auth (
    id                       UUID        NOT NULL,
    user_id                  UUID        NOT NULL,
    broker_type              VARCHAR(20) NOT NULL,
    access_token             TEXT        NOT NULL,
    refresh_token            TEXT,
    token_type               VARCHAR(20) DEFAULT 'Bearer',
    access_token_expires_at  TIMESTAMP   NOT NULL,
    refresh_token_expires_at TIMESTAMP,
    created_at               TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_broker_auth PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_broker_auth_user_broker
    ON broker_auth (user_id, broker_type);

-- ── ua_goals ──────────────────────────────────────────────────
-- 목표 달성 트래커: 사용자의 재무 목표(은퇴, 내 집 마련 등)
CREATE TABLE IF NOT EXISTS ua_goals (
    id             UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    name           VARCHAR(100) NOT NULL,
    description    VARCHAR(500),
    target_amount  NUMERIC(30, 10) NOT NULL,
    target_date    DATE,
    category       VARCHAR(30)  NOT NULL DEFAULT 'OTHER',
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_goals PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_ua_goals_user
    ON ua_goals (user_id);

-- ── ua_exclusion_lists / ua_exclusion_items : R-07 사용자 배제리스트 ──────
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

-- ── kafka_processed_event ──────────────────────────────────────
-- Kafka Consumer 멱등성 마커
-- SELECT 없이 INSERT PK 충돌로 중복 감지 (race condition 없음)
-- event_id = outboxEventId (UUID string)
-- TTL: outbox.trade 토픽 retention(24h) + 버퍼 → 48h 이후 삭제 가능
CREATE TABLE IF NOT EXISTS kafka_processed_event (
    event_id     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_kafka_processed_event PRIMARY KEY (event_id)
);

-- 클린업용 인덱스: processed_at 기준 오래된 레코드 일괄 삭제
CREATE INDEX IF NOT EXISTS idx_kafka_processed_event_at
    ON kafka_processed_event (processed_at ASC);

-- ── ua_accounts ───────────────────────────────────────────────
-- 자산 수집 단위: 거래소 계좌 / 지갑 / CSV / 수동
-- User → Account → Asset 계층 구조의 중간 노드
CREATE TABLE IF NOT EXISTS ua_accounts (
    id             UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    provider       VARCHAR(20) NOT NULL,  -- BINANCE / STOCK / WALLET / CSV / MANUAL
    account_type   VARCHAR(20) NOT NULL,  -- EXCHANGE / STOCK / WALLET / BANK / MANUAL
    account_name   VARCHAR(100) NOT NULL,
    external_id    VARCHAR(100),          -- 거래소 계좌 ID 등
    currency       VARCHAR(10) NOT NULL DEFAULT 'USD',
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / SYNCING / ERROR / INACTIVE
    last_synced_at TIMESTAMP,
    created_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    -- API 계좌 자격증명 (운영 시 암호화 필수)
    api_key        VARCHAR(2048),
    api_secret     VARCHAR(2048),
    -- 지갑 계좌
    wallet_address VARCHAR(200),
    chain          VARCHAR(20),
    CONSTRAINT pk_ua_accounts PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_ua_accounts_user
    ON ua_accounts (user_id);

ALTER TABLE IF EXISTS ua_accounts
    ALTER COLUMN api_key TYPE VARCHAR(2048),
    ALTER COLUMN api_secret TYPE VARCHAR(2048);

-- ── recon_* : 대사·검증 엔진 (P2 #12) ─────────────────────────
-- 룰은 코드(Spring 빈)라 룰 테이블 없음. rule_code 문자열로 식별.
CREATE TABLE IF NOT EXISTS recon_run (
    id              UUID         NOT NULL,
    user_id         UUID         NOT NULL,
    run_date        DATE         NOT NULL,
    run_type        VARCHAR(20)  NOT NULL,  -- VALIDATION / RECONCILIATION / ALL
    status          VARCHAR(20)  NOT NULL,  -- RUNNING / COMPLETED / FAILED
    trigger_type    VARCHAR(20)  NOT NULL,  -- MANUAL / SCHEDULED
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
    status          VARCHAR(20)  NOT NULL,  -- PASSED / DIFF_FOUND / FAILED
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
    diff_type      VARCHAR(30)    NOT NULL,  -- VALUE_MISMATCH / MISSING_INTERNAL / MISSING_EXTERNAL / RULE_VIOLATION
    internal_value NUMERIC(30,10),
    external_value NUMERIC(30,10),
    diff_value     NUMERIC(30,10),
    extras         TEXT,                     -- JSON 문자열
    kd_id          UUID,                     -- 흡수한 KD (숨김 아님)
    CONSTRAINT pk_recon_result_detail PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_recon_detail_summary ON recon_result_detail (summary_id);

-- Known Difference — USER-scoped, 수정=버저닝(기존 행 apld_end_dt 마감 + 신규 INSERT)
CREATE TABLE IF NOT EXISTS recon_kd (
    id            UUID           NOT NULL,
    user_id       UUID           NOT NULL,
    kd_code       VARCHAR(50)    NOT NULL,
    target_symbol VARCHAR(50),              -- null = 와일드카드
    target_field  VARCHAR(30),              -- null = 와일드카드
    value_type    VARCHAR(10)    NOT NULL,  -- ABS / RATIO
    allow_value   NUMERIC(30,10) NOT NULL,
    reason        VARCHAR(300)   NOT NULL,
    apld_strt_dt  DATE           NOT NULL,
    apld_end_dt   DATE           NOT NULL DEFAULT '9999-12-31',
    use_yn        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_recon_kd PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_recon_kd_user ON recon_kd (user_id);

-- ── wf_* : 일일마감 워크플로우 (P3 #22) ───────────────────────
-- 정의=데이터, 실행 액션=코드 빈(action_ref). 시드 포함.
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


-- ── ua_sync_logs ──────────────────────────────────────────────
-- 계좌 동기화 실행 이력 (AF-9). trigger는 SQL 예약어라 trigger_type.
CREATE TABLE IF NOT EXISTS ua_sync_logs (
    id            UUID         NOT NULL,
    account_id    UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    trigger_type  VARCHAR(20)  NOT NULL,   -- SCHEDULED / MANUAL / AUTO(쓰기 작업 자동 트리거)
    status        VARCHAR(20)  NOT NULL,   -- SUCCESS / ERROR
    synced_count  INT          NOT NULL DEFAULT 0,
    error_message VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_sync_logs PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_ua_sync_logs_account
    ON ua_sync_logs (account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ua_sync_logs_user
    ON ua_sync_logs (user_id, created_at DESC);

-- ── ua_assets ─────────────────────────────────────────────────
-- 개별 자산: 반드시 ua_accounts 소속
-- sourceType이 EXCHANGE_API/WALLET이면 sync 시 전체 교체됨 (full refresh)
CREATE TABLE IF NOT EXISTS ua_assets (
    id               UUID        NOT NULL,
    user_id          UUID        NOT NULL,
    account_id       UUID        NOT NULL,
    category         VARCHAR(20) NOT NULL,  -- FINANCIAL / MANUAL
    type             VARCHAR(20) NOT NULL,  -- STOCK / CRYPTO / REAL_ESTATE / VEHICLE / GOLD / CASH / ETC
    source_type      VARCHAR(20) NOT NULL,  -- EXCHANGE_API / WALLET / STOCK_API / CSV / MANUAL
    name             VARCHAR(200) NOT NULL,
    symbol           VARCHAR(200),
    quantity         NUMERIC(30, 10) NOT NULL,
    purchase_price   NUMERIC(30, 10) NOT NULL DEFAULT 0,
    current_value    NUMERIC(30, 10) NOT NULL,
    currency         VARCHAR(10) NOT NULL,
    valuation_method VARCHAR(20) NOT NULL,  -- MARKET_PRICE / BALANCE / USER_INPUT
    confidence_level VARCHAR(10) NOT NULL,  -- HIGH / MEDIUM / LOW
    sub_type         VARCHAR(30),                       -- 세부 유형 (OWN/JEONSE/MONTHLY/PRESALE/LEASE/RENTAL 등)
    loan_amount      NUMERIC(30, 10),                   -- 대출 잔액 (담보대출, 전세자금대출 등)
    area_pyeong      NUMERIC(10, 2),                    -- 부동산 면적 (평)
    last_updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMP   NOT NULL DEFAULT NOW(),
    memo             VARCHAR(500),
    CONSTRAINT pk_ua_assets PRIMARY KEY (id),
    CONSTRAINT fk_ua_assets_account FOREIGN KEY (account_id)
        REFERENCES ua_accounts(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ua_assets_user
    ON ua_assets (user_id);

CREATE INDEX IF NOT EXISTS idx_ua_assets_account
    ON ua_assets (account_id);

CREATE INDEX IF NOT EXISTS idx_ua_assets_type
    ON ua_assets (user_id, type);

-- ── ua_stock_trades ──────────────────────────────────────────────
-- 증권 계좌의 거래내역 로그 (매수/매도/신용/미수/배당)
CREATE TABLE IF NOT EXISTS ua_stock_trades (
    id           UUID        NOT NULL,
    account_id   UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    trade_type   VARCHAR(20) NOT NULL,  -- BUY/SELL/CREDIT_BUY/CREDIT_SELL/MARGIN/DIVIDEND
    stock_name   VARCHAR(200) NOT NULL,
    symbol       VARCHAR(20),
    quantity     NUMERIC(20, 4) NOT NULL DEFAULT 0,
    price        NUMERIC(20, 4) NOT NULL DEFAULT 0,
    total_amount NUMERIC(20, 4) NOT NULL DEFAULT 0,
    fee          NUMERIC(20, 4) NOT NULL DEFAULT 0,
    tax          NUMERIC(20, 4) NOT NULL DEFAULT 0,
    traded_at    DATE        NOT NULL,
    memo         VARCHAR(500),
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_stock_trades PRIMARY KEY (id),
    CONSTRAINT fk_ua_stock_trades_account FOREIGN KEY (account_id)
        REFERENCES ua_accounts(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ua_stock_trades_account
    ON ua_stock_trades (account_id, traded_at DESC);

-- 유저 단위 배당/거래 조회용 (DividendReportService, AiConsultantService 등)
CREATE INDEX IF NOT EXISTS idx_ua_stock_trades_user_dividend
    ON ua_stock_trades (user_id, trade_type, traded_at DESC);

-- ── market_price_tick ────────────────────────────────────────────
-- WebSocket 실시간 시세 틱 데이터
-- BinanceWsAdapter / KisWsAdapter → MarketPriceBatchWriter → 배치 INSERT
-- 분석/백테스트용 히스토리 보존 (장기 보존 정책은 별도 파티셔닝으로 처리 예정)
CREATE TABLE IF NOT EXISTS market_price_tick (
    id             BIGSERIAL       NOT NULL,
    exchange       VARCHAR(20)     NOT NULL,  -- BINANCE / KIS
    symbol         VARCHAR(20)     NOT NULL,  -- BTCUSDT / 005930
    price          NUMERIC(30, 10) NOT NULL,
    volume         NUMERIC(30, 10) NOT NULL DEFAULT 0,
    tick_timestamp TIMESTAMP       NOT NULL,  -- 거래소 발생 시각
    received_at    TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_market_price_tick PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_mpt_symbol_ts
    ON market_price_tick (symbol, tick_timestamp DESC);

CREATE INDEX IF NOT EXISTS idx_mpt_exchange_ts
    ON market_price_tick (exchange, tick_timestamp DESC);

-- ── FILLFACTOR 최적화 ──────────────────────────────────────────
-- INSERT ONLY 테이블은 UPDATE가 없으므로 fillfactor=100 (기본값)
-- 인덱스도 fillfactor=100으로 여유 공간 제거 → INSERT 성능 향상
CREATE INDEX IF NOT EXISTS idx_trade_raw_portfolio_executed_perf
    ON trade_raw (portfolio_id, executed_at ASC)
    WITH (fillfactor = 100);

-- ── Bloomberg Dashboard: ua_assets 컬럼 추가 ─────────────────────
ALTER TABLE ua_assets ADD COLUMN IF NOT EXISTS maturity_date   DATE;
ALTER TABLE ua_assets ADD COLUMN IF NOT EXISTS liquidity_type  VARCHAR(20) NOT NULL DEFAULT 'LIQUID';
ALTER TABLE ua_assets ADD COLUMN IF NOT EXISTS area_pyeong     NUMERIC(10, 2);

-- 기존 비유동 자산 백필
UPDATE ua_assets SET liquidity_type = 'ILLIQUID'
WHERE type IN ('REAL_ESTATE', 'JEONSE', 'VEHICLE');

-- ── benchmark_daily ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS benchmark_daily (
    index_type   VARCHAR(20)     NOT NULL,  -- KOSPI / BTC
    date         DATE            NOT NULL,
    close_value  NUMERIC(30, 10) NOT NULL,
    created_at   TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_benchmark_daily PRIMARY KEY (index_type, date)
);

-- ── kr_stocks (KRX 전종목 검색용) ───────────────────────────────
CREATE TABLE IF NOT EXISTS kr_stocks (
    symbol  VARCHAR(20)  NOT NULL,
    name    VARCHAR(200) NOT NULL,
    market  VARCHAR(20)  NOT NULL,
    CONSTRAINT pk_kr_stocks PRIMARY KEY (symbol)
);
CREATE INDEX IF NOT EXISTS idx_kr_stocks_name ON kr_stocks USING gin(to_tsvector('simple', name));

INSERT INTO kr_stocks (symbol, name, market) VALUES
-- ── KOSPI ───────────────────────────────────────────────────────
('005930','삼성전자','KOSPI'),('000660','SK하이닉스','KOSPI'),('373220','LG에너지솔루션','KOSPI'),
('207940','삼성바이오로직스','KOSPI'),('005380','현대차','KOSPI'),('000270','기아','KOSPI'),
('005490','POSCO홀딩스','KOSPI'),('051910','LG화학','KOSPI'),('006400','삼성SDI','KOSPI'),
('105560','KB금융','KOSPI'),('055550','신한지주','KOSPI'),('086790','하나금융지주','KOSPI'),
('028260','삼성물산','KOSPI'),('012330','현대모비스','KOSPI'),('066570','LG전자','KOSPI'),
('068270','셀트리온','KOSPI'),('096770','SK이노베이션','KOSPI'),('017670','SK텔레콤','KOSPI'),
('033780','KT&G','KOSPI'),('010130','고려아연','KOSPI'),('009150','삼성전기','KOSPI'),
('034730','SK','KOSPI'),('030200','KT','KOSPI'),('034020','두산에너빌리티','KOSPI'),
('267250','HD현대','KOSPI'),('015760','한국전력','KOSPI'),('012450','한화에어로스페이스','KOSPI'),
('032830','삼성생명','KOSPI'),('316140','우리금융지주','KOSPI'),('035720','카카오','KOSPI'),
('035420','NAVER','KOSPI'),('329180','현대중공업','KOSPI'),('259960','크래프톤','KOSPI'),
('047810','한국항공우주','KOSPI'),('090430','아모레퍼시픽','KOSPI'),('097950','CJ제일제당','KOSPI'),
('271560','오리온','KOSPI'),('086280','현대글로비스','KOSPI'),('006280','녹십자','KOSPI'),
('009830','한화솔루션','KOSPI'),('000100','유한양행','KOSPI'),('004020','현대제철','KOSPI'),
('000720','현대건설','KOSPI'),('036460','한국가스공사','KOSPI'),('003490','대한항공','KOSPI'),
('000810','삼성화재','KOSPI'),('010950','S-Oil','KOSPI'),('011200','HMM','KOSPI'),
('003550','LG','KOSPI'),('018260','삼성SDS','KOSPI'),('023530','롯데쇼핑','KOSPI'),
('004370','농심','KOSPI'),('002790','아모레G','KOSPI'),('078930','GS','KOSPI'),
('001040','CJ','KOSPI'),('001450','현대해상','KOSPI'),('088350','한화생명','KOSPI'),
('000150','두산','KOSPI'),('010120','LS ELECTRIC','KOSPI'),('016360','삼성증권','KOSPI'),
('071050','한국금융지주','KOSPI'),('009540','한국조선해양','KOSPI'),('003380','하이트진로','KOSPI'),
('307950','현대오토에버','KOSPI'),('352820','하이브','KOSPI'),('036570','엔씨소프트','KOSPI'),
('251270','넷마블','KOSPI'),('011170','롯데케미칼','KOSPI'),('008770','호텔신라','KOSPI'),
('010060','OCI','KOSPI'),('005180','빙그레','KOSPI'),('007070','GS리테일','KOSPI'),
('000240','한국타이어앤테크놀로지','KOSPI'),('008930','한미사이언스','KOSPI'),('002380','KCC','KOSPI'),
('006800','미래에셋증권','KOSPI'),('030000','제일기획','KOSPI'),('011780','금호석유화학','KOSPI'),
('008560','메리츠증권','KOSPI'),('009200','무림페이퍼','KOSPI'),('001300','제일기획','KOSPI'),
('002267','HD현대일렉트릭','KOSPI'),('004490','세방전지','KOSPI'),('042670','HD현대인프라코어','KOSPI'),
('000040','KYK','KOSPI'),('005870','휠라홀딩스','KOSPI'),('001120','LX홀딩스','KOSPI'),
('004000','롯데정밀화학','KOSPI'),('001800','오리온홀딩스','KOSPI'),('006360','GS건설','KOSPI'),
('033600','LT삼보','KOSPI'),('009970','영원무역홀딩스','KOSPI'),('082640','동양생명','KOSPI'),
('071970','STX중공업','KOSPI'),('012630','HDC','KOSPI'),('003240','태광산업','KOSPI'),
('007340','DN오토모티브','KOSPI'),('000670','영풍','KOSPI'),('005440','현대그린푸드','KOSPI'),
('006120','SK디스커버리','KOSPI'),('017800','현대엘리베이','KOSPI'),('010140','삼성중공업','KOSPI'),
('010780','아이에스동서','KOSPI'),('000640','동아쏘시오홀딩스','KOSPI'),('002960','한국쉘석유','KOSPI'),
('039020','이건홀딩스','KOSPI'),('097230','한진','KOSPI'),('003600','SK케미칼','KOSPI'),
('082740','HSD엔진','KOSPI'),('001070','대우부품','KOSPI'),('020150','롯데에너지머티리얼즈','KOSPI'),
('010830','한화손해보험','KOSPI'),('003830','대한화섬','KOSPI'),('009220','현대종합금속','KOSPI'),
-- ── KOSDAQ ──────────────────────────────────────────────────────
('028300','HLB','KOSDAQ'),('086520','에코프로','KOSDAQ'),('247540','에코프로비엠','KOSDAQ'),
('022100','포스코DX','KOSDAQ'),('277810','레인보우로보틱스','KOSDAQ'),('196170','알테오젠','KOSDAQ'),
('141080','리가켐바이오','KOSDAQ'),('214150','클래시스','KOSDAQ'),('140860','파크시스템스','KOSDAQ'),
('293490','카카오게임즈','KOSDAQ'),('263750','펄어비스','KOSDAQ'),('066970','엘앤에프','KOSDAQ'),
('091990','셀트리온헬스케어','KOSDAQ'),('145020','휴젤','KOSDAQ'),('290650','엔씨소프트','KOSDAQ'),
('054930','유에스티','KOSDAQ'),('241590','화승엔터프라이즈','KOSDAQ'),('108860','셀바스AI','KOSDAQ'),
('122870','와이지엔터테인먼트','KOSDAQ'),('180640','한진칼','KOSDAQ'),('357780','솔브레인','KOSDAQ'),
('039030','이오테크닉스','KOSDAQ'),('043360','비에이치','KOSDAQ'),('030530','원익IPS','KOSDAQ'),
('226440','오스코텍','KOSDAQ'),('112040','위메이드','KOSDAQ'),('035900','JYP엔터테인먼트','KOSDAQ'),
('041510','에스엠','KOSDAQ'),('352820','하이브','KOSDAQ'),('067160','수젠텍','KOSDAQ'),
('218410','RFHIC','KOSDAQ'),('236200','수산아이앤티','KOSDAQ'),('187450','나노엑스','KOSDAQ'),
('950140','잉글우드랩','KOSDAQ'),('268600','셀리버리','KOSDAQ'),('950170','JTC','KOSDAQ'),
('148780','비씨월드제약','KOSDAQ'),('073640','테라젠이텍스','KOSDAQ'),('011000','에이피티씨','KOSDAQ'),
('045970','코아스','KOSDAQ'),('090460','비엔지티','KOSDAQ'),('328130','루닛','KOSDAQ'),
('950200','파나진','KOSDAQ'),('140410','메지온','KOSDAQ'),('237350','이엑스티','KOSDAQ'),
('089790','토박스코리아','KOSDAQ'),('054450','텔레칩스','KOSDAQ'),('191420','테고사이언스','KOSDAQ'),
('200130','콜마비앤에이치','KOSDAQ'),('219130','타임폴리오자산운용','KOSDAQ'),
-- ── ETF (KODEX) ──────────────────────────────────────────────────
('069500','KODEX 200','ETF'),('114800','KODEX 인버스','ETF'),('122630','KODEX 레버리지','ETF'),
('229200','KODEX 코스닥150','ETF'),('252670','KODEX 200선물인버스2X','ETF'),
('091160','KODEX 반도체','ETF'),('305720','KODEX 2차전지산업','ETF'),
('379800','KODEX 미국S&P500','ETF'),('379810','KODEX 미국나스닥100','ETF'),
('148070','KODEX 국고채10년','ETF'),('132030','KODEX 골드선물(H)','ETF'),
('271050','KODEX WTI원유선물인버스(H)','ETF'),('261220','KODEX WTI원유선물(H)','ETF'),
('396500','KODEX AI반도체핵심장비','ETF'),('278540','KODEX MSCI Korea TR','ETF'),
('292150','KODEX 200IT','ETF'),('438330','KODEX 미국빅테크10','ETF'),
('453810','KODEX 미국AI테크TOP10','ETF'),('385720','KODEX 미국달러선물','ETF'),
('411060','KODEX 미국달러선물인버스','ETF'),('287310','KODEX 코스닥150레버리지','ETF'),
('233740','KODEX 코스닥150선물인버스','ETF'),('315960','KODEX WTI원유선물(H) TR','ETF'),
('261270','KODEX 미국채울트라30년선물(H)','ETF'),('453040','KODEX 인도Nifty50','ETF'),
('143460','KODEX 국고채3년','ETF'),('152100','KODEX 단기채권','ETF'),
('176950','KODEX 단기채권PLUS','ETF'),('385590','KODEX CD금리액티브(합성)','ETF'),
('446720','KODEX 한국은행기준금리액티브','ETF'),('130680','KODEX 바이오','ETF'),
-- ── ETF (TIGER) ──────────────────────────────────────────────────
('102110','TIGER 200','ETF'),('360750','TIGER 미국S&P500','ETF'),
('133690','TIGER 미국나스닥100','ETF'),('168580','TIGER 차이나CSI300','ETF'),
('091230','TIGER 반도체','ETF'),('381180','TIGER Fn반도체TOP10','ETF'),
('305540','TIGER 2차전지테마','ETF'),('381170','TIGER 미국테크TOP10INDXX','ETF'),
('371460','TIGER 글로벌리튬&2차전지SOLACTIVE','ETF'),('143850','TIGER 원유선물Enhanced(H)','ETF'),
('139230','TIGER 국채3년','ETF'),('148070','TIGER 국고채10년','ETF'),
('182480','TIGER 단기통안채','ETF'),('429400','TIGER 미국달러단기채권액티브','ETF'),
('441680','TIGER 미국나스닥100커버드콜(합성)','ETF'),('448290','TIGER 미국빅테크10','ETF'),
('458730','TIGER 미국AI빅테스트TOP10','ETF'),('364980','TIGER 차이나항셍테크','ETF'),
('195930','TIGER 해외리츠부동산인프라','ETF'),('251350','TIGER 글로벌인프라MLP(합성H)','ETF'),
('494860','TIGER 미국AI반도체핵심기업','ETF'),('453850','TIGER 인도니프티50','ETF'),
-- ── ETF (HANARO / SOL / ACE / KINDEX / ARIRANG) ─────────────────
('293180','HANARO 200','ETF'),('395270','HANARO K-반도체','ETF'),
('426410','HANARO 미국빅테크TOP10INDXX','ETF'),('427710','HANARO 글로벌반도체TOP10INDXX','ETF'),
('433500','SOL 미국S&P500','ETF'),('436450','SOL 미국나스닥100','ETF'),
('449170','SOL 미국배당다우존스','ETF'),('476490','SOL 미국AI소프트웨어','ETF'),
('360200','ACE 미국S&P500','ETF'),('367380','ACE 미국나스닥100','ETF'),
('411060','ACE KRX금현물','ETF'),('449060','ACE 미국빅테크TOP7Plus','ETF'),
('476600','ACE 미국AI반도체나스닥','ETF'),('091220','KINDEX 200','ETF'),
('261240','ARIRANG 미국S&P500(H)','ETF'),('195970','ARIRANG 고배당주','ETF'),
('412580','TIMEFOLIO 미국나스닥100액티브','ETF'),('494800','HANARO 미국AI빅테크10','ETF'),
('486290','KODEX 미국S&P500TR','ETF'),('486300','TIGER 미국S&P500TR','ETF')
ON CONFLICT (symbol) DO NOTHING;

-- ── tax_rates : 원천징수 세율 마스터 (국가×유형×유효기간 버저닝) ─────────
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

-- ── exclusion_presets : ADMIN 큐레이션 배제 프리셋 (버저닝 없음, symbol UNIQUE) ──
CREATE TABLE IF NOT EXISTS exclusion_presets (
    id          UUID         NOT NULL,
    symbol      VARCHAR(40)  NOT NULL,
    list_name   VARCHAR(100) NOT NULL,
    reason      VARCHAR(200) NOT NULL,
    updated_by  UUID,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_exclusion_presets PRIMARY KEY (id),
    CONSTRAINT uk_exclusion_presets_symbol UNIQUE (symbol)
);
INSERT INTO exclusion_presets (id, symbol, list_name, reason) VALUES
  (gen_random_uuid(), 'EXCL-COAL-01',   '예시 프리셋', '석탄'),
  (gen_random_uuid(), 'EXCL-WEAPON-01', '예시 프리셋', '논란무기')
ON CONFLICT (symbol) DO NOTHING;

-- ── ua_ai_configs ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ua_ai_configs (
    user_id     UUID          NOT NULL,
    base_url    VARCHAR(500)  NOT NULL,
    api_key     VARCHAR(2048) NOT NULL,
    model       VARCHAR(200)  NOT NULL,
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_ai_configs PRIMARY KEY (user_id)
);

ALTER TABLE IF EXISTS ua_ai_configs
    ALTER COLUMN api_key TYPE VARCHAR(2048);

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

-- ── cash_flow ──────────────────────────────────────────────────
-- 입출금 원장 (R1 #33): TWR/MWR 계산의 현금흐름 조정 입력. KRW 환산은 기록 시점 고정
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

-- ── user_benchmark ─────────────────────────────────────────────
-- 사용자 벤치마크 설정 (R1 #35): R-01/R-02 "BM 대비"의 기준 지수
CREATE TABLE IF NOT EXISTS user_benchmark (
    user_id     UUID        NOT NULL,
    index_type  VARCHAR(20) NOT NULL,
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_benchmark PRIMARY KEY (user_id)
);

-- ── feedback ───────────────────────────────────────────────────
-- 앱 내 1:1 문의 접수 (AF-94). 접수 전용 — 목록·답변·공개 게시판은 범위 밖.
-- 사용자는 유형과 본문만 적고, 재현 정보는 화면이 함께 보낸다.
-- 첨부파일은 받지 않는다 — 자산관리 앱 스크린샷에는 계좌번호·잔고가 그대로 담긴다.
CREATE TABLE IF NOT EXISTS feedback (
    id              UUID          NOT NULL,
    user_id         UUID          NOT NULL,
    kind            VARCHAR(20)   NOT NULL,   -- BUG / IMPROVEMENT / QUESTION
    message         VARCHAR(2000) NOT NULL,
    page_url        VARCHAR(500),             -- 문의를 남긴 화면
    user_agent      VARCHAR(500),
    viewport        VARCHAR(20),              -- "1280x720" — 모바일/데스크톱 구분
    last_api_error  VARCHAR(500),             -- 상태코드 + 엔드포인트
    console_errors  VARCHAR(2000),            -- 최근 프론트 예외 (최대 5건)
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_feedback PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_feedback_created
    ON feedback (created_at DESC);

-- ── fx_rate_daily ──────────────────────────────────────────────
-- AF-100 과거 환율 시계열. 현금흐름(cash_flow.amount_krw)을 발생일 환율로 환산하기 위한 것.
-- 자산 평가는 Redis 현재 환율을 계속 쓴다 — 여기는 "그때 얼마였나" 전용.
-- rate_krw는 항상 통화 1단위 기준. ECOS는 JPY를 100엔 기준으로 주므로 수집기가 정규화한다.
CREATE TABLE IF NOT EXISTS fx_rate_daily (
    id         UUID           NOT NULL,
    base_date  DATE           NOT NULL,
    currency   VARCHAR(10)    NOT NULL,
    rate_krw   NUMERIC(18, 6) NOT NULL,
    source     VARCHAR(20)    NOT NULL DEFAULT 'ECOS',
    created_at TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_fx_rate_daily PRIMARY KEY (id),
    CONSTRAINT uk_fx_rate_daily UNIQUE (base_date, currency)
);

CREATE INDEX IF NOT EXISTS idx_fx_rate_daily_lookup
    ON fx_rate_daily (currency, base_date DESC);

-- ── hana_fx_quote ──────────────────────────────────────────────
-- AF-99 하나은행 회차별 고시환율. ECOS 일별 확정 종가(fx_rate_daily)와 섞지 않는다 —
-- 저쪽은 하루 한 건, 이쪽은 하루 안 여러 회차 × 통화별 6개 환율이다.
-- 키가 (기준일, 회차, 통화)인 이유: 하나은행은 주말·공휴일에 조회하면 직전 영업일 고시를
-- 돌려준다. 조회일자를 키로 쓰면 연휴 사흘 동안 같은 고시가 세 번 들어간다.
CREATE TABLE IF NOT EXISTS hana_fx_quote (
    id            UUID          NOT NULL,
    base_date     DATE          NOT NULL,
    round_no      INT           NOT NULL,
    currency      VARCHAR(10)   NOT NULL,
    base_rate     NUMERIC(18,4) NOT NULL,
    cash_buy      NUMERIC(18,4),
    cash_sell     NUMERIC(18,4),
    remit_send    NUMERIC(18,4),
    remit_receive NUMERIC(18,4),
    collected_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_hana_fx_quote PRIMARY KEY (id),
    CONSTRAINT uk_hana_fx_quote UNIQUE (base_date, round_no, currency)
);

CREATE INDEX IF NOT EXISTS idx_hana_fx_quote_latest
    ON hana_fx_quote (currency, base_date DESC, round_no DESC);
