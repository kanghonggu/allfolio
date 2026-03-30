-- ============================================================
-- Allfolio Schema — INSERT ONLY 설계
-- ============================================================

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

-- ── FILLFACTOR 최적화 ──────────────────────────────────────────
-- INSERT ONLY 테이블은 UPDATE가 없으므로 fillfactor=100 (기본값)
-- 인덱스도 fillfactor=100으로 여유 공간 제거 → INSERT 성능 향상
CREATE INDEX IF NOT EXISTS idx_trade_raw_portfolio_executed_perf
    ON trade_raw (portfolio_id, executed_at ASC)
    WITH (fillfactor = 100);

