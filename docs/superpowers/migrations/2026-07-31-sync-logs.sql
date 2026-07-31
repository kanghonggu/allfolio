-- AF-9 계좌 동기화 이력 — 운영 Neon 1회성 (백엔드 배포 "전" 실행)
-- 자립형·멱등·무해: 신규 테이블 생성만 하므로 기존 데이터에 영향 없음.
-- ddl-auto:none이므로 이 파일을 실행하지 않으면 배포 후 sync 시 로그 저장이 조용히 실패한다
-- (SyncAccountUseCase가 runCatching으로 격리해 동기화 자체는 정상 동작).

CREATE TABLE IF NOT EXISTS ua_sync_logs (
    id            UUID         NOT NULL,
    account_id    UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    trigger_type  VARCHAR(20)  NOT NULL,   -- SCHEDULED / MANUAL
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

-- 검증
SELECT column_name, data_type FROM information_schema.columns
WHERE table_name = 'ua_sync_logs' ORDER BY ordinal_position;
