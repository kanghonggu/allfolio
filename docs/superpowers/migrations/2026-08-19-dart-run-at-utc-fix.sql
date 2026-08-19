-- D1 시각 9시간 밀림 소급 정정 — 운영 Neon 1회성
-- 실행: Neon 콘솔 SQL 편집기.
--
-- 무엇이 틀렸나: DartAdminController가 LocalDateTime.now(KST)를 저장 시각으로 넘겼다.
-- 대상 컬럼이 TIMESTAMPTZ인데 Postgres는 naive 값을 세션 타임존(UTC)으로 해석하므로,
-- KST 벽시계가 그대로 UTC로 라벨링돼 실제보다 9시간 미래로 저장됐다.
-- now() - run_at 이 음수인 것으로 드러났다(2026-08-19).
--
-- 조회 날짜(bgn_de·end_de)는 KST가 맞다 — 컨테이너가 UTC라 그냥 now()를 쓰면
-- 19:00 KST 실행이 "어제"를 조회한다. 틀린 것은 저장 시각뿐이다.
--
-- 실측 규모(2026-08-19 20:2x KST 기준):
--   dart_collection_run    4 / 4
--   dart_disclosure      792 / 792
--   dart_insider_trade   102 / 102
--
-- **미래 시각인 행만 고친다** — 코드 수정 배포 후 들어온 정상 행을 건드리면 안 된다.
-- 그래서 WHERE 절이 필요하다. 두 번 실행해도 안전하다(멱등).

BEGIN;

-- 1) 실행 전 규모 (눈으로 확인할 것)
SELECT 'dart_collection_run' AS t, count(*) FILTER (WHERE run_at > now()) AS 미래행, count(*) AS 전체 FROM dart_collection_run
UNION ALL SELECT 'dart_disclosure',    count(*) FILTER (WHERE collected_at > now()), count(*) FROM dart_disclosure
UNION ALL SELECT 'dart_insider_trade', count(*) FILTER (WHERE collected_at > now()), count(*) FROM dart_insider_trade;

-- 2) 보정
UPDATE dart_collection_run
   SET run_at      = run_at      - INTERVAL '9 hours',
       finished_at = finished_at - INTERVAL '9 hours'
 WHERE run_at > now();

UPDATE dart_disclosure
   SET collected_at = collected_at - INTERVAL '9 hours'
 WHERE collected_at > now();

UPDATE dart_insider_trade
   SET collected_at = collected_at - INTERVAL '9 hours'
 WHERE collected_at > now();

-- 3) 보정 후 미래 행이 0이어야 한다. 0이 아니면 롤백하고 원인을 다시 볼 것
SELECT 'dart_collection_run' AS t, count(*) FILTER (WHERE run_at > now()) AS 남은미래 FROM dart_collection_run
UNION ALL SELECT 'dart_disclosure',    count(*) FILTER (WHERE collected_at > now()) FROM dart_disclosure
UNION ALL SELECT 'dart_insider_trade', count(*) FILTER (WHERE collected_at > now()) FROM dart_insider_trade;

COMMIT;
