-- 해외 거래소 자산의 통화 라벨 USD → USDT — 운영 Neon 1회성 백필
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-08-12-exchange-asset-currency-usdt.sql
--
-- Binance·OKX·Bybit 어댑터는 거래소 오더북의 USDT 호가로 평가액을 만들면서 통화만 'USD'로
-- 적어 왔다. 어댑터를 고쳤으므로 이미 저장된 행도 맞춘다.
--
-- **필수는 아니다.** ua_assets의 EXCHANGE_API 행은 sync마다 전체 교체된다
-- (SyncAccountUseCase: deleteByAccountId → saveAll). 세 provider 모두
-- DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS에 있어 마감 배치가 매일 다시 쓴다.
-- 이 스크립트는 ERROR 상태로 멈췄거나 연동이 끊겨 한동안 재동기화되지 않을 계좌를
-- 즉시 정정하는 용도다. 배포 전후 아무 때나 돌려도 되고, 멱등하다.
--
-- 국내 거래소(UPBIT·BITHUMB·COINONE)는 이미 'KRW'를 쓰므로 WHERE에 걸리지 않는다.
-- 지갑(WALLET, source_type='WALLET')은 Moralis usd_value 기반이라 'USD'가 맞다 — 건드리지 않는다.

-- 실행 전 분포 확인
SELECT a.provider, s.source_type, s.currency, count(*)
  FROM ua_assets s JOIN ua_accounts a ON a.id = s.account_id
 GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;

BEGIN;

UPDATE ua_assets s
   SET currency        = 'USDT',
       last_updated_at = NOW()
  FROM ua_accounts a
 WHERE a.id           = s.account_id
   AND s.source_type  = 'EXCHANGE_API'
   AND s.currency     = 'USD'
   AND a.provider IN ('BINANCE', 'BYBIT', 'OKX');

COMMIT;

-- 검증: 아래 두 쿼리 모두 0행이어야 한다
-- (1) 해외 거래소인데 아직 USD인 행
SELECT count(*) AS leftover_usd
  FROM ua_assets s JOIN ua_accounts a ON a.id = s.account_id
 WHERE s.source_type = 'EXCHANGE_API' AND s.currency = 'USD'
   AND a.provider IN ('BINANCE', 'BYBIT', 'OKX');

-- (2) 대상이 아닌데 USDT가 된 행 — 지갑이나 국내 거래소가 섞여 들어갔는지.
--     MANUAL·CSV는 제외한다: 사용자가 직접 USDT를 고를 수 있고(Currencies.SUPPORTED,
--     프론트 통화 select) 그건 정상이다.
SELECT count(*) AS unexpected_usdt
  FROM ua_assets s JOIN ua_accounts a ON a.id = s.account_id
 WHERE s.currency = 'USDT'
   AND s.source_type IN ('EXCHANGE_API', 'WALLET')
   AND a.provider NOT IN ('BINANCE', 'BYBIT', 'OKX');
