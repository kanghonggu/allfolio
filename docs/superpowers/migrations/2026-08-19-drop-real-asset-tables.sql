-- A1 방향 전환 — 쓰지 않게 된 표 둘 정리
-- 실행: /opt/homebrew/opt/libpq/bin/psql "<NEON_DB_URL>" -f 2026-08-19-drop-real-asset-tables.sql
--
--
-- ## 왜 만들었다가 지우는가
--
-- 2026-08-18에 `2026-08-18-real-asset-tables.sql`로 `real_asset`·`real_asset_valuation`을
-- 만들어 운영에 적용했다. 설계 문서(작업지시서)가 실물자산 전용 표를 명시했기 때문이다.
--
-- 그런데 **제품에는 이미 같은 개념이 있었다.** `ua_assets`의 `AssetType.GOLD`이고,
-- 계좌 > 자산 등록에 전용 폼(단위 g/돈/oz · 중량 · 매입 단가 · 현재 총 가치)까지 있으며,
-- 대시보드 "실물·고정 자산" 섹션과 순자산·배분 차트·리포트가 전부 그걸 쓴다.
-- 게다가 `valuation_method`(MARKET_PRICE/BALANCE/USER_INPUT)와
-- `confidence_level`(HIGH/MEDIUM/LOW)까지 있어서, 그 모델은 **"수동 입력 vs 자동 평가"를
-- 이미 예상하고 설계돼 있었다.** 빠져 있던 것은 표가 아니라 자동 평가였다.
--
-- 표를 둘로 두면 사용자가 금을 넣는 곳이 두 곳이 되고 한쪽만 순자산에 잡힌다.
-- 그래서 A1은 전용 표를 접고 `ua_assets`의 GOLD 행을 갱신하는 쪽으로 방향을 바꿨다.
--
--
-- ## 안전한가
--
-- **두 표 모두 한 번도 쓰이지 않았다.** 적용 후 배포 전에 방향이 바뀌었고, 운영에서 이 표를
-- 읽거나 쓰는 코드가 배포된 적이 없다. 아래 가드가 그 사실을 실행 시점에 다시 확인한다 —
-- 행이 하나라도 있으면 **일부러 실패시킨다.** 예상과 다른 상태에서 조용히 지우지 않는다.
--
-- 순서: 스냅샷(자식) → 자산(부모). FK가 CASCADE지만 명시적으로 지운다.

DO $$
DECLARE
    asset_rows     BIGINT := 0;
    valuation_rows BIGINT := 0;
BEGIN
    IF to_regclass('real_asset') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM real_asset' INTO asset_rows;
    END IF;
    IF to_regclass('real_asset_valuation') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM real_asset_valuation' INTO valuation_rows;
    END IF;

    IF asset_rows > 0 OR valuation_rows > 0 THEN
        RAISE EXCEPTION
            '중단: 표가 비어 있지 않습니다 (real_asset=%, real_asset_valuation=%). 데이터를 확인하고 옮긴 뒤 다시 실행하세요.',
            asset_rows, valuation_rows;
    END IF;

    RAISE NOTICE '두 표 모두 비어 있음 — 드롭을 진행합니다.';
END $$;

DROP TABLE IF EXISTS real_asset_valuation;
DROP TABLE IF EXISTS real_asset;

-- ── 검증 ────────────────────────────────────────────────────────────────────
SELECT
    to_regclass('real_asset')           AS real_asset_should_be_null,
    to_regclass('real_asset_valuation') AS valuation_should_be_null;
