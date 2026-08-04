-- QA 후속 #2 (2026-08-04) — TWR external flow 미인식 소급 보정. 운영 Neon 1회성.
--
-- 배경: 계좌 최초 동기화 시 초기 평가액을 자동 DEPOSIT flow로 남기는 로직(P1 #8,
-- SyncAccountUseCase.recordInitialInflow)은 배포되어 있으나, 그 "이전"에 연동된 계좌의
-- 유입은 cash_flow에 없다. 그래서 netFlow=0으로 계산돼 계좌 연동 유입(179만→4,310만
-- 급점프)이 전부 수익으로 잡혀 TWR +2060%가 나온다.
--
-- 방법: performance_daily의 인접 스냅샷 쌍에서, 그 구간에 기록된 외부 플로우
-- (DEPOSIT/WITHDRAWAL)로 설명되지 않는 급증을 계좌 연동 유입으로 보고
-- 미설명분(delta - 기록된 net flow)을 DEPOSIT으로 소급 기록한다.
--   급증 판정(보수적): 전일 대비 2배 초과 AND 미설명분 +100만원 초과
--   (일 시세 변동이나 정상 입금 기록이 있는 날은 잡히지 않는다)
--
-- 멱등: 같은 (user, 날짜)에 소급 보정 flow가 이미 있으면 건너뜀.
-- 실행 순서: ① 미리보기 SELECT로 대상 확인 → ② INSERT 실행 → ③ 검증.

-- ① 미리보기 — 대상 급점프 구간과 삽입될 금액 확인
WITH nav_series AS (
    SELECT portfolio_id AS user_id, date, MAX(nav) AS nav
    FROM performance_daily
    GROUP BY portfolio_id, date
), pairs AS (
    SELECT user_id, date, nav,
           LAG(date) OVER (PARTITION BY user_id ORDER BY date) AS prev_date,
           LAG(nav)  OVER (PARTITION BY user_id ORDER BY date) AS prev_nav
    FROM nav_series
), jumps AS (
    SELECT p.*,
           p.nav - p.prev_nav
             - COALESCE((SELECT SUM(CASE cf.flow_type WHEN 'DEPOSIT' THEN cf.amount_krw
                                                      WHEN 'WITHDRAWAL' THEN -cf.amount_krw
                                                      ELSE 0 END)
                         FROM cash_flow cf
                         WHERE cf.user_id = p.user_id
                           AND cf.flow_date > p.prev_date
                           AND cf.flow_date <= p.date), 0) AS unexplained
    FROM pairs p
    WHERE p.prev_nav IS NOT NULL AND p.prev_nav > 0
)
SELECT user_id, prev_date, date, prev_nav, nav, unexplained
FROM jumps
WHERE nav > prev_nav * 2
  AND unexplained > 1000000
  AND NOT EXISTS (SELECT 1 FROM cash_flow cf
                  WHERE cf.user_id = jumps.user_id
                    AND cf.flow_date = jumps.date
                    AND cf.memo LIKE '계좌 연동 초기 자산 편입%')
ORDER BY user_id, date;

-- ② 삽입 — ①과 동일한 대상에 DEPOSIT 소급 기록
WITH nav_series AS (
    SELECT portfolio_id AS user_id, date, MAX(nav) AS nav
    FROM performance_daily
    GROUP BY portfolio_id, date
), pairs AS (
    SELECT user_id, date, nav,
           LAG(date) OVER (PARTITION BY user_id ORDER BY date) AS prev_date,
           LAG(nav)  OVER (PARTITION BY user_id ORDER BY date) AS prev_nav
    FROM nav_series
), jumps AS (
    SELECT p.*,
           p.nav - p.prev_nav
             - COALESCE((SELECT SUM(CASE cf.flow_type WHEN 'DEPOSIT' THEN cf.amount_krw
                                                      WHEN 'WITHDRAWAL' THEN -cf.amount_krw
                                                      ELSE 0 END)
                         FROM cash_flow cf
                         WHERE cf.user_id = p.user_id
                           AND cf.flow_date > p.prev_date
                           AND cf.flow_date <= p.date), 0) AS unexplained
    FROM pairs p
    WHERE p.prev_nav IS NOT NULL AND p.prev_nav > 0
)
INSERT INTO cash_flow (id, user_id, account_id, flow_date, flow_type, amount, currency, amount_krw, memo)
SELECT gen_random_uuid(), user_id, NULL, date, 'DEPOSIT',
       unexplained, 'KRW', unexplained,
       '계좌 연동 초기 자산 편입(소급 보정 2026-08-04)'
FROM jumps
WHERE nav > prev_nav * 2
  AND unexplained > 1000000
  AND NOT EXISTS (SELECT 1 FROM cash_flow cf
                  WHERE cf.user_id = jumps.user_id
                    AND cf.flow_date = jumps.date
                    AND cf.memo LIKE '계좌 연동 초기 자산 편입%');

-- ③ 검증 — 소급 flow 확인 + returns API netFlow가 급점프를 잡는지 확인
SELECT user_id, flow_date, flow_type, amount_krw, memo
FROM cash_flow
WHERE memo LIKE '계좌 연동 초기 자산 편입%'
ORDER BY user_id, flow_date;
-- 이후 GET /api/reports/returns 에서 summary.netFlow ≈ 급점프 금액,
-- summary.twr 이 매입원가 기준 수익률(-7% 근처)과 같은 부호 대역으로 수렴해야 한다.
