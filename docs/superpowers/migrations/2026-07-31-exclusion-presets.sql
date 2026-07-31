-- R-07 배제 프리셋 큐레이션 — 운영 Neon 1회성 (백엔드 배포 "전" 실행). 자립형·멱등.
CREATE TABLE IF NOT EXISTS exclusion_presets (
    id          UUID         PRIMARY KEY,
    symbol      VARCHAR(40)  NOT NULL,
    list_name   VARCHAR(100) NOT NULL,
    reason      VARCHAR(200) NOT NULL,
    updated_by  UUID,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_exclusion_presets_symbol UNIQUE (symbol)
);
INSERT INTO exclusion_presets (id, symbol, list_name, reason) VALUES
  (gen_random_uuid(), 'EXCL-COAL-01',   '예시 프리셋', '석탄'),
  (gen_random_uuid(), 'EXCL-WEAPON-01', '예시 프리셋', '논란무기')
ON CONFLICT (symbol) DO NOTHING;
SELECT symbol, list_name, reason FROM exclusion_presets ORDER BY symbol;
