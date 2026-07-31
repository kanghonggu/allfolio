-- R-06 환전/계좌간이체 Phase 1 — 운영 Neon 1회성 (백엔드 배포 "전" 실행)
-- 추가형·멱등·무해(신규 nullable 컬럼).
ALTER TABLE cash_flow ADD COLUMN IF NOT EXISTS link_id UUID;
