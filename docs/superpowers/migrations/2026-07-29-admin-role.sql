-- ADMIN role (P0) — 운영 Neon 1회성 마이그레이션
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-07-29-admin-role.sql
-- 반드시 백엔드 배포 "전"에 실행 (ddl-auto: none, 컬럼 부재 시 role SELECT 실패).

ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';

UPDATE app_users
    SET role = 'ADMIN'
    WHERE email = 'rkdghd123@naver.com'
    RETURNING email, role;   -- 0 rows = 해당 계정 없음(오타/미가입). 관리자 0명 상태이므로 반드시 확인.

-- 검증: 승격된 관리자 목록(정확히 1명이어야 함).
SELECT email, role FROM app_users WHERE role = 'ADMIN';
