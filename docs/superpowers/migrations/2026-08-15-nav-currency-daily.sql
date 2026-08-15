-- AF-106 통화별 일간 평가액 — 수익 기여도 분해(자산 vs 환율)의 원자료
--
-- 왜 이 테이블이 필요한가: performance_daily.nav는 원화 총액 하나뿐이고,
-- 시세가 스냅샷에 들어가기 전에 KRW로 환산되어(SnapshotTriggerService)
-- 저장된 과거 NAV에는 통화 흔적이 남지 않는다. 게다가 그 환산은 날짜를 안 받아서
-- 과거 스냅샷을 재계산해도 현재 환율이 적용된다. 소급 복원은 자산별 과거 시세
-- 테이블이 없어 원리적으로 불가능하므로, 오늘부터 관측을 쌓는다.
--
-- value_krw를 저장하지 않는다: value_native * fx_rate로 나오고,
-- 저장하면 셋이 어긋나는 날 무엇이 맞는지 가릴 수 없다.
--
-- 미지원 통화는 fx_rate = 1이다: CurrencyConverter가 실제로 환산하는 통화는
-- KRW·USD·USDT·BTC·ETH 다섯뿐이고 나머지는 경고만 남기고 원금을 그대로 돌려준다.
-- 그 동작을 그대로 기록해야 아래 불변식이 성립하고, currency <> 'KRW'인데
-- fx_rate = 1인 행이 미환산 자산의 진단 지표가 된다.
--
-- 불변식: SUM(value_native * fx_rate) ~= performance_daily.nav (같은 portfolio_id, date)
--   정확히 같지는 않다 — toKrw가 자산별 가격을 원 단위로 반올림한 뒤 수량을 곱하므로
--   자산마다 최대 0.5 * quantity 만큼 벌어진다.
--
-- 별도 인덱스를 만들지 않는다: PK 선두 두 열 (portfolio_id, date)가 기간 조회를 그대로 받는다.

CREATE TABLE IF NOT EXISTS nav_currency_daily (
    portfolio_id  UUID           NOT NULL,
    date          DATE           NOT NULL,
    currency      VARCHAR(10)    NOT NULL,
    value_native  NUMERIC(30,10) NOT NULL,
    fx_rate       NUMERIC(30,10) NOT NULL,
    created_at    TIMESTAMP      NOT NULL DEFAULT NOW(),
    PRIMARY KEY (portfolio_id, date, currency)
);

COMMENT ON TABLE  nav_currency_daily              IS 'AF-106 통화별 일간 평가액 — 자산/환율 기여도 분해용';
COMMENT ON COLUMN nav_currency_daily.value_native IS '그날의 외화 기준 평가액 (환산 전)';
COMMENT ON COLUMN nav_currency_daily.fx_rate      IS '그날 적용한 1단위당 KRW. KRW와 미지원 통화는 1';
